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
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DaemonClient implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(DaemonClient.class.getName());

  private final DaemonServiceGrpc.DaemonServiceBlockingStub blockingStub;
  //  private final DaemonServiceGrpc.DaemonServiceStub asyncStub;
  //  private final DaemonServiceGrpc.DaemonServiceFutureStub futureStub;

  private final ManagedChannel channel;

  private DaemonClient(String daemonAddress) {
    // ManagedChannelBuilder.forAddress(daemonAddress, 1223);
    // ManagedChannelBuilder.forTarget().
    channel = Grpc.newChannelBuilder(daemonAddress, InsecureChannelCredentials.create()).build();
    blockingStub = DaemonServiceGrpc.newBlockingStub(channel);
  }

  private static DaemonClient create(String daemonHost, int daemonPort) {
    return create(daemonHost + ":" + daemonPort);
  }

  public static DaemonClient create(String daemonAddress) {
    return new DaemonClient(daemonAddress);
  }

  public static void main(String[] args) {
    String daemonHost = "127.0.0.12"; // from 110-topo
    int daemonPort = 30255; // from 110-topo

    testInterfaces(daemonHost, daemonPort);
    testServices(daemonHost, daemonPort);
    testPaths(daemonHost, daemonPort);
  }

  private static void testInterfaces(String daemonHost, int daemonPort) {

    Map<Long, Daemon.Interface> interfaces;
    try (DaemonClient client = DaemonClient.create(daemonHost, daemonPort)) {
      interfaces = client.getInterfaces();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    System.out.println("Interfaces found: " + interfaces.size());
    for (Map.Entry<Long, Daemon.Interface> entry : interfaces.entrySet()) {
      System.out.print("    Interface: " + entry.getKey() + " -> "  +  entry.getValue().getAddress());
    }
  }

  private static void testPaths(String daemonHost, int daemonPort) {
    long srcIA = Util.ParseIA("1-ff00:0:110");
    long dstIA = Util.ParseIA("1-ff00:0:112");

    List<Daemon.Path> paths;
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
        System.out.println(
                "    "
                        + i
                        + ": "
                        + segment.getId()
                        + " "
                        + segment.getIsdAs()
                        + "  "
                        + Util.toStringIA(segment.getIsdAs()));
      }
    }
  }
  private static void testServices(String daemonHost, int daemonPort) {
    Map<String, Daemon.ListService> services;
    try (DaemonClient client = DaemonClient.create(daemonHost, daemonPort)) {
      services = client.getServices();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    System.out.println("Services found: " + services.size());
    for (Map.Entry<String, Daemon.ListService> entry : services.entrySet()) {
      System.out.println("ListService: " + entry.getKey());
      for (Daemon.Service service: entry.getValue().getServicesList()) {
        System.out.println("    Service: " + service.getUri());
      }
    }
  }


  public Map<Long, Daemon.Interface> getInterfaces() {
    LOG.info("*** GetInterfaces ***");

    Daemon.InterfacesRequest request =
            Daemon.InterfacesRequest.newBuilder().build();  // TODO getDefaultInstance()?

    Daemon.InterfacesResponse response;
    try {
      response = blockingStub.interfaces(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }

    return response.getInterfacesMap();
  }

  public List<Daemon.Path> getPath(long srcIsdAs, long dstIsdAs) {
    LOG.info("*** GetPath: src={} dst={}", srcIsdAs, dstIsdAs);

    Daemon.PathsRequest request =
        Daemon.PathsRequest.newBuilder()
            .setSourceIsdAs(srcIsdAs)
            .setDestinationIsdAs(dstIsdAs)
            .build();

    Daemon.PathsResponse response;
    try {
      response = blockingStub.paths(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }

    return response.getPathsList();
  }

    public Map<String, Daemon.ListService> getServices() {
      LOG.info("*** GetServices ***");

      Daemon.ServicesRequest request =
              Daemon.ServicesRequest.newBuilder().build();  // TODO getDefaultInstance()?

      Daemon.ServicesResponse response;
      try {
        response = blockingStub.services(request);
      } catch (StatusRuntimeException e) {
        throw new ScionException(e);
      }

      return response.getServicesMap();
    }


  //  public long getIsdIs() {
  //    LOG.info("*** GetPath: src={} dst={}", srcIsdAs, dstIsdAs);
  //
  //    Daemon.ASRequest request =
  //            Daemon.ASRequest.newBuilder()
  //                    .s
  //                    .setSourceIsdAs(srcIsdAs)
  //                    .setDestinationIsdAs(dstIsdAs)
  //                    .build();
  //
  //    Daemon.ASResponse response;
  //    try {
  //      response = blockingStub.paths(request);
  //    } catch (StatusRuntimeException e) {
  //      throw new ScionException(e);
  //    }
  //
  //    return response.getIsdAs();
  //  }

  @Override
  public void close() throws IOException {
    try {
      channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }
}
