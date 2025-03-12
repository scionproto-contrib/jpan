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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
          runDemo(ScionUtil.parseIA("71-2:0:48"));
          // runDemo(DemoConstants.iaAnapayaHK);
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
        printExtended(path, localIP);
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

  private static void printExtended(Path path, String localIP) {
    StringBuilder sb = new StringBuilder();
    String NL = System.lineSeparator();

    PathMetadata meta = path.getMetadata();
    sb.append("    MTU: ").append(meta.getMtu()).append(NL);
    sb.append("    NextHop: ").append(meta.getFirstHopAddress().getHostString()).append(NL);
    sb.append("    Expires: ").append(toStringExpiry(meta)).append(NL);
    sb.append("    Latency: ").append(toStringLatency(meta)).append(NL);
    sb.append("    Bandwidth: ").append(toStringBandwidth(meta)).append(NL);
    sb.append("    Geo: ").append(toStringGeo(meta)).append(NL);
    sb.append("    LinkType: ").append(toStringLinkType(meta)).append(NL);
    sb.append("    Notes: ").append(toStringNotes(meta)).append(NL);
    sb.append("    SupportsEPIC: ").append(toStringEPIC(meta)).append(NL);
    // TODO, see private/app/path/pathprobe/paths.go
    sb.append("    Status: ").append("unknown").append(NL);
    // TODO use destination IP from returned packet from probe
    sb.append("    LocalIP: ").append(localIP).append(NL);

    println(sb.toString());
  }

  private static String toStringExpiry(PathMetadata meta) {
    Instant exp = Instant.ofEpochSecond(meta.getExpiration());
    DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z").withZone(ZoneId.of("UTC"));
    Instant now = Instant.now();
    long s = Duration.between(now, exp).getSeconds();
    String ds = String.format("(%dh%02dm%02ds)", s / 3600, (s % 3600) / 60, (s % 60));
    return formatter.format(exp) + " UTC " + ds;
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
    long bw = Long.MAX_VALUE;
    boolean bwComplete = true;
    for (long l : meta.getBandwidthList()) {
      if (l > 0) {
        bw = Math.min(bw, l);
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
    for (PathMetadata.GeoCoordinates g : meta.getGeoList()) {
      if (s.length() > 1) {
        s.append(" > ");
      }
      if (g.getLatitude() == 0 && g.getLongitude() == 0 && g.getAddress().isEmpty()) {
        s.append("N/A");
      } else {
        s.append(g.getLatitude()).append(",").append(g.getLongitude());
        String addr = g.getAddress().replace("\n", ", ");
        s.append(" (\"").append(addr).append("\")");
      }
    }
    s.append("]");
    return s.toString();
  }

  private static String toStringLinkType(PathMetadata meta) {
    StringBuilder s = new StringBuilder("[");
    for (PathMetadata.LinkType lt : meta.getLinkTypeList()) {
      if (s.length() > 1) {
        s.append(", ");
      }
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
    }
    s.append("]");
    return s.toString();
  }

  private static String toStringEPIC(PathMetadata meta) {
    PathMetadata.EpicAuths ea = meta.getEpicAuths();
    if (ea == null) {
      return "false";
    }
    if (ea.getAuthLhvf() != null && ea.getAuthLhvf().length == 16) {
      return "true";
    }
    if (ea.getAuthPhvf() != null && ea.getAuthPhvf().length == 16) {
      return "true";
    }
    return "false";
  }

  private static String toStringNotes(PathMetadata meta) {
    StringBuilder s = new StringBuilder("[");
    int i = 0;
    for (String note : meta.getNotesList()) {
      if (note != null && !note.isEmpty()) {
        if (s.length() > 1) {
          s.append(", ");
        }
        long isdAs = meta.getInterfacesList().get(Math.max(0, i * 2 - 1)).getIsdAs();
        s.append(ScionUtil.toStringIA(isdAs));
        s.append(": \"").append(note).append("\"");
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
