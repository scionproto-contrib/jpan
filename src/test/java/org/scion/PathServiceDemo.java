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
import java.util.List;
import java.util.Map;
import org.scion.proto.daemon.Daemon;

/**
 * Small demo that requests and prints information from the path service daemon. This arguments are
 * tailored to with with the "tiny" topology.
 */
public class PathServiceDemo {

  private final ScionService daemon;

  public static void main(String[] args) {
    try (Scion.CloseableService daemon = Scion.newServiceForAddress("127.0.0.12", 30255)) {
      PathServiceDemo demo = new PathServiceDemo(daemon);
      demo.testAsInfo();
      demo.testInterfaces();
      demo.testServices();
      demo.testPaths();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public PathServiceDemo(ScionService daemon) {
    this.daemon = daemon;
  }

  private void testAsInfo() {
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

  private void testInterfaces() {
    Map<Long, Daemon.Interface> interfaces = daemon.getInterfaces();
    System.out.println("Interfaces found: " + interfaces.size());
    for (Map.Entry<Long, Daemon.Interface> entry : interfaces.entrySet()) {
      System.out.print("    Interface: " + entry.getKey() + " -> " + entry.getValue().getAddress());
    }
  }

  private void testPaths() {
    long srcIA = ScionUtil.parseIA("1-ff00:0:110");
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");

    List<Daemon.Path> paths = daemon.getPathList(srcIA, dstIA);
    System.out.println("Paths found: " + paths.size());
    for (Daemon.Path path : paths) {
      System.out.println("Path:  exp=" + path.getExpiration() + "  mtu=" + path.getMtu());
      System.out.println("Path: interfaces = " + path.getInterface().getAddress().getAddress());
      System.out.println("Path: first hop(?) = " + path.getInterface().getAddress().getAddress());
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
    }
  }

  private void testServices() {
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
