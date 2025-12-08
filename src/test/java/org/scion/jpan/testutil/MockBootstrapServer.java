// Copyright 2023 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.jpan.testutil;

import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.jpan.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockBootstrapServer implements Closeable {

  public static final String TOPO_HOST = "my-topo-host.org";
  public static final String CONFIG_DIR_TINY = "topologies/tiny4/";
  public static final String TOPO_TINY_110 = CONFIG_DIR_TINY + "ASff00_0_110/";
  private static final Logger logger = LoggerFactory.getLogger(MockBootstrapServer.class.getName());
  private final ExecutorService executor;
  private final AtomicInteger callCount = new AtomicInteger();
  private final CountDownLatch barrier = new CountDownLatch(1);
  private final AtomicReference<InetSocketAddress> serverSocket = new AtomicReference<>();
  private final AsInfo asInfo;

  private MockBootstrapServer(Path topoDir, Path configPath, boolean installNaptr) {
    getAndResetCallCount();
    Path configResource = JsonFileParser.toResourcePath(configPath);
    asInfo = JsonFileParser.parseTopology(topoDir);
    executor = Executors.newSingleThreadExecutor();
    Path topoFile = topoDir.resolve("topology.json");
    executor.submit(new TopologyServerImpl(JsonFileParser.readFile(topoFile), configResource));

    try {
      // Wait for sever socket address to be ready
      barrier.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    if (installNaptr) {
      InetSocketAddress topoAddr = serverSocket.get();
      DNSUtil.bootstrapNAPTR(TOPO_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME, TOPO_HOST);
    }

    logger.info("Server started, listening on {}", serverSocket);
  }

  public static MockBootstrapServer start(String topoDir, boolean installNaptr) {
    if (!TOPO_TINY_110.equals(topoDir)) {
      throw new UnsupportedOperationException("Add config dir");
    }
    return new MockBootstrapServer(Paths.get(topoDir), Paths.get(CONFIG_DIR_TINY), installNaptr);
  }

  /**
   * Start a new bootstrap server.
   *
   * @param cfgPath path to topology folder
   * @param topoDir sub-path to topology file
   * @return server instance
   */
  public static MockBootstrapServer start(String cfgPath, String topoDir) {
    return new MockBootstrapServer(Paths.get(cfgPath + topoDir), Paths.get(cfgPath), false);
  }

  @Override
  public void close() {
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME);
    try {
      executor.shutdownNow();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.error("Topology server did not terminate");
      }
      logger.info("Topology server shut down");
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    DNSUtil.clear();
  }

  public InetSocketAddress getAddress() {
    return serverSocket.get();
  }

  public int getControlServerPort() {
    return asInfo.getControlServerPort();
  }

  public InetSocketAddress getControlServerAddress() {
    try {
      InetAddress addr = InetAddress.getByName(asInfo.getControlServerIP());
      return new InetSocketAddress(addr, asInfo.getControlServerPort());
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  public String getBorderRouterAddressByIA(long remoteIsdAs) {
    return asInfo.getBorderRouterAddressByIA(remoteIsdAs);
  }

  public int getAndResetCallCount() {
    return callCount.getAndSet(0);
  }

  private String readTrcFiles(Path resource) {
    if (resource == null) {
      return "[\n]";
    }
    File file = new File(resource.toFile(), "trcs");

    try {
      StringWriter sw = new StringWriter();
      JsonWriter jw = new GsonBuilder().setPrettyPrinting().create().newJsonWriter(sw);
      jw.beginArray();
      for (String s : file.list()) {
        if (!s.endsWith(".json")) {
          continue;
        }
        int isd = Integer.parseInt(s.substring(3, s.indexOf("-b")));
        int base = Integer.parseInt(s.substring(s.indexOf("-b") + 2, s.indexOf("-s")));
        int sn = Integer.parseInt(s.substring(s.indexOf("-s") + 2, s.indexOf(".")));
        jw.beginObject().name("id").beginObject();
        jw.name("base_number").value(base);
        jw.name("isd").value(isd);
        jw.name("serial_number").value(sn);
        jw.endObject().endObject();
      }
      jw.endArray();
      return sw.toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public long getLocalIsdAs() {
    return asInfo.getIsdAs();
  }

  public AsInfo getASInfo() {
    return asInfo;
  }

  private class TopologyServerImpl implements Runnable {
    private final String topologyFile;
    private final String trcsFilesJson;
    private final Path serverPath;

    TopologyServerImpl(String topologyFile, Path serverPath) {
      this.topologyFile = topologyFile;
      this.trcsFilesJson = readTrcFiles(serverPath);
      this.serverPath = serverPath;
    }

    @Override
    public void run() {
      try (ServerSocketChannel chnLocal = ServerSocketChannel.open()) {
        // Explicit binding to "localhost" to avoid automatic binding to IPv6 which is not
        // supported by GitHub CI (https://github.com/actions/runner-images/issues/668).
        InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 45678);
        chnLocal.bind(local);
        chnLocal.configureBlocking(true);
        ByteBuffer buffer = ByteBuffer.allocate(66000);
        serverSocket.set((InetSocketAddress) chnLocal.getLocalAddress());
        logger.info("Topology server started on port {}", chnLocal.getLocalAddress());
        barrier.countDown();
        while (true) {
          SocketChannel ss = chnLocal.accept();
          ss.read(buffer);
          SocketAddress srcAddress = ss.getRemoteAddress();

          buffer.flip();

          // Expected:
          //   "GET /topology HTTP/1.1"
          //   "GET /trcs HTTP/1.1"
          String request = Charset.defaultCharset().decode(buffer).toString();
          String resource = request.substring(request.indexOf(" ") + 1, request.indexOf(" HTTP"));
          if ("/topology".equals(resource)) {
            logger.info("Bootstrap server serves file to {}", srcAddress);
            callCount.incrementAndGet();
            buffer.clear();
            buffer.put(createMessage(topologyFile).getBytes());
            buffer.flip();
            ss.write(buffer);
          } else if ("/trcs".equals(resource)) {
            logger.info("Bootstrap server serves file to {}", srcAddress);
            buffer.clear();
            buffer.put(createMessage(trcsFilesJson).getBytes());
            buffer.flip();
            ss.write(buffer);
          } else if (resource.startsWith("/trcs/")) {
            String fileName = resource.substring(1) + ".json";
            Path file = new File(serverPath.toFile(), fileName).toPath();
            logger.info("Bootstrap server serves file to {}: {}", srcAddress, file);
            buffer.clear();
            String data = new String(Files.readAllBytes(file));
            buffer.put(createMessage(data).getBytes());
            buffer.flip();
            ss.write(buffer);
          } else {
            logger.warn("Illegal request: {}", request);
          }
          buffer.clear();
        }

      } catch (ClosedByInterruptException e) {
        throw new RuntimeException(e);
      } catch (IOException e) {
        logger.error(e.getMessage());
        throw new RuntimeException(e);
      } finally {
        logger.info("Shutting down topology server");
      }
    }

    private String createMessage(String content) {
      return "HTTP/1.1 200 OK\n"
          + "Connection: close\n"
          + "Content-Type: text/plain\n"
          + "Content-Length:"
          + content.length()
          + "\n"
          + "\n"
          + content
          + "\n";
    }
  }
}
