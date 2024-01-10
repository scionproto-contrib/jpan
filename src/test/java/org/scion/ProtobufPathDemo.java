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

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.scion.demo.util.ToStringUtil;
import org.scion.proto.daemon.Daemon;

/**
 * Small demo that requests and prints information from the path service daemon. The arguments are
 * tailored to be used with the "tiny" topology.
 */
public class ProtobufPathDemo {

  private final ScionService daemon;

  public static void main(String[] args) {
    String daemon110_tiny = "127.0.0.12:30255";
    String daemon111_tiny = "127.0.0.19:30255";
    String daemon110_minimal = "127.0.0.29:30255";
    String daemon111_minimal = "127.0.0.37:30255";
    String daemon1111_minimal = "127.0.0.43:30255";
    String daemon210_minimal = "127.0.0.92:30255";
    long ia110 = ScionUtil.parseIA("1-ff00:0:110");
    long ia111 = ScionUtil.parseIA("1-ff00:0:111");
    long ia1111 = ScionUtil.parseIA("1-ff00:0:1111");
    long ia1112 = ScionUtil.parseIA("1-ff00:0:1112");
    long ia112 = ScionUtil.parseIA("1-ff00:0:112");
    long ia1121 = ScionUtil.parseIA("1-ff00:0:1121");
    long ia120 = ScionUtil.parseIA("1-ff00:0:120");
    long ia121 = ScionUtil.parseIA("1-ff00:0:121");
    long ia210 = ScionUtil.parseIA("2-ff00:0:210");
    long ia211 = ScionUtil.parseIA("2-ff00:0:211");

    try (Scion.CloseableService daemon = Scion.newServiceWithDaemon(daemon1111_minimal)) {
      ProtobufPathDemo demo = new ProtobufPathDemo(daemon);
      demo.testAsInfo();
      demo.testInterfaces();
      demo.testServices();
      demo.testPathsDaemon(ia1111, ia1121);
      // demo.testPathsControlService(srcIA, dstIA);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public ProtobufPathDemo(ScionService daemon) {
    this.daemon = daemon;
  }

  private void testAsInfo() throws ScionException {
    Daemon.ASResponse asInfo = daemon.getASInfo();
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

  private void testInterfaces() throws ScionException {
    Map<Long, Daemon.Interface> interfaces = daemon.getInterfaces();
    System.out.println("Interfaces found: " + interfaces.size());
    for (Map.Entry<Long, Daemon.Interface> entry : interfaces.entrySet()) {
      System.out.print("    Interface: " + entry.getKey() + " -> " + entry.getValue().getAddress());
    }
  }

  private void testPathsDaemon(long srcIA, long dstIA) throws ScionException {
    List<Daemon.Path> paths = daemon.getPathListDaemon(srcIA, dstIA);
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
                + " "
                + pathIf.getIsdAs()
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
      System.out.println("    raw: " + ToStringUtil.toStringHex(path.getRaw().toByteArray()));
      System.out.println("    raw: " + ToStringUtil.toStringByte(path.getRaw().toByteArray()));
    }
  }

  private void testPathsControlService(long srcIA, long dstIA) throws ScionException {
    System.out.println("testPathsControlService()");
    String addr110 = "127.0.0.11:31000";
    String addr111 = "127.0.0.18:31006";
    // ScionService csSercice = Scion.newServiceWithBootstrapServerIP(addr111);
    ScionService csSercice =
        Scion.newServiceWithTopologyFile("topologies/scionproto-tiny-111.json");
    List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(csSercice, srcIA, dstIA);
    System.out.println("Paths found: " + paths.size());
    for (Daemon.Path path : paths) {
      System.out.println("Path:  exp=" + path.getExpiration() + "  mtu=" + path.getMtu());
      System.out.println("Path: interfaces = " + path.getInterface().getAddress().getAddress());
      System.out.println("Path: first hop = " + path.getInterface().getAddress().getAddress());
      int i = 0;
      for (Daemon.PathInterface pathIf : path.getInterfacesList()) {
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
      System.out.println("    raw: " + ToStringUtil.toStringHex(path.getRaw().toByteArray()));
    }
  }

  private void testServices() throws ScionException {
    Map<String, Daemon.ListService> services = daemon.getServices();
    System.out.println("Services found: " + services.size());
    for (Map.Entry<String, Daemon.ListService> entry : services.entrySet()) {
      System.out.println("ListService: " + entry.getKey());
      for (Daemon.Service service : entry.getValue().getServicesList()) {
        System.out.println("    Service: " + service.getUri());
      }
    }
  }
}
