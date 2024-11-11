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

package org.scion.jpan.demo;

import java.io.*;
import java.net.*;
import java.util.List;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockScmpHandler;

/**
 * This demo mimics the "scion ping" command available in scionproto (<a
 * href="https://github.com/scionproto/scion">...</a>). This demo also demonstrates different ways
 * of connecting to a network: <br>
 * - JUNIT_MOCK shows how to use the mock network in this library (for JUnit tests) <br>
 * - SCION_PROTO shows how to connect to a local topology from the scionproto go implementation such
 * as "tiny". Note that the constants for "minimal" differ somewhat from the scionproto topology.
 * <br>
 * - PRODUCTION shows different ways how to connect to the production network. Note: While the
 * production network uses the dispatcher, the demo needs to use port 30041.
 *
 * <p>Commented out lines show alternative ways to connect or alternative destinations.
 */
public class ShowpathsDemo {

  public static boolean PRINT = true;
  public static Network NETWORK = Network.PRODUCTION;

  public enum Network {
    JUNIT_MOCK_V4, // SCION Java JUnit mock network with local AS = 1-ff00:0:112
    JUNIT_MOCK_V6, // SCION Java JUnit mock network with local AS = 1-ff00:0:112
    SCION_PROTO, // Try to connect to scionproto networks, e.g. "tiny"
    PRODUCTION // production network
  }

  public static void init(boolean print, ShowpathsDemo.Network network) {
    PRINT = print;
    NETWORK = network;
  }

  public static void main(String[] args) throws IOException {
    try {
      run();
    } finally {
      Scion.closeDefault();
    }
  }

  public static int run() throws IOException {
    switch (NETWORK) {
      case JUNIT_MOCK_V4:
        {
          DemoTopology.configureMockV4();
          InetAddress remote = MockScmpHandler.getAddress().getAddress();
          MockDNS.install("1-ff00:0:112", "localhost", remote.toString());
          ShowpathsDemo demo = new ShowpathsDemo();
          int n = demo.runDemo(DemoConstants.ia110);
          DemoTopology.shutDown();
          return n;
        }
      case JUNIT_MOCK_V6:
        {
          DemoTopology.configureMockV6();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          int n = runDemo(DemoConstants.ia110);
          DemoTopology.shutDown();
          return n;
        }
      case SCION_PROTO:
        {
          // System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
          // "topologies/minimal/ASff00_0_1111/topology.json");
          System.setProperty(Constants.PROPERTY_DAEMON, DemoConstants.daemon1111_minimal);
          runDemo(DemoConstants.ia211);
          break;
        }
      case PRODUCTION:
        {
          runDemo(DemoConstants.iaAnapayaHK);
          // runDemo(DemoConstants.iaOVGU);
          break;
        }
    }
    return -1;
  }

  private static int runDemo(long destinationIA) throws IOException {
    ScionService service = Scion.defaultService();
    // dummy address
    InetSocketAddress destinationAddress =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    List<Path> paths = service.getPaths(destinationIA, destinationAddress);
    if (paths.isEmpty()) {
      String src = ScionUtil.toStringIA(service.getLocalIsdAs());
      String dst = ScionUtil.toStringIA(destinationIA);
      throw new IOException("No path found from " + src + " to " + dst);
    }

    println("Available paths to " + ScionUtil.toStringIA(destinationIA));

    int id = 0;
    for (Path path : paths) {
      String localIP;
      try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
        channel.connect(path);
        localIP = channel.getLocalAddress().getAddress().getHostAddress();
      }
      PathMetadata meta = path.getMetadata();
      String sb =
          "["
              + id++
              + "] Hops: "
              + ScionUtil.toStringPath(meta)
              + " MTU: "
              + meta.getMtu()
              + " NextHop: "
              + path.getFirstHopAddress().getAddress()
              + " LocalIP: "
              + localIP;
      println(sb);
    }
    return paths.size();
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
