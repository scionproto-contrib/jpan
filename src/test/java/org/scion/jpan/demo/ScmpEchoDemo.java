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
    JUNIT_MOCK, // SCION Java JUnit mock network
    SCION_PROTO, // Try to connect to scionproto networks, e.g. "tiny"
    PRODUCTION // production network
  }

  public ScmpEchoDemo() {
    this(12345);
  }

  public ScmpEchoDemo(int localPort) {
    this.localPort = localPort;
  }

  private static final Network network = Network.SCION_PROTO;

  public static void main(String[] args) throws IOException {
    switch (network) {
      case JUNIT_MOCK:
        {
          DemoTopology.configureMock();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          ScmpEchoDemo demo = new ScmpEchoDemo();
          demo.runDemo(DemoConstants.ia110, serviceIP);
          DemoTopology.shutDown();
          break;
        }
      case SCION_PROTO:
        // Same as:
        // scion ping 2-ff00:0:211,127.0.0.10 --sciond 127.0.0.43:30255
        {
          // System.setProperty(
          //     Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
          //     "topologies/minimal/ASff00_0_1111/topology.json");
          System.setProperty(Constants.PROPERTY_DAEMON, DemoConstants.daemon1111_minimal);
          ScmpEchoDemo demo = new ScmpEchoDemo();
          demo.runDemo(DemoConstants.ia211, serviceIP);
          // demo.runDemo(DemoConstants.ia111, toAddr(DemoConstants.daemon111_minimal));
          // demo.runDemo(DemoConstants.ia1111, toAddr(DemoConstants.daemon1111_minimal));
          break;
        }
      case PRODUCTION:
        {
          // Local port must be 30041 for networks that expect a dispatcher
          ScmpEchoDemo demo = new ScmpEchoDemo(30041);
          // demo.runDemo(DemoConstants.iaOVGU, serviceIP);
          InetAddress ethzIP = Scion.defaultService().getScionAddress("ethz.ch").getInetAddress();
          demo.runDemo(DemoConstants.iaETH, new InetSocketAddress(ethzIP, 30041));
          // demo.runDemo(DemoConstants.iaGEANT, serviceIP);
          break;
        }
    }
  }

  private void runDemo(long dstIA, InetSocketAddress dstAddress) throws IOException {
    List<RequestPath> paths = Scion.defaultService().getPaths(dstIA, dstAddress);
    RequestPath path = paths.get(0);
    ByteBuffer data = ByteBuffer.allocate(0);

    println("Listening on port " + localPort + " ...");
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.connect(path);
      println("Resolved local address: ");
      println("  " + channel.getLocalAddress().getAddress().getHostAddress());
    }

    try (ScmpChannel scmpChannel = Scmp.createChannel(path, localPort)) {
      printPath(scmpChannel);
      for (int i = 0; i < 10; i++) {
        Scmp.EchoMessage msg = scmpChannel.sendEchoRequest(i, data);
        if (i == 0) {
          printHeader(dstIA, dstAddress, data, msg);
        }
        String millis = String.format("%.3f", msg.getNanoSeconds() / (double) 1_000_000);
        String echoMsgStr = msg.getSizeReceived() + " bytes from ";
        // TODO get actual address from response
        InetAddress addr = msg.getPath().getDestinationAddress();
        echoMsgStr += ScionUtil.toStringIA(dstIA) + "," + addr.getHostAddress();
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

  private void printPath(ScmpChannel channel) {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder();
    //    sb.append("Actual local address:").append(nl);
    //    sb.append("
    // ").append(channel.getLocalAddress().getAddress().getHostAddress()).append(nl);
    RequestPath path = channel.getConnectionPath();
    sb.append("Using path:").append(nl);
    sb.append("  Hops: ").append(ScionUtil.toStringPath(path));
    sb.append(" MTU: ").append(path.getMtu());
    sb.append(" NextHop: ").append(path.getInterface().getAddress()).append(nl);
    println(sb.toString());
  }

  private void printHeader(
      long dstIA, InetSocketAddress dstAddress, ByteBuffer data, Scmp.EchoMessage msg) {
    String sb =
        "PING "
            + ScionUtil.toStringIA(dstIA)
            + ","
            + dstAddress.getHostString()
            + ":"
            + dstAddress.getPort()
            + " pld="
            + data.remaining()
            + "B scion_pkt="
            + msg.getSizeSent()
            + "B";
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
    return new InetSocketAddress(addr, 30041);
  }
}