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
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockDNS;

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

  private static final boolean PRINT = true;
  private final int localPort;
  private static final InetSocketAddress serviceIP;

  static {
    try {
      serviceIP = new InetSocketAddress(Inet4Address.getByAddress(new byte[] {0, 0, 0, 0}), 12345);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  private enum Network {
    JUNIT_MOCK, // SCION Java JUnit mock network with local AS = 1-ff00:0:112
    SCION_PROTO, // Try to connect to scionproto networks, e.g. "tiny"
    PRODUCTION // production network
  }

  public ScmpEchoDemo() {
    this(12345);
  }

  public ScmpEchoDemo(int localPort) {
    this.localPort = localPort;
  }

  private static final Network network = Network.PRODUCTION;

  public static void main(String[] args) throws IOException {
    ScionService service = Scion.defaultService();
    switch (network) {
      case JUNIT_MOCK:
        {
          DemoTopology.configureMock();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          ScmpEchoDemo demo = new ScmpEchoDemo();
          demo.runDemo(service.getPaths(DemoConstants.ia112, serviceIP).get(0));
          DemoTopology.shutDown();
          break;
        }
      case SCION_PROTO:
        // Same as:
        // scion ping 2-ff00:0:211,127.0.0.10 --sciond 127.0.0.43:30255
        {
          // Bootstrap from topo file
          System.setProperty(
              Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
              "topologies/minimal/ASff00_0_1111/topology.json");
          // Bootstrap from SCION daemon
          // System.setProperty(Constants.PROPERTY_DAEMON, DemoConstants.daemon1111_minimal);

          ScmpEchoDemo demo = new ScmpEchoDemo();
          demo.runDemo(service.getPaths(DemoConstants.ia211, serviceIP).get(0));
          // demo.runDemo(service.getPaths(DemoConstants.ia111, serviceIP).get(0));
          // demo.runDemo(service.getPaths(DemoConstants.ia1111, serviceIP).get(0));
          break;
        }
      case PRODUCTION:
        {
          // Local port must be 30041 for networks that expect a dispatcher
          ScmpEchoDemo demo = new ScmpEchoDemo(Constants.SCMP_PORT);
          demo.runDemo(service.lookupAndGetPath("ethz.ch", Constants.SCMP_PORT, null));
          demo.runDemo(service.getPaths(DemoConstants.iaOVGU, serviceIP).get(0));
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
    try (ScmpChannel scmpChannel = Scmp.createChannel(localPort)) {
      for (int i = 0; i < 10; i++) {
        Scmp.EchoMessage msg = scmpChannel.sendEchoRequest(path, i, data);
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
          Thread.sleep(1000);
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

  private static InetSocketAddress toAddr(String addrString) throws UnknownHostException {
    int posColon = addrString.indexOf(':');
    InetAddress addr = InetAddress.getByName(addrString.substring(0, posColon));
    return new InetSocketAddress(addr, Constants.SCMP_PORT);
  }
}
