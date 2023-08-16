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

package org.scion;

import io.grpc.*;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DaemonClient implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(DaemonClient.class.getName());

  private final DaemonServiceGrpc.DaemonServiceBlockingStub blockingStub;
  //  private final DaemonServiceGrpc.DaemonServiceStub asyncStub;
  //  private final DaemonServiceGrpc.DaemonServiceFutureStub futureStub;

  private final ManagedChannel channel;

  private static DaemonClient create(String daemonHost, int daemonPort) {
    return create(daemonHost + ":" + daemonPort);
  }

  public static DaemonClient create(String daemonAddress) {
    return new DaemonClient(daemonAddress);
  }

  private DaemonClient(String daemonAddress) {
    //ManagedChannelBuilder.forAddress(daemonAddress, 1223);
    //ManagedChannelBuilder.forTarget().
    channel = Grpc.newChannelBuilder(daemonAddress, InsecureChannelCredentials.create()).build();
    blockingStub = DaemonServiceGrpc.newBlockingStub(channel);
  }

  /** Construct client for accessing RouteGuide server using the existing channel. */
  private DaemonClient(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = DaemonServiceGrpc.newBlockingStub(channel);
    //    asyncStub = DaemonServiceGrpc.newStub(channel);
    //    futureStub = DaemonServiceGrpc.newFutureStub(channel);
  }

  /** Blocking unary call example. */
  public List<Daemon.Path> getPath(long srcIsdAs, long dstIsdAs) {
    info("*** GetPath: src={} dst={}", srcIsdAs, dstIsdAs);

    Daemon.PathsRequest request =
        Daemon.PathsRequest.newBuilder()
            .setSourceIsdAs(srcIsdAs)
            .setDestinationIsdAs(dstIsdAs)
            .build();

    Daemon.PathsResponse response;
    try {
      response = blockingStub.paths(request);
    } catch (StatusRuntimeException e) {
      warning("RPC failed: {}", e.getStatus());
      throw new ScionException(e);
      //e.printStackTrace();
      //return Collections.emptyList();
    }

    return response.getPathsList();
  }

  /** Issues several different requests and then exits. */
  public static void main(String[] args) throws InterruptedException {
    String daemonHost = "127.0.0.12"; // from 110-topo
    int daemonPort = 30255; // from 110-topo
    String daemonAddr = "127.0.0.12:30255"; // from 110-topo
//    String target = "localhost:8980";
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [target]");
        System.err.println();
        System.err.println("  target  The server to connect to. Defaults to " + daemonAddr);
        System.exit(1);
      }
      daemonAddr = args[0];
    }


//    List<Daemon.Path> paths;
    long srcIA = Util.ParseIA("1-ff00:0:110");
    long dstIA = Util.ParseIA("1-ff00:0:112");
//    try (DaemonClient client = DaemonClient.create(daemonAddr)) {
//      client.getPath(srcIA, dstIA);
//      paths = client.getPath(0, 0);
//    } catch (IOException e) {
//      throw new RuntimeException(e);
//    }




    List<Daemon.Path> paths;
//    // TODO credentials?
//    ManagedChannel channel =
//        Grpc.newChannelBuilder(daemonAddr, InsecureChannelCredentials.create()).build();
//    try {
//      DaemonClient client = new DaemonClient(channel);
//      paths = client.getPath(srcIA, dstIA);
//    } finally {
//      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
//    }


    try (DaemonClient client = DaemonClient.create(daemonHost, daemonPort)) {
      paths = client.getPath(srcIA, dstIA);
    } catch (IOException e) {
        throw new RuntimeException(e);
    }

      System.out.println("Paths found: " + paths.size());
    for (Daemon.Path path : paths) {
      System.out.println("Path: first hop = " + path.getInterface().getAddress().getAddress());
      int i = 0;
      for (Daemon.PathInterface segment : path.getInterfacesList()) {
        System.out.println("    " + i + ": " + segment.getId() + " " + segment.getIsdAs() + "  " + Util.toStringIA(segment.getIsdAs()));
      }
    }
  }

  private void info(String msg, Object... params) {
    logger.info(msg, params);
  }

  private void warning(String msg, Object... params) {
    logger.warn(msg, params);
  }

  @Override
  public void close() throws IOException {
    try {
      channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }
}
