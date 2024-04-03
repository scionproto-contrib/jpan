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

package org.scion.demo;

import java.io.*;
import java.net.*;
import java.util.Collection;
import java.util.List;
import org.scion.*;
import org.scion.Scmp;
import org.scion.testutil.MockDNS;

public class ScmpTracerouteDemo {

  public static final boolean PRINT = true;
  private final int localPort;

  private enum Network {
    MOCK_TOPOLOGY, // SCION Java JUnit mock network
    TINY_PROTO, // Try to connect to "tiny" scionproto network
    MINIMAL_PROTO, // Try to connect to "minimal" scionproto network
    PRODUCTION // production network
  }

  public ScmpTracerouteDemo() {
    this(12345);
  }

  public ScmpTracerouteDemo(int localPort) {
    this.localPort = localPort;
  }

  private static final Network network = Network.PRODUCTION;

  public static void main(String[] args) throws IOException {
    switch (network) {
      case MOCK_TOPOLOGY:
        {
          DemoTopology.configureMock();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          ScmpTracerouteDemo demo = new ScmpTracerouteDemo();
          demo.runDemo(DemoConstants.ia110);
          DemoTopology.shutDown();
          break;
        }
      case TINY_PROTO:
        {
          // System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
          // "topologies/scionproto-tiny-110.json");
          System.setProperty(Constants.PROPERTY_DAEMON, DemoConstants.daemon1111_minimal);
          ScmpTracerouteDemo demo = new ScmpTracerouteDemo();
          demo.runDemo(DemoConstants.ia110);
          break;
        }
      case MINIMAL_PROTO:
        {
          // System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
          // "topologies/minimal/ASff00_0_1111/topology.json");
          System.setProperty(Constants.PROPERTY_DAEMON, DemoConstants.daemon1111_minimal);
          ScmpTracerouteDemo demo = new ScmpTracerouteDemo();
          demo.runDemo(DemoConstants.ia211);
          break;
        }
      case PRODUCTION:
        {
          // Local port must be 30041 for networks that expect a dispatcher
          ScmpTracerouteDemo demo = new ScmpTracerouteDemo(30041);
          demo.runDemo(DemoConstants.iaAnapayaHK);
          // demo.runDemo(DemoConstants.iaOVGU);
          break;
        }
    }
  }

  private void runDemo(long destinationIA) throws IOException {
    ScionService service = Scion.defaultService();
    // dummy address
    InetSocketAddress destinationAddress =
        new InetSocketAddress(Inet4Address.getByAddress(new byte[] {0, 0, 0, 0}), 12345);
    List<RequestPath> paths = service.getPaths(destinationIA, destinationAddress);
    if (paths.isEmpty()) {
      String src = ScionUtil.toStringIA(service.getLocalIsdAs());
      String dst = ScionUtil.toStringIA(destinationIA);
      throw new IOException("No path found from " + src + " to " + dst);
    }
    RequestPath path = paths.get(0);

    System.out.println("Listening at port " + localPort + " ...");

    try (ScmpChannel scmpChannel = Scmp.createChannel(path, localPort)) {
      Collection<Scmp.TracerouteMessage> results = scmpChannel.sendTracerouteRequest();
      for (Scmp.TracerouteMessage msg : results) {
        String millis = String.format("%.4f", msg.getNanoSeconds() / (double) 1_000_000);
        String echoMsgStr = msg.getTypeCode().getText();
        echoMsgStr += " scmp_seq=" + msg.getSequenceNumber();
        echoMsgStr += " " + ScionUtil.toStringIA(msg.getIsdAs()) + " IfID=" + msg.getIfID();
        echoMsgStr += " time=" + millis + "ms";
        println("Received: " + echoMsgStr);
      }
    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
