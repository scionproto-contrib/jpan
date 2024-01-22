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
import org.scion.*;
import org.scion.Scmp;
import org.scion.testutil.MockDNS;

@Deprecated // TODO remove
public class ScmpTracerouteDemo {

  private static final boolean PRINT = ScmpServerDemo.PRINT;
  public static int PORT = ScmpServerDemo.PORT;

  /**
   * True: connect to ScionPingPongChannelServer via Java mock topology False: connect to any
   * service via ScionProto "tiny" topology
   */
  public static boolean USE_MOCK_TOPOLOGY = false;

  public static void main(String[] args) throws IOException, InterruptedException {
    // Demo setup
    if (USE_MOCK_TOPOLOGY) {
      DemoTopology.configureMock();
      MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
      doClientStuff();
      DemoTopology.shutDown();
    } else {
      DemoTopology.configureTiny110_112();
      MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
      doClientStuff();
      DemoTopology.shutDown();
    }
  }

  private static void traceListener(Scmp.ScmpTraceroute msg) {
    if (PRINT) {
      System.out.println(
          "Received TRACEROUTE: " + msg.getIdentifier() + "/" + msg.getSequenceNumber());
    }
  }

  private static void errorListener(Scmp.ScmpMessage msg) {
    if (PRINT) {
      System.out.println("SCMP error: " + msg.getTypeCode().getText());
    }
  }

  private static void doClientStuff() throws IOException {
    //    try (DatagramChannel channel = DatagramChannel.open().bind(null)) {
    //      channel.configureBlocking(true);
    //
    //      InetSocketAddress serverAddress = new InetSocketAddress(ScmpServerDemo.hostName, PORT);
    //      long isdAs = ScionUtil.parseIA("1-ff00:0:112");
    //      // ScionSocketAddress serverAddress = ScionSocketAddress.create(isdAs, "::1", 44444);
    //      Path path = Scion.defaultService().getPaths(isdAs, serverAddress).get(0);
    //
    //      channel.setScmpErrorListener(ScmpTracerouteDemo::errorListener);
    //      channel.setTracerouteListener(ScmpTracerouteDemo::traceListener);
    //
    //      if (PRINT) {
    //        System.out.println("Client running on: " + channel.getLocalAddress());
    //        System.out.println("Sending traceroute request ...");
    //      }
    //      // TODO match id + sn
    //      channel.sendTracerouteRequest(path, 0, null);
    //
    //      if (PRINT) {
    //        System.out.println("Waiting ...");
    //      }
    //      channel.receive(null);
    //
    //      channel.disconnect();
    //    }
  }
}
