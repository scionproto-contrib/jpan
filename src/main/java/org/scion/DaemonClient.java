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
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DaemonClient implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(DaemonClient.class.getName());

  private final DaemonServiceGrpc.DaemonServiceBlockingStub blockingStub;
  //  private final DaemonServiceGrpc.DaemonServiceStub asyncStub;
  //  private final DaemonServiceGrpc.DaemonServiceFutureStub futureStub;

  private final ManagedChannel channel;

  public static DaemonClient create(String daemonAddress) {
    return new DaemonClient(daemonAddress);
  }

  private DaemonClient(String daemonAddress) {
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
    info("*** GetPath: src={0} lon={1}", srcIsdAs, dstIsdAs);

    Daemon.PathsRequest request =
        Daemon.PathsRequest.newBuilder()
            .setSourceIsdAs(srcIsdAs)
            .setDestinationIsdAs(dstIsdAs)
            .build();

    Daemon.PathsResponse response;
    try {
      response = blockingStub.paths(request);
    } catch (StatusRuntimeException e) {
      warning("RPC failed: {0}", e.getStatus());
      return Collections.emptyList();
    }

    return response.getPathsList();
  }

  /** Issues several different requests and then exits. */
  public static void main(String[] args) throws InterruptedException {
    String target = "localhost:8980";
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [target]");
        System.err.println();
        System.err.println("  target  The server to connect to. Defaults to " + target);
        System.exit(1);
      }
      target = args[0];
    }

    // TODO credentials?
    ManagedChannel channel =
        Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
    List<Daemon.Path> paths;
    try {
      DaemonClient client = new DaemonClient(channel);
      // Looking for a valid feature
      client.getPath(409146138, -746188906);

      // Feature missing.
      paths = client.getPath(0, 0);

      // Looking for features between 40, -75 and 42, -73.
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    System.out.println("Paths found: " + paths.size());
    for (Daemon.Path path : paths) {
      System.out.println("Path: first hop = " + path.getInterface().getAddress().getAddress());
      int i = 0;
      for (Daemon.PathInterface segment : path.getInterfacesList()) {
        System.out.println("    " + i + ": " + segment.getId() + " " + segment.getIsdAs());
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
