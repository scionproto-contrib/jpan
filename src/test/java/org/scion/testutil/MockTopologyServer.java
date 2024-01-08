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

package org.scion.testutil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.scion.ScionRuntimeException;
import org.scion.ScionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockTopologyServer implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(MockTopologyServer.class.getName());
  private final ExecutorService server;
  private final AtomicInteger callCount = new AtomicInteger();
  private final CountDownLatch barrier = new CountDownLatch(1);
  private final AtomicReference<InetSocketAddress> serverSocket = new AtomicReference<>();
  private String controlServer;
  private long localIsdAs;

  public MockTopologyServer(Path topoFile) {
    getAndResetCallCount();
    server = Executors.newSingleThreadExecutor();
    TopologyServerImpl serverInstance = new TopologyServerImpl(readTopologyFile(topoFile));
    server.submit(serverInstance);

    try {
      // Wait for sever socket address to be ready
      barrier.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    logger.info("Server started, listening on " + serverSocket);
  }

  public static MockTopologyServer start() throws IOException {
    return new MockTopologyServer(Paths.get("topologies/scionproto-tiny-111.json"));
  }

  public static MockTopologyServer start(Path topoFile) throws IOException {
    return new MockTopologyServer(topoFile);
  }

  public static MockTopologyServer start(String topoFile) throws IOException {
    return new MockTopologyServer(Paths.get(topoFile));
  }

  @Override
  public void close() {
    try {
      server.shutdownNow();
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.error("Topology server did not terminate");
      }
      logger.info("Topology server shut down");
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  public InetSocketAddress getAddress() {
    return serverSocket.get();
  }

  public int getControlServerPort() {
    return Integer.parseInt(controlServer.substring(controlServer.indexOf(':') + 1));
  }

  public int getAndResetCallCount() {
    return callCount.getAndSet(0);
  }

  private String readTopologyFile(java.nio.file.Path file) {
    try {
      if (!Files.exists(file)) {
        // fallback, try resource folder
        ClassLoader classLoader = getClass().getClassLoader();
        URL resource = classLoader.getResource(file.toString());
        if (resource != null) {
          file = Paths.get(resource.toURI());
        }
      }
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }

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
      // JsonObject brs = safeGet(o, "border_routers").getAsJsonObject();
      //      for (Map.Entry<String, JsonElement> e : brs.entrySet()) {
      //        JsonObject br = e.getValue().getAsJsonObject();
      //        String addr = safeGet(br, "internal_addr").getAsString();
      //        JsonObject ints = safeGet(br, "interfaces").getAsJsonObject();
      //        List<ScionBootstrapper.BorderRouterInterface> interfaces = new ArrayList<>();
      //        for (Map.Entry<String, JsonElement> ifEntry : ints.entrySet()) {
      //          JsonObject ife = ifEntry.getValue().getAsJsonObject();
      //          // TODO bandwidth, mtu, ... etc
      //          JsonObject underlay = ife.getAsJsonObject("underlay");
      //          interfaces.add(
      //              new ScionBootstrapper.BorderRouterInterface(
      //                  ifEntry.getKey(),
      //                  underlay.get("public").getAsString(),
      //                  underlay.get("remote").getAsString()));
      //        }
      //        borderRouters.add(new ScionBootstrapper.BorderRouter(e.getKey(), addr, interfaces));
      //      }
      JsonObject css = safeGet(o, "control_service").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : css.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        controlServer = cs.get("addr").getAsString();
        // controlServices.add(
        //     new ScionBootstrapper.ServiceNode(e.getKey(), cs.get("addr").getAsString()));
      }
    }
  }

  private static JsonElement safeGet(JsonObject o, String name) {
    JsonElement e = o.get(name);
    if (e == null) {
      throw new ScionRuntimeException("Entry not found in topology file: " + name);
    }
    return e;
  }

  private class TopologyServerImpl implements Runnable {
    private final String topologyFile;

    TopologyServerImpl(String topologyFile) {
      this.topologyFile = topologyFile;
    }

    @Override
    public void run() {
      try (ServerSocketChannel chnLocal = ServerSocketChannel.open().bind(null)) {
        chnLocal.configureBlocking(true);
        ByteBuffer buffer = ByteBuffer.allocate(66000);
        serverSocket.set((InetSocketAddress) chnLocal.getLocalAddress());
        barrier.countDown();
        logger.info("Topology server started on port " + chnLocal.getLocalAddress());
        while (true) {
          SocketChannel ss = chnLocal.accept();
          ss.read(buffer);
          SocketAddress srcAddress = ss.getRemoteAddress();

          buffer.flip();

          String request = Charset.defaultCharset().decode(buffer).toString();
          if (request.contains("GET /topology HTTP/1.1")) {
            logger.info("Topology server serves file to " + srcAddress);
            buffer.clear();

            StringBuilder out = new StringBuilder();
            out.append("HTTP/1.1 200 OK\n");
            out.append("Connection: close\n");
            out.append("Content-Type: text/plain\n");
            out.append("Content-Length:").append(topologyFile.length()).append("\n");
            out.append("\n");
            out.append(topologyFile).append("\n");

            buffer.put(out.toString().getBytes());
            buffer.flip();
            ss.write(buffer);
          } else {
            logger.warn("Illegal request: " + request);
          }
          buffer.clear();
          callCount.incrementAndGet();
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
  }
}
