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
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.scion.*;
import org.scion.Scmp;
import org.scion.testutil.MockDNS;

public class ScmpEchoDemo {

  private static final boolean PRINT = ScmpServerDemo.PRINT;
  private static final int PORT = ScmpServerDemo.PORT;
  private static final AtomicLong now = new AtomicLong();

  /**
   * True: connect to ScionPingPongChannelServer via Java mock topology False: connect to any
   * service via ScionProto "tiny" topology
   */
  private enum Mode {
    MOCK_TOPOLOGY,
    TINY,
    MINIMAL_PROTO, // Try to connect to "minimal" scionproto network
    PRODUCTION
  }

  private static Mode mode = Mode.TINY;

  public static void main(String[] args) throws IOException, InterruptedException {
    // Demo setup
    mode = Mode.MINIMAL_PROTO;
    switch (mode) {
      case MOCK_TOPOLOGY:
        {
          DemoTopology.configureMock();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          doClientStuff();
          DemoTopology.shutDown();
          break;
        }
      case TINY:
        {
          DemoTopology.configureTiny110_112();
          MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
          doClientStuff();
          DemoTopology.shutDown();
          break;
        }
      case MINIMAL_PROTO:
      {
        // Scion.newServiceWithDNS("inf.ethz.ch");
        Scion.newServiceWithTopologyFile("topologies/minimal/ASff00_0_110/topology.json");

        doClientStuff();
        break;
      }
      case PRODUCTION:
      {
        // Scion.newServiceWithDNS("inf.ethz.ch");
        Scion.newServiceWithBootstrapServer("129.132.121.175:8041");
        doClientStuff();
        break;
      }
    }
  }

  private static void echoListener(Scmp.ScmpEcho msg) {
    long millies = Instant.now().toEpochMilli() - now.get();
    println(
        "Received ECHO: "
            + msg.getIdentifier()
            + "/"
            + msg.getSequenceNumber()
            + " - "
            + millies
            + "ms");
  }

  private static void errorListener(Scmp.ScmpMessage msg) {
    long millies = Instant.now().toEpochMilli() - now.get();
    Scmp.ScmpTypeCode code = msg.getTypeCode();
    println("SCMP error (after " + millies + "ms): " + code.getText() + " (" + code + ")");
  }

  private static void doClientStuff() throws IOException {
    ScionService sv = Scion.defaultService();
    //    try (DatagramChannel channel = DatagramChannel.open().bind(null)) {
    // InetSocketAddress local = new InetSocketAddress("127.0.0.1", 34567);
    InetSocketAddress local = new InetSocketAddress("0.0.0.0", 30041 + 5);
    try (DatagramChannel channel = sv.openChannel().bind(local)) {
      channel.configureBlocking(true);

      InetSocketAddress serverAddress = new InetSocketAddress(ScmpServerDemo.hostName, PORT);

      // InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.10", 31004);
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");

      long iaETH = ScionUtil.parseIA("64-2:0:9");
      long iaETH_CORE = ScionUtil.parseIA("64-0:0:22f");
      long iaGEANT = ScionUtil.parseIA(ScionUtil.toStringIA(71, 20965));
      long iaOVGU = ScionUtil.parseIA("71-2:0:4a");
      long iaAnapayaHK = ScionUtil.parseIA("66-2:0:11");
      long iaCyberex = ScionUtil.parseIA("71-2:0:49");

//      long iaMinimal120 = ScionUtil.parseIA("1-ff00:0:120");
//      long iaMinimal210 = ScionUtil.parseIA("2-ff00:0:210");
      long iaMinimal211 = ScionUtil.parseIA("2-ff00:0:211");
//
//      isdAs = iaAnapayaHK;
      isdAs = iaMinimal211;
//      isdAs = iaMinimal120;

      // Tiny topology SCMP
      //      InetSocketAddress serverAddress = new InetSocketAddress("[fd00:f00d:cafe::7f00:9]",
      // 31012);
      //      long isdAs = ScionUtil.parseIA("1-ff00:0:112");

      // ScionSocketAddress serverAddress = ScionSocketAddress.create(isdAs, "::1", 44444);
      Path path = sv.getPaths(isdAs, serverAddress).get(0);

      channel.setScmpErrorListener(ScmpEchoDemo::errorListener);
      channel.setEchoListener(ScmpEchoDemo::echoListener);

      println("Sending echo request ...");
      // TODO match id + sn
      ByteBuffer data = ByteBuffer.allocate(8);
      data.putLong(30041);
      data.flip();
      now.set(Instant.now().toEpochMilli());
      channel.sendEchoRequest(path, 0, data);

      println("Waiting at " + channel.getLocalAddress() + " ...");
      channel.receive(null);

      channel.disconnect();
    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
