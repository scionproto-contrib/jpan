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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.scion.*;
import org.scion.Scmp;
import org.scion.testutil.MockDNS;

public class ScmpEchoDemo {

  private static final boolean PRINT = true;
  private static final int PORT = 12345;
  private final AtomicLong nowNanos = new AtomicLong();
  private final ByteBuffer sendBuffer = ByteBuffer.allocateDirect(8);
  private final int localPort;
  private DatagramChannel channel;
  private Path path;

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

  public ScmpEchoDemo() {
    this(12345);
  }

  public ScmpEchoDemo(int localPort) {
    this.localPort = localPort;
  }

  private static final Network network = Network.MINIMAL_PROTO;

  public static void main(String[] args) throws IOException, InterruptedException {
    switch (network) {
      case MOCK_TOPOLOGY:
        {
          DemoTopology.configureMock();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          ScmpEchoDemo demo = new ScmpEchoDemo();
          demo.doClientStuff(DemoConstants.ia110);
          DemoTopology.shutDown();
          break;
        }
      case TINY_PROTO:
        {
          DemoTopology.configureTiny110_112();
          MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
          ScmpEchoDemo demo = new ScmpEchoDemo();
          demo.doClientStuff(DemoConstants.ia110);
          DemoTopology.shutDown();
          break;
        }
      case MINIMAL_PROTO:
        {
          Scion.newServiceWithTopologyFile("topologies/minimal/ASff00_0_111/topology.json");
          //          Scion.newServiceWithDaemon(DemoConstants.daemon111_minimal);
          ScmpEchoDemo demo = new ScmpEchoDemo();
          demo.doClientStuff(DemoConstants.ia211);
          // demo.runDemo(DemoConstants.ia211);
          break;
        }
      case PRODUCTION:
        {
          // Scion.newServiceWithDNS("inf.ethz.ch");
          Scion.newServiceWithBootstrapServer("129.132.121.175:8041");
          // Port must be 30041 for networks that expect a dispatcher
          ScmpEchoDemo demo = new ScmpEchoDemo(30041);
          demo.doClientStuff(DemoConstants.iaOVGU);
          break;
        }
    }
  }

  private void echoListener(Scmp.ScmpEcho msg) {
    String echoMsgStr = msg.getTypeCode().getText();
    echoMsgStr += " scmp_seq=" + msg.getSequenceNumber();
    echoMsgStr += " time=" + getPassedMillies() + "ms";
    println("Received: " + echoMsgStr);
    send();
  }

  private void errorListener(Scmp.ScmpMessage msg) {
    Scmp.ScmpTypeCode code = msg.getTypeCode();
    String millies = getPassedMillies();
    println("SCMP error (after " + millies + "ms): " + code.getText() + " (" + code + ")");
    System.exit(1);
  }

  private String getPassedMillies() {
    long nanos = Instant.now().getNano() - nowNanos.get();
    return String.format("%.4f", nanos / (double) 1_000_000);
  }

  // TODO This method uses the new SCMP API but adds 4-5ms per ping.... ?!?!?!
  private void runDemo(long destinationIA) throws IOException {
    ScionService service = Scion.defaultService();
    // dummy address
    InetSocketAddress destinationAddress =
        new InetSocketAddress(Inet4Address.getByAddress(new byte[] {0, 0, 0, 0}), 12345);
    List<RequestPath> paths = service.getPaths(destinationIA, destinationAddress);
    RequestPath path = paths.get(0);

    System.out.println("Listening at port " + localPort + " ...");

    ByteBuffer data = ByteBuffer.allocate(0);
    try (ScmpChannel scmpChannel = Scmp.createChannel(path, localPort)) {
      for (int i = 0; i < 5; i++) {
        Scmp.Result<Scmp.ScmpEcho> result = scmpChannel.sendEchoRequest(i, data);
        Scmp.ScmpEcho msg = result.message;
        String millis = String.format("%.4f", result.nanoSeconds / (double) 1_000_000);
        String echoMsgStr = msg.getTypeCode().getText();
        echoMsgStr += " scmp_seq=" + msg.getSequenceNumber();
        echoMsgStr += " time=" + millis + "ms";
        println("Received: " + echoMsgStr);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private void doClientStuff(long destinationIA) throws IOException {
    //    try (DatagramChannel channel = DatagramChannel.open().bind(null)) {
    // InetSocketAddress local = new InetSocketAddress("127.0.0.1", 34567);
    InetSocketAddress local = new InetSocketAddress("0.0.0.0", localPort);
    ScionService service = Scion.defaultService();
    try (DatagramChannel channel = service.openChannel().bind(local)) {
      channel.configureBlocking(true);
      this.channel = channel;

      // InetSocketAddress serverAddress = new InetSocketAddress(ScmpServerDemo.hostName, PORT);
      InetSocketAddress destinationAddress =
          new InetSocketAddress(Inet4Address.getByAddress(new byte[] {0, 0, 0, 0}), PORT);

      // InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.10", 31004);

      // Tiny topology SCMP
      //      InetSocketAddress serverAddress = new InetSocketAddress("[fd00:f00d:cafe::7f00:9]",
      // 31012);

      // ScionSocketAddress serverAddress = ScionSocketAddress.create(isdAs, "::1", 44444);

      channel.setScmpErrorListener(this::errorListener);
      channel.setEchoListener(this::echoListener);

      List<RequestPath> paths = service.getPaths(destinationIA, destinationAddress);
      path = paths.get(0);

      String fromStr = ScionUtil.toStringIA(service.getLocalIsdAs());
      String toStr = ScionUtil.toStringIA(destinationIA) + " " + destinationAddress;
      println("Sending ECHO request from " + fromStr + " to " + toStr + " ...");

      send();

      println("Listening at " + channel.getLocalAddress() + " ...");
      channel.receive(null);

      channel.disconnect();
    }
  }

  private void send() {
    sendBuffer.clear();
    sendBuffer.putLong(localPort);
    sendBuffer.flip();
    nowNanos.set(Instant.now().getNano());
    try {
      // TODO why pass in path???????! Why not channel.default path?
      channel.sendEchoRequest(path, 0, sendBuffer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // wait
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
