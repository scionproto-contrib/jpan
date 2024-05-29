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

/**
 * This demo mimics the "scion traceroute" command available in scionproto (<a
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
public class ScmpTracerouteDemo {

  public static final boolean PRINT = true;
  private final int localPort;

  private enum Network {
    JUNIT_MOCK, // SCION Java JUnit mock network with local AS = 1-ff00:0:112
    SCION_PROTO, // Try to connect to scionproto networks, e.g. "tiny"
    PRODUCTION // production network
  }

  public ScmpTracerouteDemo() {
    this(12345); // Any port is fine unless we connect to a dispatcher network
  }

  public ScmpTracerouteDemo(int localPort) {
    this.localPort = localPort;
  }

  private static final Network network = Network.PRODUCTION;

  public static void main(String[] args) throws IOException {
    switch (network) {
      case JUNIT_MOCK:
        {
          DemoTopology.configureMock();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          ScmpTracerouteDemo demo = new ScmpTracerouteDemo();
          demo.runDemo(DemoConstants.ia112);
          DemoTopology.shutDown();
          break;
        }
      case SCION_PROTO:
        {
          // System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
          //   "topologies/minimal/ASff00_0_1111/topology.json");
          System.setProperty(Constants.PROPERTY_DAEMON, DemoConstants.daemon1111_minimal);
          ScmpTracerouteDemo demo = new ScmpTracerouteDemo();
          demo.runDemo(DemoConstants.ia211);
          break;
        }
      case PRODUCTION:
        {
          // Local port must be 30041 for networks that expect a dispatcher
          ScmpTracerouteDemo demo = new ScmpTracerouteDemo(Constants.SCMP_PORT);
          demo.runDemo(DemoConstants.iaAnapayaHK);
          // demo.runDemo(DemoConstants.iaOVGU);
          break;
        }
    }
  }

  private void runDemo(long destinationIA) throws IOException {
    ScionService service = Scion.defaultService();
    // Dummy address. The traceroute will contact the control service IP instead.
    InetSocketAddress destinationAddress =
        new InetSocketAddress(Inet4Address.getByAddress(new byte[] {1, 2, 3, 4}), 12345);
    List<RequestPath> paths = service.getPaths(destinationIA, destinationAddress);
    if (paths.isEmpty()) {
      String src = ScionUtil.toStringIA(service.getLocalIsdAs());
      String dst = ScionUtil.toStringIA(destinationIA);
      throw new IOException("No path found from " + src + " to " + dst);
    }
    RequestPath path = paths.get(0);

    println("Listening on port " + localPort + " ...");
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(path);
      println("Resolved local address: ");
      println("  " + channel.getLocalAddress().getAddress().getHostAddress());
    }

    printPath(path);

    try (ScmpChannel scmpChannel = Scmp.createChannel(localPort)) {
      List<Scmp.TracerouteMessage> results = scmpChannel.sendTracerouteRequest(path);
      for (Scmp.TracerouteMessage msg : results) {
        String millis = String.format("%.4f", msg.getNanoSeconds() / (double) 1_000_000);
        String out = "" + msg.getSequenceNumber();
        out += " " + ScionUtil.toStringIA(msg.getIsdAs());
        out += " " + msg.getPath().getRemoteAddress().getHostAddress();
        out += " IfID=" + msg.getIfID();
        out += " " + millis + "ms";
        println(out);
      }
    }
  }

  private void printPath(RequestPath path) {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder();
    // sb.append("Actual local address:").append(nl);
    // sb.append("  ").append(channel.getLocalAddress().getAddress().getHostAddress()).append(nl);
    sb.append("Using path:").append(nl);
    sb.append("  Hops: ").append(ScionUtil.toStringPath(path));
    sb.append(" MTU: ").append(path.getMtu());
    sb.append(" NextHop: ").append(path.getInterface().getAddress()).append(nl);
    println(sb.toString());
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
