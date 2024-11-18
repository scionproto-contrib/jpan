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
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.scion.jpan.*;
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
        Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/scionproto-tiny/topology-110.json");
    try {
      MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
      //      // Stop the SCMP responder on 30041
      //      MockScmpHandler.stop();
      //
      //      testShim();
    } finally {
      MockNetwork.stopTiny();
      System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    }
  }

  @Test
  void testShim_noDaemon() throws IOException {
    try (MockNetwork2 unused =
        MockNetwork2.start("topologies/minimal/", "ASff00_0_110/topology.json")) {
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
    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      Path path = createDummyPath(Constants.SCMP_PORT);
      Scmp.EchoMessage msg = sender.sendEchoRequest(path, ByteBuffer.allocate(0));
      assertFalse(msg.isTimedOut());
    }
  }

  private Path createDummyPath(int serverPort) {
    long localIA = ScionUtil.parseIA("1-ff00:0:110");
    InetAddress dstAddr = InetAddress.getLoopbackAddress();
    InetSocketAddress firstHop = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12321);
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
