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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockTopologyServer implements Closeable {

  public static final String TOPO_HOST = "my-topo-host.org";
  public static final String TOPOFILE_TINY_110 = "topologies/scionproto-tiny-110.json";
  public static final String TOPOFILE_TINY_111 = "topologies/scionproto-tiny-111.json";
  private static final Logger logger = LoggerFactory.getLogger(MockTopologyServer.class.getName());
  private final ExecutorService executor;
  private final AtomicInteger callCount = new AtomicInteger();
  private final CountDownLatch barrier = new CountDownLatch(1);
  private final AtomicReference<InetSocketAddress> serverSocket = new AtomicReference<>();
  private String controlServer;
  private long localIsdAs;
  private final List<BorderRouter> borderRouters = new ArrayList<>();

  private MockTopologyServer(Path topoFile, Path configPath, boolean installNaptr) {
    getAndResetCallCount();
    Path topoResource = toResourcePath(topoFile);
    Path configResource = toResourcePath(configPath);
    executor = Executors.newSingleThreadExecutor();
    executor.submit(new TopologyServerImpl(readTopologyFile(topoResource), configResource));

    try {
      // Wait for sever socket address to be ready
      barrier.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    if (installNaptr) {
      InetSocketAddress topoAddr = serverSocket.get();
      DNSUtil.installNAPTR(TOPO_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME, TOPO_HOST);
    }

    logger.info("Server started, listening on {}", serverSocket);
  }

  public static MockTopologyServer start() {
    return new MockTopologyServer(Paths.get(TOPOFILE_TINY_111), null, false);
  }

  public static MockTopologyServer start(String topoFile) {
    return new MockTopologyServer(Paths.get(topoFile), null, false);
  }

  public static MockTopologyServer start(String topoFile, boolean installNaptr) {
    return new MockTopologyServer(Paths.get(topoFile), null, installNaptr);
  }

  public static MockTopologyServer start(String topoFile, String trcPath) {
    return new MockTopologyServer(Paths.get(topoFile), Paths.get(trcPath), false);
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
    return Integer.parseInt(controlServer.substring(controlServer.indexOf(':') + 1));
  }

  public String getBorderRouterAddressByIA(long remoteIsdAs) {
    for (BorderRouter br : borderRouters) {
      for (BorderRouterInterface brif : br.interfaces) {
        if (brif.isdAs == remoteIsdAs) {
          return br.internalAddress;
        }
      }
    }
    throw new ScionRuntimeException("No router found for IsdAs " + remoteIsdAs);
  }

  public int getAndResetCallCount() {
    return callCount.getAndSet(0);
  }

  private Path toResourcePath(Path file) {
    if (file == null) {
      return null;
    }
    try {
      ClassLoader classLoader = getClass().getClassLoader();
      URL resource = classLoader.getResource(file.toString());
      if (resource != null) {
        return Paths.get(resource.toURI());
      }
      throw new IllegalArgumentException("Resource not found: " + file);
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private String readTopologyFile(java.nio.file.Path file) {
    StringBuilder contentBuilder = new StringBuilder();
    try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
      stream.forEach(s -> contentBuilder.append(s).append("\n"));
    } catch (IOException e) {
      throw new ScionRuntimeException(
          "Error reading topology file found at: " + file.toAbsolutePath());
    }
    parseTopologyFile(contentBuilder.toString());
    return contentBuilder.toString();
  }

  private void parseTopologyFile(String topologyFile) {
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(topologyFile);
    if (jsonTree.isJsonObject()) {
      JsonObject o = jsonTree.getAsJsonObject();
      localIsdAs = ScionUtil.parseIA(safeGet(o, "isd_as").getAsString());
      // localMtu = safeGet(o, "mtu").getAsInt();
      JsonObject brs = safeGet(o, "border_routers").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : brs.entrySet()) {
        JsonObject br = e.getValue().getAsJsonObject();
        String addr = safeGet(br, "internal_addr").getAsString();
        JsonObject ints = safeGet(br, "interfaces").getAsJsonObject();
        List<BorderRouterInterface> interfaces = new ArrayList<>();
        for (Map.Entry<String, JsonElement> ifEntry : ints.entrySet()) {
          JsonObject ife = ifEntry.getValue().getAsJsonObject();
          // TODO bandwidth, mtu, ... etc
          JsonObject underlay = ife.getAsJsonObject("underlay");
          interfaces.add(
              new BorderRouterInterface(
                  ifEntry.getKey(),
                  ife.get("isd_as").getAsString(),
                  underlay.get("public").getAsString(),
                  underlay.get("remote").getAsString()));
        }
        borderRouters.add(new BorderRouter(e.getKey(), addr, interfaces));
      }
      JsonObject css = safeGet(o, "control_service").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : css.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        controlServer = cs.get("addr").getAsString();
        // controlServices.add(
        //     new ScionBootstrapper.ServiceNode(e.getKey(), cs.get("addr").getAsString()));
      }
    }
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
        if (!s.endsWith(".trc")) {
          continue;
        }
        int isd = Integer.parseInt(s.substring(3, s.indexOf("-B")));
        int base = Integer.parseInt(s.substring(s.indexOf("-B") + 2, s.indexOf("-S")));
        int sn = Integer.parseInt(s.substring(s.indexOf("-S") + 2, s.indexOf(".")));
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

  private static JsonElement safeGet(JsonObject o, String name) {
    JsonElement e = o.get(name);
    if (e == null) {
      throw new ScionRuntimeException("Entry not found in topology file: " + name);
    }
    return e;
  }

  public long getLocalIsdAs() {
    return localIsdAs;
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

  private static class BorderRouter {
    private final String name;
    private final String internalAddress;
    private final List<BorderRouterInterface> interfaces;

    public BorderRouter(String name, String addr, List<BorderRouterInterface> interfaces) {
      this.name = name;
      this.internalAddress = addr;
      this.interfaces = interfaces;
    }
  }

  private static class BorderRouterInterface {
    final int id;
    final long isdAs;
    final String publicUnderlay;
    final String remoteUnderlay;

    public BorderRouterInterface(String id, String isdAs, String publicU, String remoteU) {
      this.id = Integer.parseInt(id);
      this.isdAs = ScionUtil.parseIA(isdAs);
      this.publicUnderlay = publicU;
      this.remoteUnderlay = remoteU;
    }
  }
}
