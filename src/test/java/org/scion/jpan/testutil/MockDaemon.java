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

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.scion.jpan.Constants;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockDaemon implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(MockDaemon.class.getName());

  public static final String DEFAULT_IP = "127.0.0.15";
  public static final int DEFAULT_PORT = 22222; // 30255 in production
  public static final InetSocketAddress DEFAULT_ADDRESS =
      new InetSocketAddress(DEFAULT_IP, DEFAULT_PORT);
  public static final String DEFAULT_ADDRESS_STR = DEFAULT_ADDRESS.toString().substring(1);

  public static MockDaemon DEFAULT = null;

  private final InetSocketAddress address;
  private Server server;
  private final List<MockBorderRouter> borderRouters;
  private final AsInfo asInfo;
  private static final AtomicInteger callCount = new AtomicInteger();
  private static final byte[] PATH_RAW_TINY_110_112 = {
    0, 0, 32, 0, 1, 0, 11, 16,
    101, 83, 118, -81, 0, 63, 0, 0,
    0, 2, 118, -21, 86, -46, 89, 0,
    0, 63, 0, 1, 0, 0, -8, 2,
    -114, 25, 76, -122,
  };
  // Same as PATH_RAW_TINY_110_112 but uses interfaces 11<>22
  private static final byte[] PATH_RAW_TINY_110_112_b = {
    0, 0, 32, 0, 1, 0, 11, 16,
    101, 83, 118, -81, 0, 63, 0, 0,
    0, 22, 118, -21, 86, -46, 89, 0,
    0, 63, 0, 11, 0, 0, -8, 2,
    -114, 25, 76, -122,
  };

  private static void setEnvironment() {
    System.setProperty(Constants.PROPERTY_DAEMON, DEFAULT_IP + ":" + DEFAULT_PORT);
  }

  public static MockDaemon createForBorderRouter(List<MockBorderRouter> borderRouter) {
    setEnvironment();
    return new MockDaemon(DEFAULT_ADDRESS, borderRouter);
  }

  public static void createAndStartDefault() throws IOException {
    if (DEFAULT != null) {
      throw new NullPointerException();
    }
    setEnvironment();
    DEFAULT = new MockDaemon(DEFAULT_ADDRESS);
    DEFAULT.start();
  }

  public static void closeDefault() throws IOException {
    if (DEFAULT != null) {
      DEFAULT.close();
      DEFAULT = null;
    }
    System.clearProperty(Constants.PROPERTY_DAEMON);
  }

  private MockDaemon(InetSocketAddress address) {
    this.address = address;
    this.borderRouters = new ArrayList<>();
    InetSocketAddress bind1 = new InetSocketAddress("127.0.0.10", 31004);
    InetSocketAddress bind2 = new InetSocketAddress("127.0.0.25", 31012);
    this.borderRouters.add(new MockBorderRouter(0, bind1, bind2, 2, 1));
    asInfo = JsonFileParser.parseTopologyFile(Paths.get(MockBootstrapServer.TOPOFILE_TINY_110));
  }

  private MockDaemon(InetSocketAddress address, List<MockBorderRouter> borderRouters) {
    this.address = address;
    this.borderRouters = borderRouters;
    asInfo = JsonFileParser.parseTopologyFile(Paths.get(MockBootstrapServer.TOPOFILE_TINY_110));
  }

  public MockDaemon start() throws IOException {
    int port = address.getPort();
    server =
        Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .addService(new MockDaemon.DaemonImpl(borderRouters, asInfo))
            .build()
            .start();
    logger.info("Server started, listening on {}", address);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                  }
                }));
    return this;
  }

  @Override
  public void close() throws IOException {
    if (server != null) {
      server.shutdown(); // Disable new tasks from being submitted
      try {
        // Wait a while for existing tasks to terminate
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
          server.shutdownNow(); // Cancel currently executing tasks
          // Wait a while for tasks to respond to being cancelled
          if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
            logger.error("Daemon server did not terminate");
          }
        }
        logger.info("Daemon server shut down");
      } catch (InterruptedException ie) {
        // (Re-)Cancel if current thread also interrupted
        server.shutdownNow();
        // Preserve interrupt status
        Thread.currentThread().interrupt();
      }
    }
  }

  public AsInfo getASInfo() {
    return asInfo;
  }

  public static int getAndResetCallCount() {
    return callCount.getAndSet(0);
  }

  static class DaemonImpl extends DaemonServiceGrpc.DaemonServiceImplBase {
    final List<MockBorderRouter> borderRouters;
    final AsInfo asInfo;

    DaemonImpl(List<MockBorderRouter> borderRouters, AsInfo asInfo) {
      this.borderRouters = borderRouters;
      this.asInfo = asInfo;
    }

    @Override
    public void paths(
        Daemon.PathsRequest req, StreamObserver<Daemon.PathsResponse> responseObserver) {
      logger.debug(
          "Got request from client: {} / {}", req.getSourceIsdAs(), req.getDestinationIsdAs());
      callCount.incrementAndGet();
      ByteString rawPath1 = ByteString.copyFrom(PATH_RAW_TINY_110_112);
      ByteString rawPath11 = ByteString.copyFrom(PATH_RAW_TINY_110_112_b);
      long expirySecs = Instant.now().getEpochSecond() + Constants.DEFAULT_PATH_EXPIRY_MARGIN + 5;
      Timestamp expiry = Timestamp.newBuilder().setSeconds(expirySecs).build();
      Daemon.PathsResponse.Builder replyBuilder = Daemon.PathsResponse.newBuilder();
      if (req.getSourceIsdAs() == ExamplePacket.SRC_IA
          && req.getDestinationIsdAs() == ExamplePacket.DST_IA) {
        for (MockBorderRouter br : borderRouters) {
          String brAddress = br.getAddress1().toString().substring(1);
          ByteString rawPath = br.getInterfaceId2() == 1 ? rawPath1 : rawPath11;
          Daemon.Path p0 =
              Daemon.Path.newBuilder()
                  .setInterface(
                      Daemon.Interface.newBuilder()
                          .setAddress(Daemon.Underlay.newBuilder().setAddress(brAddress).build())
                          .build())
                  .addInterfaces(
                      Daemon.PathInterface.newBuilder()
                          .setId(br.getInterfaceId1())
                          .setIsdAs(ExamplePacket.SRC_IA)
                          .build())
                  .addInterfaces(
                      Daemon.PathInterface.newBuilder()
                          .setId(br.getInterfaceId2())
                          .setIsdAs(ExamplePacket.DST_IA)
                          .build())
                  .setRaw(rawPath)
                  .setExpiration(expiry)
                  .build();
          replyBuilder.addPaths(p0);
        }
      } else if (req.getSourceIsdAs() == req.getDestinationIsdAs()) {
        // localAS
        Daemon.Path p0 = Daemon.Path.newBuilder().setExpiration(expiry).build();
        replyBuilder.addPaths(p0);
      }
      responseObserver.onNext(replyBuilder.build());
      responseObserver.onCompleted();
    }

    @Override
    public void aS(Daemon.ASRequest req, StreamObserver<Daemon.ASResponse> responseObserver) {
      logger.debug("Got AS request from client: {}", req.getIsdAs());
      callCount.incrementAndGet();
      Daemon.ASResponse.Builder replyBuilder = Daemon.ASResponse.newBuilder();
      if (req.getIsdAs() == 0) { // 0 -> local AS
        replyBuilder.setCore(true);
        replyBuilder.setIsdAs(561850441793808L);
        replyBuilder.setMtu(1400);
      }
      responseObserver.onNext(replyBuilder.build());
      responseObserver.onCompleted();
    }

    @Override
    public void interfaces(
        Daemon.InterfacesRequest req, StreamObserver<Daemon.InterfacesResponse> responseObserver) {
      callCount.incrementAndGet();
      Daemon.InterfacesResponse.Builder replyBuilder = Daemon.InterfacesResponse.newBuilder();
      for (MockBorderRouter br : borderRouters) {
        String brAddress = br.getAddress1().toString().substring(1);
        Daemon.Interface.Builder ifBuilder = Daemon.Interface.newBuilder();
        ifBuilder.setAddress(Daemon.Underlay.newBuilder().setAddress(brAddress).build());
        // Interface IDs are currently not supported
        replyBuilder.putInterfaces(br.getInterfaceId1(), ifBuilder.build());
      }
      responseObserver.onNext(replyBuilder.build());
      responseObserver.onCompleted();
    }

    @Override
    public void portRange(Empty req, StreamObserver<Daemon.PortRangeResponse> responseObserver) {
      callCount.incrementAndGet();
      Daemon.PortRangeResponse.Builder replyBuilder = Daemon.PortRangeResponse.newBuilder();
      replyBuilder.setDispatchedPortStart(asInfo.getPortRange().getPortMin());
      replyBuilder.setDispatchedPortEnd(asInfo.getPortRange().getPortMax());
      responseObserver.onNext(replyBuilder.build());
      responseObserver.onCompleted();
    }
  }
}
