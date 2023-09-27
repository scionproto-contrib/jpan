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
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockDaemon implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(MockDaemon.class.getName());
  private final InetSocketAddress address;
  private Server server;
  private final InetSocketAddress borderRouter;

  private static final byte[] PATH_RAW_TINY_110_112 = {
0x0, 0x0, 0x20, 0x0, 0x1, 0x0, (byte) 0xb4, (byte) 0xab,
          0x65, 0x14, 0x8, (byte) 0xde, 0x0, 0x3f, 0x0, 0x0,
          0x0, 0x2, 0x66, 0x62, 0x3e, (byte) 0xba, 0x31, (byte) 0xc6,
          0x0, 0x3f, 0x0, 0x1, 0x0, 0x0, 0x51, (byte) 0xc1,
          (byte) 0xfd, (byte) 0xed, 0x27, 0x60, };
  public MockDaemon(InetSocketAddress address) {
    this.address = address;
    this.borderRouter = new InetSocketAddress("127.0.0.10", 31004);
  }

  public MockDaemon(InetSocketAddress address, InetSocketAddress borderRouter) {
    this.address = address;
    this.borderRouter = borderRouter;
  }

  public static MockDaemon create(InetSocketAddress address) {
    return new MockDaemon(address);
  }

  public static MockDaemon create(InetSocketAddress address, InetSocketAddress borderRouter) {
    return new MockDaemon(address, borderRouter);
  }

  public MockDaemon start() throws IOException {
    String br = "127.0.0.10:31004";
    br = borderRouter.toString().substring(1);
    System.out.println("BR++++ " + br);
    int port = address.getPort();
    //        server = NettyServerBuilder.forAddress(address).addService(new
    // DaemonImpl(br)).build().start();
    // server = ServerBuilder.forPort(port).addService(new DaemonImpl()).build().start();
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
                  System.err.println("Shutting down daemon server");
                  try {
                    server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                  }
                }));
    return this;
  }

  @Override
  public void close() throws IOException {
    try {
      logger.warn("Shutting down daemon");
      server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
      logger.warn("Daemon shut down");
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  static class DaemonImpl extends DaemonServiceGrpc.DaemonServiceImplBase {
    String borderRouter = "127.0.0.10:31004";

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
