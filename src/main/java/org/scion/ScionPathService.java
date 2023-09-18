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

public class ScionPathService implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ScionPathService.class.getName());

  // from 110-topo
  private static final String DAEMON_HOST =
      ScionUtil.getPropertyOrEnv("org.scion.daemon.host", "SCION_DAEMON_HOST", "127.0.0.12");
  private static final String DAEMON_PORT =
      ScionUtil.getPropertyOrEnv("org.scion.daemon.port", "SCION_DAEMON_PORT", "30255");

  private final DaemonServiceGrpc.DaemonServiceBlockingStub blockingStub;
  //  private final DaemonServiceGrpc.DaemonServiceStub asyncStub;
  //  private final DaemonServiceGrpc.DaemonServiceFutureStub futureStub;

  private final ManagedChannel channel;

  private ScionPathService(String daemonAddress) {
    // ManagedChannelBuilder.forAddress(daemonAddress, 1223);
    // ManagedChannelBuilder.forTarget().
    channel = Grpc.newChannelBuilder(daemonAddress, InsecureChannelCredentials.create()).build();
    blockingStub = DaemonServiceGrpc.newBlockingStub(channel);
  }

  private static ScionPathService create(String daemonHost, int daemonPort) {
    return create(daemonHost + ":" + daemonPort);
  }

  public static ScionPathService create(String daemonAddress) {
    return new ScionPathService(daemonAddress);
  }

  public static ScionPathService create() {
    return create(DAEMON_HOST + ":" + DAEMON_PORT);
  }

  public static void main(String[] args) {
    try (ScionPathService client = ScionPathService.create()) {
      client.testAsInfo();
      client.testInterfaces();
      client.testServices();
      client.testPaths();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void testAsInfo() {
    Daemon.ASResponse asInfo = getASInfo();
    System.out.println(
        "ASInfo found: "
            + asInfo.getIsdAs()
            + " "
            + ScionUtil.toStringIA(asInfo.getIsdAs())
            + "  core="
            + asInfo.getCore()
            + "  mtu="
            + asInfo.getMtu());
  }

  private void testInterfaces() {
    Map<Long, Daemon.Interface> interfaces = getInterfaces();
    System.out.println("Interfaces found: " + interfaces.size());
    for (Map.Entry<Long, Daemon.Interface> entry : interfaces.entrySet()) {
      System.out.print("    Interface: " + entry.getKey() + " -> " + entry.getValue().getAddress());
    }
  }

  private void testPaths() {
    long srcIA = ScionUtil.ParseIA("1-ff00:0:110");
    long dstIA = ScionUtil.ParseIA("1-ff00:0:112");

    List<Daemon.Path> paths = getPathList(srcIA, dstIA);
    System.out.println("Paths found: " + paths.size());
    for (Daemon.Path path : paths) {
      System.out.println("Path:  exp=" + path.getExpiration() + "  mtu=" + path.getMtu());
      System.out.println("Path: interfaces = " + path.getInterface().getAddress().getAddress());
      System.out.println("Path: first hop(?) = " + path.getInterface().getAddress().getAddress());
      int i = 0;
      for (Daemon.PathInterface pathIf : path.getInterfacesList()) {
        System.out.println("    pathIf: "
                        + i
                        + ": "
                        + pathIf.getId()
                        + " "
                        + pathIf.getIsdAs()
                        + "  "
                        + ScionUtil.toStringIA(pathIf.getIsdAs()));
      }
      for (int hop : path.getInternalHopsList()) {
        System.out.println("    hop: " + i + ": " + hop);
      }
    }
  }

  private void testServices() {
    Map<String, Daemon.ListService> services = getServices();
    System.out.println("Services found: " + services.size());
    for (Map.Entry<String, Daemon.ListService> entry : services.entrySet()) {
      System.out.println("ListService: " + entry.getKey());
      for (Daemon.Service service : entry.getValue().getServicesList()) {
        System.out.println("    Service: " + service.getUri());
      }
    }
  }

  Daemon.ASResponse getASInfo() {
    LOG.info("*** GetASInfo ***");

    Daemon.ASRequest request =
        Daemon.ASRequest.newBuilder().setIsdAs(0).build(); // TODO getDefaultInstance()?

    Daemon.ASResponse response;
    try {
      response = blockingStub.aS(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }

    return response;
  }

  Map<Long, Daemon.Interface> getInterfaces() {
    LOG.info("*** GetInterfaces ***");

    Daemon.InterfacesRequest request =
        Daemon.InterfacesRequest.newBuilder().build(); // TODO getDefaultInstance()?

    Daemon.InterfacesResponse response;
    try {
      response = blockingStub.interfaces(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }

    return response.getInterfacesMap();
  }

  // TODO do not expose proto types
  List<Daemon.Path> getPathList(long srcIsdAs, long dstIsdAs) {
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

  /**
   * Request and return a path from srcIsdAs to dstIsdAs.
   * @param srcIsdAs Source ISD + AS
   * @param dstIsdAs Destination ISD + AS
   * @return The first path is returned by the path service.
   */
  public ScionPath getPath(long srcIsdAs, long dstIsdAs) {
    List<Daemon.Path> paths = getPathList(srcIsdAs, dstIsdAs);
    if (paths.isEmpty()) {
      throw new ScionException("No path found from " + ScionUtil.toStringIA(srcIsdAs) + " to " + ScionUtil.toStringIA(dstIsdAs));
    }
    return new ScionPath(paths.get(0));
  }

  Map<String, Daemon.ListService> getServices() {
    LOG.info("*** GetServices ***");

    Daemon.ServicesRequest request =
        Daemon.ServicesRequest.newBuilder().build(); // TODO getDefaultInstance()?

    Daemon.ServicesResponse response;
    try {
      response = blockingStub.services(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }

    return response.getServicesMap();
  }

  public long getLocalIsdAs() {
    return getASInfo().getIsdAs();
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
