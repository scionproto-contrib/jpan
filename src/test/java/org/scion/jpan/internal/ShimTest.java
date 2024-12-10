// Copyright 2024 ETH Zurich
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

package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.scion.jpan.*;
import org.scion.jpan.Constants;
import org.scion.jpan.demo.inspector.PathHeaderScion;
import org.scion.jpan.demo.inspector.ScionHeader;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.demo.inspector.ScmpHeader;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockNetwork2;
import org.scion.jpan.testutil.MockScmpHandler;
import org.scion.jpan.testutil.PingPongChannelHelper;

class ShimTest {

  private static final AtomicInteger shimForwardingCounter = new AtomicInteger();
  private static final CountDownLatch serverBarrier = new CountDownLatch(1);

  @BeforeEach
  void beforeEach() {
    Scion.closeDefault();
    Shim.uninstall();
    shimForwardingCounter.set(0);
  }

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
    Shim.uninstall();
  }

  @AfterAll
  static void afterAll() {
    MockNetwork.stopTiny();
  }

  @Test
  void testShim_withDaemon() throws IOException {
    try {
      MockNetwork.startTiny(MockNetwork.Mode.DAEMON);
      // Stop the SCMP responder on 30041
      MockScmpHandler.stop();

      testShim();
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void testShim_withTopofile() throws IOException {
    System.setProperty(
        Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/tiny4/ASff00_0_110/topology.json");
    try {
      MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
      // Stop the SCMP responder on 30041
      MockScmpHandler.stop();

      testShim();
    } finally {
      MockNetwork.stopTiny();
      System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    }
  }

  @Test
  void testShim_noDaemon() throws IOException {
    try (MockNetwork2 unused = MockNetwork2.start("topologies/minimal/", "ASff00_0_110")) {
      testShim();
    }
  }

  private void testShim() throws IOException {
    assertFalse(Shim.isInstalled());
    ScionService service = Scion.defaultService();
    assertTrue(Shim.isInstalled());

    // test that SCMP echo requests are answered
    testScmpEchoReflect();
    testScmpEchoReflect();

    testScmpEchoResponse();

    testScmpResponse(Scmp.TypeCode.TYPE_129);
    testScmpResponse(Scmp.TypeCode.TYPE_129);
    // test traceroute response
    testScmpResponse(Scmp.TypeCode.TYPE_131);
    testScmpResponse(Scmp.TypeCode.TYPE_131);
    // test SCMP error with truncated payload
    testScmpResponse(Scmp.TypeCode.TYPE_5, true);
    testScmpResponse(Scmp.TypeCode.TYPE_5, true);
    // test SCMP error
    testScmpResponse(Scmp.TypeCode.TYPE_5);
    testScmpResponse(Scmp.TypeCode.TYPE_5);

    // check double install doesn't fail
    Shim.install(null);
    assertTrue(Shim.isInstalled());
    Shim.install(service);
    assertTrue(Shim.isInstalled());

    // This shouldn't be called normally, but we test it anyway
    Shim.uninstall();
    assertFalse(Shim.isInstalled());
    Shim.uninstall();
    assertFalse(Shim.isInstalled());
  }

  private void testScmpEchoReflect() throws IOException {
    // Test that the SHIM answers SCMP requests
    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      Path path = createDummyPath(Constants.SCMP_PORT);
      Scmp.EchoMessage msg = sender.sendEchoRequest(path, ByteBuffer.allocate(0));
      assertFalse(msg.isTimedOut());
    }
  }

  private void testScmpEchoResponse() throws IOException {
    // Test that an SCMP echo response ca n be forwarded by the SHIM
    ManagedThread responder = ManagedThread.newBuilder().build();
    AtomicInteger counter = new AtomicInteger();
    int port = 31212; // Port from non-dispatched port range
    try (ScmpSender sender = Scmp.newSenderBuilder().build();
        ScmpResponder scmpResponder = Scmp.newResponderBuilder().setLocalPort(port).build()) {
      scmpResponder.setScmpEchoListener(echoMessage -> counter.incrementAndGet() > 0);
      responder.submit(
          news -> {
            news.reportStarted();
            scmpResponder.start();
          });

      Path path = createDummyPath(port);
      Scmp.EchoMessage msg = sender.sendEchoRequest(path, ByteBuffer.allocate(0));
      assertFalse(msg.isTimedOut());
      assertEquals(1, counter.get());
    } finally {
      responder.join(10);
    }
  }

  private void testScmpResponse(Scmp.TypeCode scmpTypeCode) {
    testScmpResponse(scmpTypeCode, false);
  }

  private void testScmpResponse(Scmp.TypeCode scmpTypeCode, boolean expectError) {
    // This should happen e.g. for SCMP errors with truncated payload where the SHIM cannot
    // extract the original port.
    ManagedThread receiver = ManagedThread.newBuilder().expectException(expectError).build();
    AtomicInteger counter = new AtomicInteger();
    int port = 44444; // Use a port from the dispatcher mapped range
    InetSocketAddress senderAddr = IPHelper.toInetSocketAddress("[::1]:" + port);
    try {
      receiver.submit(
          news -> {
            try (DatagramChannel scmpReceiver = DatagramChannel.open()) {
              scmpReceiver.bind(senderAddr);
              news.reportStarted();
              ByteBuffer buf = ByteBuffer.allocate(1000);
              scmpReceiver.receive(buf);
              counter.incrementAndGet();
            }
          });

      sendScmpResponse(port, scmpTypeCode, expectError);
    } finally {
      receiver.join(10);
    }
    assertEquals(expectError ? 0 : 1, counter.get());
  }

  private void sendScmpResponse(int dstPort, Scmp.TypeCode type, boolean truncatePayload) {
    try (DatagramChannel sender = DatagramChannel.open()) {
      ScionPacketInspector spi = ScionPacketInspector.createEmpty();
      ScionHeader scionHeader = spi.getScionHeader();
      scionHeader.setSrcHostAddress(new byte[] {127, 0, 0, 1});
      scionHeader.setDstHostAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
      scionHeader.setSrcIA(ScionUtil.parseIA("1-ff00:0:110"));
      scionHeader.setDstIA(ScionUtil.parseIA("1-ff00:0:112"));

      byte[] path = ExamplePacket.PATH_RAW_TINY_110_112;
      PathHeaderScion pathHeader = spi.getPathHeaderScion();
      pathHeader.read(ByteBuffer.wrap(path)); // Initialize path

      ScmpHeader scmpHeader = spi.getScmpHeader();
      scmpHeader.setCode(type);
      if (type == Scmp.TypeCode.TYPE_131 || type == Scmp.TypeCode.TYPE_129) {
        scmpHeader.setIdentifier(dstPort);
      } else if (type == Scmp.TypeCode.TYPE_5) {
        byte[] payload = ExamplePacket.PACKET_BYTES_SERVER_E2E_PING;
        if (truncatePayload) {
          payload = Arrays.copyOf(payload, payload.length - 1);
        }
        spi.setPayLoad(payload);
      } else {
        throw new UnsupportedOperationException();
      }

      ByteBuffer data = ByteBuffer.allocate(1000);
      spi.writePacketSCMP(data);
      data.flip();

      InetSocketAddress shim =
          new InetSocketAddress(InetAddress.getLoopbackAddress(), Constants.SCMP_PORT);
      sender.send(data, shim);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Path createDummyPath(int serverPort) {
    InetSocketAddress firstHop = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12321);
    return createDummyPath(serverPort, firstHop);
  }

  private Path createDummyPath(int serverPort, InetSocketAddress firstHop) {
    long localIA = ScionUtil.parseIA("1-ff00:0:110");
    InetAddress dstAddr = InetAddress.getLoopbackAddress();
    return PackageVisibilityHelper.createDummyPath(
        localIA, dstAddr, serverPort, new byte[0], firstHop);
  }

  @Test
  void testForwardingUDP() {
    PingPongChannelHelper.Server serverFn = this::server;
    PingPongChannelHelper.Client clientFn = this::client;
    PingPongChannelHelper pph = PingPongChannelHelper.newBuilder(1, 2, 10).build();
    pph.runPingPong(serverFn, clientFn);
    assertTrue(Shim.isInstalled());
    assertEquals(2 * 10, shimForwardingCounter.getAndSet(0));
    assertEquals(2 * 2 * 10, MockNetwork.getAndResetForwardCount());
  }

  @Test
  void testShim_notStartedForALL() throws IOException {
    // Do not start SHIM in an AS with port range ALL
    MockNetwork.startTiny();
    ScionService service = null;
    try {
      service = Scion.newServiceWithTopologyFile("topologies/dispatcher-port-range-all.json");
      try (ScionDatagramChannel unused = ScionDatagramChannel.open(service)) {
        assertFalse(Shim.isInstalled());
      }
    } finally {
      if (service != null) {
        service.close();
      }
      MockNetwork.stopTiny();
    }
  }

  @Test
  void testForwardingUDP_LocalAS_remoteInsideRange() {
    testForwardingUDP_LocalAS(31000, 0);
  }

  @Test
  void testForwardingUDP_LocalAS_remoteOutsideRange() {
    testForwardingUDP_LocalAS(45678, 2 * 2 * 10);
  }

  void testForwardingUDP_LocalAS(int port, int expectedShimCount) {
    PingPongChannelHelper.Server serverFn = this::server;
    PingPongChannelHelper.Client clientFn = this::client;
    // we are circumventing the daemon! -> checkCounters(false)
    PingPongChannelHelper pph =
        PingPongChannelHelper.newBuilder(1, 2, 10)
            .checkCounters(false)
            .serverIsdAs(MockNetwork.TINY_CLIENT_ISD_AS)
            .serverBindAddress(new InetSocketAddress("127.0.0.1", port))
            .build();
    pph.runPingPong(serverFn, clientFn);
    assertTrue(Shim.isInstalled());
    assertEquals(expectedShimCount, shimForwardingCounter.getAndSet(0));
    assertEquals(0, MockNetwork.getAndResetForwardCount());
  }

  public void client(ScionDatagramChannel channel, Path serverAddress, int id) throws IOException {
    assertTrue(Shim.isInstalled());
    // Set callback
    Shim.setCallback(
        byteBuffer -> {
          shimForwardingCounter.incrementAndGet();
          return true;
        });

    // wait for server to start
    try {
      serverBarrier.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    String message = PingPongChannelHelper.MSG + "-" + id;
    ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());
    channel.send(sendBuf, serverAddress);

    ByteBuffer response = ByteBuffer.allocate(512);
    ScionSocketAddress address = channel.receive(response);
    assertNotNull(address);
    assertEquals(serverAddress.getRemoteAddress(), address.getAddress());
    assertEquals(serverAddress.getRemotePort(), address.getPort());

    response.flip();
    String pong = Charset.defaultCharset().decode(response).toString();
    assertEquals(message, pong);
  }

  public void server(ScionDatagramChannel channel) throws IOException {
    ByteBuffer request = ByteBuffer.allocate(512);
    serverBarrier.countDown();
    ScionSocketAddress responseAddress = channel.receive(request);

    request.flip();
    String msg = Charset.defaultCharset().decode(request).toString();
    assertTrue(msg.startsWith(PingPongChannelHelper.MSG), msg);
    assertTrue(PingPongChannelHelper.MSG.length() + 3 >= msg.length());

    request.flip();
    channel.send(request, responseAddress);
  }
}
