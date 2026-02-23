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

package org.scion.jpan;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.scion.jpan.demo.DemoConstants;
import org.scion.jpan.internal.paths.ControlServiceGrpc;
import org.scion.jpan.internal.paths.Segments;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.testutil.TestUtil;

/**
 * Small demo that requests and prints information from the path service daemon. The arguments are
 * tailored to be used with the "tiny" topology.
 */
public class ProtobufPathDemo {

  private final ScionService service;

  public static void main(String[] args) {
    if (true) {
      System.exit(0);
    }

    try (Scion.CloseableService daemon =
        Scion.newServiceWithDaemon(DemoConstants.daemon112_default)) {
      ProtobufPathDemo demo = new ProtobufPathDemo(daemon);
      demo.testAsInfo();
      demo.testInterfaces();
      demo.testServices();
      demo.testPathsDaemon(DemoConstants.ia112, DemoConstants.ia221);
      // demo.testPathsControlService(srcIA, dstIA);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public ProtobufPathDemo(ScionService service) {
    this.service = service;
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

  private void testPathsDaemon(long srcIA, long dstIA) {
    List<Daemon.Path> paths = service.getDaemonConnection().paths(srcIA, dstIA).getPathsList();
    System.out.println("Paths found: " + paths.size());
    for (Daemon.Path path : paths) {
      Instant exp = Instant.ofEpochSecond(path.getExpiration().getSeconds());
      System.out.println(
          "Path:  exp="
              + path.getExpiration().getSeconds()
              + " / "
              + exp
              + "  mtu="
              + path.getMtu());
      System.out.println("Path: first hop = " + path.getInterface().getAddress().getAddress());
      int i = 0;
      for (Daemon.PathInterface pathIf : path.getInterfacesList()) {
        System.out.println(
            "    pathIf: "
                + i++
                + ": "
                + pathIf.getId()
                + "  "
                + ScionUtil.toStringIA(pathIf.getIsdAs()));
      }
      int j = 0;
      for (int hop : path.getInternalHopsList()) {
        System.out.println("    hop: " + j++ + ": " + hop);
      }
      for (Daemon.LinkType linkType : path.getLinkTypeList()) {
        System.out.println(
            "    linkType: "
                + linkType.getNumber()
                + " "
                + linkType.getValueDescriptor().getName());
      }
      String lat =
          path.getLatencyList().stream()
              .map(d -> d.getNanos() / 1_000_000 + "ms, ")
              .collect(Collectors.joining());
      System.out.println("    latency:   " + lat);
      String bw =
          path.getBandwidthList().stream().map(d -> d + "B/s, ").collect(Collectors.joining());
      System.out.println("    bandwidth: " + bw);
      String geo =
          path.getGeoList().stream()
              .map(
                  g ->
                      "        Lat: "
                          + g.getLatitude()
                          + "; Long: "
                          + g.getLongitude()
                          + "; Addr: "
                          + g.getAddress()
                          + "\n")
              .collect(Collectors.joining());
      System.out.println("    geo: " + geo);
      String notes =
          path.getNotesList().stream()
              .map(s -> "        " + s + "\n")
              .collect(Collectors.joining());
      System.out.println("    notes: " + notes);
      System.out.println("    raw: " + TestUtil.toStringHex(path.getRaw().toByteArray()));
      System.out.println("    raw: " + TestUtil.toStringByte(path.getRaw().toByteArray()));
      System.out.println("    " + ScionUtil.toStringPath(path.getRaw().toByteArray()));
    }
  }

  private void testPathsControlService(long srcIA, long dstIA) {
    System.out.println("testPathsControlService()");
    ScionService csService =
        Scion.newServiceWithTopologyFile("topologies/tiny4/ASff00_0_112/topology.json");
    ControlServiceGrpc cs = PackageVisibilityHelper.getControlService(csService);
    List<PathMetadata> paths = Segments.getPaths(cs, csService.getLocalAS(), srcIA, dstIA, false);
    System.out.println("Paths found: " + paths.size());
    for (PathMetadata path : paths) {
      System.out.println("Path:  exp=" + path.getExpiration() + "  mtu=" + path.getMtu());
      System.out.println("Path: interfaces = " + path.getInterface().getAddress());
      System.out.println("Path: first hop = " + path.getInterface().getAddress());
      int i = 0;
      for (PathMetadata.PathInterface pathIf : path.getInterfacesList()) {
        System.out.println(
            "    pathIf: "
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
      System.out.println("    raw: " + TestUtil.toStringHex(path.getRawPath()));
    }
  }

  private void testServices() throws ScionException {
    Map<String, Daemon.ListService> services = getServices();
    System.out.println("Services found: " + services.size());
    for (Map.Entry<String, Daemon.ListService> entry : services.entrySet()) {
      System.out.println("ListService: " + entry.getKey());
      for (Daemon.Service daemonService : entry.getValue().getServicesList()) {
        System.out.println("    Service: " + daemonService.getUri());
      }
    }
  }

  private Daemon.ASResponse getASInfo() {
    Daemon.ASRequest request = Daemon.ASRequest.newBuilder().setIsdAs(0).build();
    Daemon.ASResponse response;
    try {
      response = service.getDaemonConnection().aS(request);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        throw new ScionRuntimeException("Could not connect to SCION daemon: " + e.getMessage(), e);
      }
      throw new ScionRuntimeException("Error while getting AS info: " + e.getMessage(), e);
    }
    return response;
  }

  private Map<Long, Daemon.Interface> getInterfaces() {
    Daemon.InterfacesRequest request = Daemon.InterfacesRequest.newBuilder().build();
    Daemon.InterfacesResponse response;
    try {
      response = service.getDaemonConnection().interfaces(request);
    } catch (StatusRuntimeException e) {
      throw new ScionRuntimeException(e);
    }
    return response.getInterfacesMap();
  }

  private Map<String, Daemon.ListService> getServices() throws ScionException {
    Daemon.ServicesRequest request = Daemon.ServicesRequest.newBuilder().build();
    Daemon.ServicesResponse response;
    try {
      response = service.getDaemonConnection().getGrpcStub().services(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }
    return response.getServicesMap();
  }
}
