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
import java.util.List;
import org.scion.*;
import org.scion.Scmp;
import org.scion.testutil.MockDNS;

public class ScmpTracerouteDemo2 {

  private static final boolean PRINT = ScmpServerDemo.PRINT;
  private static final int PORT = ScmpServerDemo.PORT;
  private final int localPort;
  private final long destinationIA;

  /**
   * True: connect to ScionPingPongChannelServer via Java mock topology False: connect to any
   * service via ScionProto "tiny" topology
   */
  private enum Network {
    MOCK_TOPOLOGY, // SCION Java JUnit mock network
    TINY_PROTO, // Try to connect to "tiny" scionproto network
    MINIMAL_PROTO, // Try to connect to "minimal" scionproto network
    PRODUCTION // production network
  }

  public ScmpTracerouteDemo2(long destinationIA) {
    this(destinationIA, 12345);
  }

  public ScmpTracerouteDemo2(long destinationIA, int port) {
    this.destinationIA = destinationIA;
    localPort = port;
  }

  private static final Network network = Network.MINIMAL_PROTO;

  public static void main(String[] args) throws IOException, InterruptedException {
    switch (network) {
      case MOCK_TOPOLOGY:
        {
          DemoTopology.configureMock();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          ScmpTracerouteDemo2 demo = new ScmpTracerouteDemo2(DemoConstants.ia110);
          demo.doClientStuff();
          DemoTopology.shutDown();
          break;
        }
      case TINY_PROTO:
        {
          DemoTopology.configureTiny110_112();
          MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
          ScmpTracerouteDemo2 demo = new ScmpTracerouteDemo2(DemoConstants.ia110);
          demo.doClientStuff();
          DemoTopology.shutDown();
          break;
        }
      case MINIMAL_PROTO:
        {
          // Scion.newServiceWithTopologyFile("topologies/minimal/ASff00_0_111/topology.json");
          //          Scion.newServiceWithDaemon(DemoConstants.daemon111_minimal);
          //          ScmpEchoDemo demo = new ScmpEchoDemo(DemoConstants.ia110);
          Scion.newServiceWithDaemon(DemoConstants.daemon110_minimal);
          ScmpTracerouteDemo2 demo = new ScmpTracerouteDemo2(DemoConstants.ia211);
          demo.doClientStuff();
          break;
        }
      case PRODUCTION:
        {
          // Scion.newServiceWithDNS("inf.ethz.ch");
          Scion.newServiceWithBootstrapServer("129.132.121.175:8041");
          // Port must be 30041 for networks that expect a dispatcher
          ScmpTracerouteDemo2 demo = new ScmpTracerouteDemo2(DemoConstants.iaOVGU, 30041);
          demo.doClientStuff();
          break;
        }
    }
  }

  private void doClientStuff() throws IOException {
    ScionService service = Scion.defaultService();
    InetSocketAddress destinationAddress =
        new InetSocketAddress(Inet4Address.getByAddress(new byte[] {0, 0, 0, 0}), PORT);
    List<RequestPath> paths = service.getPaths(destinationIA, destinationAddress);
    RequestPath path = paths.get(0);

    System.out.println("Listening at port " + localPort + " ...");

    List<Scmp.Result<Scmp.ScmpTraceroute>> results = Scmp.sendTracerouteRequest(path, localPort);
    for (Scmp.Result<Scmp.ScmpTraceroute> r : results) {
      Scmp.ScmpTraceroute msg = r.message;
      String millies = String.format("%.4f", r.nanoSeconds / (double) 1_000_000);

      String echoMsgStr = msg.getTypeCode().getText();
      echoMsgStr += " scmp_seq=" + msg.getSequenceNumber();
      echoMsgStr += " " + ScionUtil.toStringIA(msg.getIsdAs()) + " IfID=" + msg.getIfID();
      echoMsgStr += " time=" + millies + "ms";
      println("Received: " + echoMsgStr);
    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
