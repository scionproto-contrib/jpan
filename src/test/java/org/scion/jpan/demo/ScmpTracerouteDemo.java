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

  public static boolean PRINT = true;
  public static Network NETWORK = Network.PRODUCTION;

  public enum Network {
    JUNIT_MOCK_V4, // SCION Java JUnit mock network with local AS = 1-ff00:0:112
    JUNIT_MOCK_V6, // SCION Java JUnit mock network with local AS = 1-ff00:0:112
    SCION_PROTO, // Try to connect to scionproto networks, e.g. "tiny"
    PRODUCTION // production network
  }

  public static void init(boolean print, ScmpTracerouteDemo.Network network) {
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
          System.setProperty(Constants.PROPERTY_SHIM, "false"); // disable SHIM
          DemoTopology.configureMockV4();
          InetAddress remote = MockScmpHandler.getAddress().getAddress();
          MockDNS.install("1-ff00:0:112", "localhost", remote.toString());
          int n = runDemo(DemoConstants.ia112);
          DemoTopology.shutDown();
          return n;
        }
      case JUNIT_MOCK_V6:
        {
          System.setProperty(Constants.PROPERTY_SHIM, "false"); // disable SHIM
          DemoTopology.configureMockV6();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          MockScmpHandler.stop();
          MockScmpHandler.start("[::1]"); // We need the SCMP handler running on IPv6
          int n = runDemo(DemoConstants.ia112);
          DemoTopology.shutDown();
          MockScmpHandler.stop();
          return n;
        }
      case SCION_PROTO:
        {
          // Alternative: Use topo file i.o. daemon
          // System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
          //   "topologies/minimal/ASff00_0_1111/topology.json");
          System.setProperty(Constants.PROPERTY_DAEMON, DemoConstants.daemon1111_minimal);
          runDemo(DemoConstants.ia211);
          runDemo(DemoConstants.ia111);
          return runDemo(DemoConstants.ia1111);
        }
      case PRODUCTION:
        {
          runDemo(ScionUtil.parseIA("66-2:0:10")); // singapore
          // runDemo(DemoConstants.iaAnapayaHK);
          // runDemo(DemoConstants.iaOVGU);
        }
      default:
        return -1;
    }
  }

  private static int runDemo(long destinationIA) throws IOException {
    ScionService service = Scion.defaultService();
    // Dummy address. The traceroute will contact the control service IP instead.
    InetSocketAddress destinationAddress =
        new InetSocketAddress(Inet4Address.getByAddress(new byte[] {1, 2, 3, 4}), 12345);
    List<Path> paths = service.getPaths(destinationIA, destinationAddress);
    if (paths.isEmpty()) {
      String src = ScionUtil.toStringIA(service.getLocalIsdAs());
      String dst = ScionUtil.toStringIA(destinationIA);
      throw new IOException("No path found from " + src + " to " + dst);
    }
    Path path = paths.get(0);

    String localAddress;
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(path);
      // We determine the address separately because SCMP will always have 0.0.0.0 as local address
      localAddress = channel.getLocalAddress().getAddress().getHostAddress();
    }

    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      println("Listening on port " + sender.getLocalAddress().getPort() + " ...");
      println("Resolved local address: ");
      println("  " + localAddress);
      printPath(path);

      List<Scmp.TracerouteMessage> results = sender.sendTracerouteRequest(path);
      int n = 0;
      for (Scmp.TracerouteMessage msg : results) {
        if (!msg.isTimedOut()) {
          n++;
        }
        String millis = String.format("%.4f", msg.getNanoSeconds() / (double) 1_000_000);
        String out = "" + msg.getSequenceNumber();
        out += " " + ScionUtil.toStringIA(msg.getIsdAs());
        out += " " + msg.getPath().getRemoteAddress().getHostAddress();
        out += " IfID=" + msg.getIfID();
        out += " " + millis + "ms";
        println(out);
      }
      return n;
    }
  }

  private static void printPath(Path path) {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder();
    // sb.append("Actual local address:").append(nl);
    // sb.append("  ").append(channel.getLocalAddress().getAddress().getHostAddress()).append(nl);
    sb.append("Using path:").append(nl);
    sb.append("  Hops: ").append(ScionUtil.toStringPath(path.getMetadata()));
    sb.append(" MTU: ").append(path.getMetadata().getMtu());
    sb.append(" NextHop: ").append(path.getMetadata().getInterface().getAddress()).append(nl);
    println(sb.toString());
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
