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

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.scion.ScionConstants;
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockDaemon implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(MockDaemon.class.getName());

  public static final InetSocketAddress DEFAULT_ADDRESS =
          new InetSocketAddress("127.0.0.15", 30255);
  public static final String DEFAULT_IP = "127.0.0.15";
  public static final int DEFAULT_PORT = 30255;

  private final InetSocketAddress address;
  private Server server;
  private final InetSocketAddress borderRouter;

  private static final byte[] PATH_RAW_TINY_110_112 = {
          0x00, 0x00, 0x20, 0x00, 0x01, 0x00, (byte) 0xb4, (byte) 0xab,
          0x65, 0x14, 0x08, (byte) 0xde, 0x00, 0x3f, 0x00, 0x00,
          0x00, 0x02, 0x66, 0x62, 0x3e, (byte) 0xba, 0x31, (byte) 0xc6,
          0x00, 0x3f, 0x00, 0x01, 0x00, 0x00, 0x51, (byte) 0xc1,
          (byte) 0xfd, (byte) 0xed, 0x27, 0x60, };

  private static void setEnvironment() {
    System.setProperty(ScionConstants.PROPERTY_DAEMON_HOST, DEFAULT_IP);
    System.setProperty(ScionConstants.PROPERTY_DAEMON_PORT, "" + DEFAULT_PORT);
  }
  public static MockDaemon create() {
    setEnvironment();
    return new MockDaemon(DEFAULT_ADDRESS);
  }

  public static MockDaemon createForBorderRouter(InetSocketAddress borderRouter) {
    setEnvironment();
    return new MockDaemon(DEFAULT_ADDRESS, borderRouter);
  }

  private MockDaemon(InetSocketAddress address) {
    this.address = address;
    this.borderRouter = new InetSocketAddress("127.0.0.10", 31004);
  }

  private MockDaemon(InetSocketAddress address, InetSocketAddress borderRouter) {
    this.address = address;
    this.borderRouter = borderRouter;
  }

  public MockDaemon start() throws IOException {
    String br = borderRouter.toString().substring(1);
    int port = address.getPort();
    server =
        Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .addService(new MockDaemon.DaemonImpl(br))
            .build()
            .start();
    logger.info("Server started, listening on " + address);

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
      logger.info("Daemon server shut down (or not?)");
    } catch (InterruptedException ie) {
      // (Re-)Cancel if current thread also interrupted
      server.shutdownNow();
      // Preserve interrupt status
      Thread.currentThread().interrupt();
    }
  }

  static class DaemonImpl extends DaemonServiceGrpc.DaemonServiceImplBase {
    final String borderRouter;

    DaemonImpl(String borderRouter) {
      this.borderRouter = borderRouter;
    }

    @Override
    public void paths(
        Daemon.PathsRequest req, StreamObserver<Daemon.PathsResponse> responseObserver) {
      logger.info(
          "Got request from client: " + req.getSourceIsdAs() + " / " + req.getDestinationIsdAs());
      ByteString rawPath = ByteString.copyFrom(PATH_RAW_TINY_110_112);
      Daemon.PathsResponse.Builder replyBuilder = Daemon.PathsResponse.newBuilder();
      if (req.getSourceIsdAs() == 561850441793808L
          && req.getDestinationIsdAs() == 561850441793810L) {
        Daemon.Path p0 =
            Daemon.Path.newBuilder()
                .setInterface(
                    Daemon.Interface.newBuilder()
                        .setAddress(Daemon.Underlay.newBuilder().setAddress(borderRouter).build())
                        .build())
                .addInterfaces(
                    Daemon.PathInterface.newBuilder().setId(2).setIsdAs(561850441793808L).build())
                .addInterfaces(
                    Daemon.PathInterface.newBuilder().setId(1).setIsdAs(561850441793810L).build())
                .setRaw(rawPath)
                .build();
        replyBuilder.addPaths(p0);
      }
      responseObserver.onNext(replyBuilder.build());
      responseObserver.onCompleted();
    }

    @Override
    public void aS(Daemon.ASRequest req, StreamObserver<Daemon.ASResponse> responseObserver) {
      logger.info("Got AS request from client: " + req.getIsdAs());
      Daemon.ASResponse.Builder replyBuilder = Daemon.ASResponse.newBuilder();
      if (req.getIsdAs() == 0) { // 0 -> local AS
        replyBuilder.setCore(true);
        replyBuilder.setIsdAs(561850441793808L);
        replyBuilder.setMtu(1400);
      }
      responseObserver.onNext(replyBuilder.build());
      responseObserver.onCompleted();
    }
  }
}
