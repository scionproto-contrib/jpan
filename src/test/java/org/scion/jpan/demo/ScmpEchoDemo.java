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
import java.nio.ByteBuffer;
import java.util.List;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.Scenario;

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
public class ScmpEchoDemo {

  public static boolean PRINT = true;
  private static int REPEAT = 10;
  public static Network NETWORK = Network.PRODUCTION;
  private final int localPort;

  public enum Network {
    JUNIT_MOCK, // SCION Java JUnit mock network with local AS = 1-ff00:0:112
    SCION_PROTO, // Try to connect to scionproto networks, e.g. "tiny"
    PRODUCTION // production network
  }

  public static void init(boolean print, Network network, int repeat) {
    PRINT = print;
    NETWORK = network;
    REPEAT = repeat;
  }

  private ScmpEchoDemo() {
    this(12345);
  }

  private ScmpEchoDemo(int localPort) {
    this.localPort = localPort;
  }

  public static void main(String[] args) throws IOException {
    switch (NETWORK) {
      case JUNIT_MOCK:
        {
          DemoTopology.configureMock();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          ScmpEchoDemo demo = new ScmpEchoDemo();
          InetSocketAddress br211 = new InetSocketAddress("::1", MockNetwork.BORDER_ROUTER_PORT2);
          demo.runDemo(Scion.defaultService().getPaths(DemoConstants.ia112, br211).get(0));
          DemoTopology.shutDown();
          break;
        }
      case SCION_PROTO:
        // Same as:
        // scion ping 2-ff00:0:211,127.0.0.10 --sciond 127.0.0.43:30255
        {
          // Use scenario builder to get access to relevant IP addresses
          Scenario scenario = Scenario.readFrom("topologies/scionproto-default");
          long srcIsdAs = ScionUtil.parseIA("2-ff00:0:212");
          long dstIsdAs = ScionUtil.parseIA("2-ff00:0:222");

          if (!true) {
            // Alternative #1: Bootstrap from topo file
            System.setProperty(
                Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
                "topologies/scionproto-default/ASff00_0_212/topology.json");
          } else {
            // Alternative #2: Bootstrap from SCION daemon
            System.setProperty(Constants.PROPERTY_DAEMON, scenario.getDaemon(srcIsdAs));
          }

          // Use a port from the dispatcher compatibility range
          ScmpEchoDemo demo = new ScmpEchoDemo(32766);
          // Ping the dispatcher/shim. It listens on the same IP as the control service.
          InetAddress ip = scenario.getControlServer(dstIsdAs).getAddress();

          // Get paths
          List<Path> paths = Scion.defaultService().getPaths(dstIsdAs, ip, Constants.SCMP_PORT);
          demo.runDemo(PathPolicy.MIN_HOPS.filter(paths));
          break;
        }
      case PRODUCTION:
        {
          // Local port must be 30041 for networks that expect a dispatcher
          ScmpEchoDemo demo = new ScmpEchoDemo(Constants.SCMP_PORT);
          ScionService service = Scion.defaultService();
          demo.runDemo(service.lookupAndGetPath("ethz.ch", Constants.SCMP_PORT, null));
          break;
        }
    }
    Scion.closeDefault();
  }

  private void runDemo(Path path) throws IOException {
    ByteBuffer data = ByteBuffer.allocate(0);

    println("Listening on port " + localPort + " ...");
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(path);
      println("Resolved local address: ");
      println("  " + channel.getLocalAddress().getAddress().getHostAddress());
    }

    printPath(path);
    try (ScmpSender scmpChannel = Scmp.newSenderBuilder().setLocalPort(localPort).build()) {
      for (int i = 0; i < REPEAT; i++) {
        Scmp.EchoMessage msg = scmpChannel.sendEchoRequest(path, data);
        if (i == 0) {
          printHeader(path.getRemoteSocketAddress(), data, msg);
        }
        String millis = String.format("%.3f", msg.getNanoSeconds() / (double) 1_000_000);
        String echoMsgStr = msg.getSizeReceived() + " bytes from ";
        InetAddress addr = msg.getPath().getRemoteAddress();
        echoMsgStr += ScionUtil.toStringIA(path.getRemoteIsdAs()) + "," + addr.getHostAddress();
        echoMsgStr += ": scmp_seq=" + msg.getSequenceNumber();
        if (msg.isTimedOut()) {
          echoMsgStr += " Timed out after";
        }
        echoMsgStr += " time=" + millis + "ms";
        println(echoMsgStr);
        try {
          if (i < REPEAT - 1) {
            Thread.sleep(1000);
          }
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private void printPath(Path path) {
    String nl = System.lineSeparator();
    String sb = "Using path:" + nl + "  Hops: " + ScionUtil.toStringPath(path.getMetadata());
    sb += " MTU: " + path.getMetadata().getMtu();
    sb += " NextHop: " + path.getMetadata().getInterface().getAddress() + nl;
    println(sb);
  }

  private void printHeader(ScionSocketAddress dstAddress, ByteBuffer data, Scmp.EchoMessage msg) {
    String sb = "PING " + ScionUtil.toStringIA(dstAddress.getIsdAs()) + ",";
    sb += dstAddress.getHostString() + ":" + dstAddress.getPort() + " pld=" + data.remaining();
    sb += "B scion_pkt=" + msg.getSizeSent() + "B";
    println(sb);
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
