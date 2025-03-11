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
import java.time.Instant;
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
  public static Network NETWORK = Network.SCION_PROTO;
  public static boolean EXTENDED = true;

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
          System.setProperty(Constants.PROPERTY_SHIM, "false"); // disable SHIM
          DemoTopology.configureMockV4();
          InetAddress remote = MockScmpHandler.getAddress().getAddress();
          MockDNS.install("1-ff00:0:112", "localhost", remote.toString());
          int n = runDemo(DemoConstants.ia110);
          DemoTopology.shutDown();
          return n;
        }
      case JUNIT_MOCK_V6:
        {
          System.setProperty(Constants.PROPERTY_SHIM, "false"); // disable SHIM
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
          System.setProperty(Constants.PROPERTY_DAEMON, DemoConstants.daemon110_default);
          runDemo(DemoConstants.ia220);
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
      String header = "[" + id++ + "] Hops: " + ScionUtil.toStringPath(meta);
      if (EXTENDED) {
        println(header);
        printExtended(path);
      } else {
        String compact =
            " MTU: "
                + meta.getMtu()
                + " NextHop: "
                + path.getFirstHopAddress().getHostString()
                + ":"
                + path.getFirstHopAddress().getPort()
                + " LocalIP: "
                + localIP;
        println(header + compact);
      }
    }
    return paths.size();
  }

  private static void printExtended(Path path) {
    StringBuilder sb = new StringBuilder();
    String NL = System.lineSeparator();

    PathMetadata meta = path.getMetadata();
    // for (Object o : meta.get)

    sb.append("    MTU: ").append(meta.getMtu()).append(NL);
    sb.append("    NextHop: ").append(meta.getFirstHopAddress().getHostString()).append(NL);
    Instant exp = Instant.ofEpochSecond(meta.getExpiration());
    sb.append("    Expires: ").append(exp).append(NL);
    sb.append("    Latency: ").append(toStringLatency(meta)).append(NL);
    sb.append("    Bandwidth: ").append(toStringBandwidth(meta)).append(NL);
    sb.append("    Geo: ").append(toStringGeo(meta)).append(NL);
    sb.append("    LinkType: ").append(toStringLinkType(meta)).append(NL);
    sb.append("    Notes: ").append(meta.getMtu()).append(NL);
    sb.append("    SupportsEPIC: ").append(meta.getMtu()).append(NL);
    sb.append("    Status: ").append(meta.getMtu()).append(NL);
    sb.append("    LocalIP: ").append(meta.getMtu()).append(NL);

    println(sb.toString());
  }

  private static String toStringLatency(PathMetadata meta) {
    int latencyMs = 0;
    boolean latencyComplete = true;
    for (int l : meta.getLatencyList()) {
      if (l >= 0) {
        latencyMs += l;
      } else {
        latencyComplete = false;
      }
    }
    if (latencyComplete) {
      return latencyMs + "ms";
    } else {
      return ">" + latencyMs + "ms (information incomplete)";
    }
  }

  private static String toStringBandwidth(PathMetadata meta) {
    long bw = 0;
    boolean bwComplete = true;
    for (long l : meta.getBandwidthList()) {
      if (l > 0) {
        bw += l;
      } else {
        bwComplete = false;
      }
    }
    String bwString = bw + "KBit/s";
    if (!bwComplete) {
      bwString += "KBit/s (information incomplete)";
    }
    return bwString;
  }

  private static String toStringGeo(PathMetadata meta) {
    StringBuilder s = new StringBuilder("[");
    int n = meta.getGeoList().size();
    int i = 0;
    for (PathMetadata.GeoCoordinates g : meta.getGeoList()) {
      if (g.getLatitude() == 0 && g.getLongitude() == 0 && g.getAddress().isEmpty()) {
        s.append("N/A");
      } else {
        s.append(g.getLatitude()).append(",").append(g.getLongitude());
        s.append(" (\"").append(g.getAddress()).append("\")");
      }
      if (i < n - 1) {
        s.append(" > ");
      }
      i++;
    }
    s.append("]");
    return s.toString();
  }

  private static String toStringLinkType(PathMetadata meta) {
    StringBuilder s = new StringBuilder("[");
    int n = meta.getLinkTypeList().size();
    int i = 0;
    for (PathMetadata.LinkType lt : meta.getLinkTypeList()) {
      switch (lt) {
        case LINK_TYPE_UNSPECIFIED:
          s.append("unset");
          break;
        case LINK_TYPE_DIRECT:
          s.append("direct");
          break;
        case LINK_TYPE_MULTI_HOP:
          s.append("multihop");
          break;
        case LINK_TYPE_OPEN_NET:
          s.append("opennet");
          break;
        default:
          s.append("unset");
          break;
      }
      if (i < n - 1) {
        s.append(", ");
      }
      i++;
    }
    s.append("]");
    return s.toString();
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
