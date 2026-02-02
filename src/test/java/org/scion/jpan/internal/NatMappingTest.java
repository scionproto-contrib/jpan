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
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.TestUtil;

class NatMappingTest {

  @BeforeEach
  void beforeEach() {
    afterAll();
  }

  @AfterAll
  static void afterAll() {
    System.clearProperty(Constants.PROPERTY_NAT);
    System.clearProperty(Constants.PROPERTY_NAT_STUN_SERVER);
    System.clearProperty(Constants.PROPERTY_NAT_MAPPING_KEEPALIVE);
    System.clearProperty(Constants.PROPERTY_NAT_MAPPING_TIMEOUT);
    MockNetwork.stopTiny();
  }

  @Test
  void test_wrongSetting() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "Hello!");
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      List<InetSocketAddress> brs = new ArrayList<>();
      brs.add(new InetSocketAddress("127.0.0.1", 55555));
      Exception e =
          assertThrows(
              IllegalArgumentException.class, () -> NatMapping.createMapping(isdAs, channel, brs));
      assertTrue(e.getMessage().startsWith("Illegal value for NAT config: "));
    }
  }

  @Test
  void testOFF() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "OFF");
    MockNetwork.startTiny();
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      Path path = ExamplePacket.PATH_IPV4;
      NatMapping natMapping =
          NatMapping.createMapping(isdAs, channel, MockNetwork.getBorderRouterAddresses());
      InetSocketAddress src = natMapping.getMappedAddress(path);
      assertEquals(local, src);
      assertFalse(src.getAddress().isAnyLocalAddress());
      assertEquals(local.getAddress(), natMapping.getExternalIP());
    }
  }

  @Test
  void testBR() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    MockNetwork.startTiny();
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      Path path = createPath(MockNetwork.getBorderRouterAddress1());
      NatMapping natMapping =
          NatMapping.createMapping(isdAs, channel, MockNetwork.getBorderRouterAddresses());
      InetSocketAddress src = natMapping.getMappedAddress(path);
      assertEquals(local, src);
      assertFalse(src.getAddress().isAnyLocalAddress());
      assertEquals(local.getAddress(), natMapping.getExternalIP());
    }
  }

  @Test
  void testBR_pathToHostInLocalAS() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    MockNetwork.startTiny();
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      InetSocketAddress remoteHost = IPHelper.toInetSocketAddress("127.0.0.1:23456");
      Path path = createLocalPath(remoteHost);
      NatMapping natMapping =
          NatMapping.createMapping(isdAs, channel, MockNetwork.getBorderRouterAddresses());
      InetSocketAddress src = natMapping.getMappedAddress(path);
      assertEquals(local, src);
      assertFalse(src.getAddress().isAnyLocalAddress());
      assertEquals(local.getAddress(), natMapping.getExternalIP());
    }
  }

  @Test
  void testBR_pathToHostInLocalAS_notBRsInLocalAs() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 12555));
      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      InetSocketAddress remoteHost = IPHelper.toInetSocketAddress("127.0.0.1:23456");
      Path path = createLocalPath(remoteHost);
      NatMapping natMapping = NatMapping.createMapping(isdAs, channel, Collections.emptyList());
      InetSocketAddress src = natMapping.getMappedAddress(path);
      assertEquals(local, src);
      assertFalse(src.getAddress().isAnyLocalAddress());
      assertEquals(local.getAddress(), natMapping.getExternalIP());
    }
  }

  @Test
  void testBR_noSTUN() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    MockNetwork.startTiny();
    MockNetwork.disableStun();
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      Path path = createPath(MockNetwork.getBorderRouterAddress1());
      NatMapping natMapping =
          NatMapping.createMapping(isdAs, channel, MockNetwork.getBorderRouterAddresses());
      Exception e =
          assertThrows(IllegalStateException.class, () -> natMapping.getMappedAddress(path));
      assertEquals("No mapped source for: " + path.getFirstHopAddress(), e.getMessage());
    }
  }

  @Test
  void testBR_notRunning() throws IOException {
    MockNetwork.startTiny();
    InetSocketAddress firstHop = MockNetwork.getBorderRouterAddress1();
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();
    MockNetwork.stopTiny();

    System.setProperty(Constants.PROPERTY_NAT, "BR");
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 12555));
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      Path path = createPath(firstHop);
      NatMapping natMapping = NatMapping.createMapping(isdAs, channel, brs);
      assertThrows(IllegalStateException.class, () -> natMapping.getMappedAddress(path));
    }
  }

  @Test
  void testCUSTOM_fails_STUN_router_problem() {
    System.setProperty(Constants.PROPERTY_NAT, "CUSTOM");
    List<InetSocketAddress> brs = new ArrayList<>();
    brs.add(new InetSocketAddress("127.0.0.1", 55555));

    Path path = ExamplePacket.PATH_IPV4;
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);

    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, "127.0.0.1:34343");
    Exception e = assertThrows(ScionRuntimeException.class, () -> tryIFD(path, local, brs));
    assertEquals("Failed to connect to STUN servers: \"127.0.0.1:34343\"", e.getMessage());
  }

  @Test
  void testCUSTOM_fails_syntax_problem() {
    System.setProperty(Constants.PROPERTY_NAT, "CUSTOM");
    List<InetSocketAddress> brs = new ArrayList<>();
    brs.add(new InetSocketAddress("127.0.0.1", 55555));

    Path path = ExamplePacket.PATH_IPV4;
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);

    String prefix =
        "Please provide a valid STUN server address as 'address:port' in "
            + "SCION_STUN_SERVER or org.scion.stun.server, was: ";

    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, "");
    Exception e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, "nonsense");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"nonsense\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, "nonsense:12345");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"nonsense:12345\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, "nonsense:1234567");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"nonsense:1234567\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, "127.0.0.1:1234567");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"127.0.0.1:1234567\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, "127.0.0.0.1:12345");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"127.0.0.0.1:12345\"", e.getMessage());
  }

  private InetSocketAddress tryIFD(Path path, InetSocketAddress bind, List<InetSocketAddress> brs)
      throws IOException {
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(bind);
      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
      NatMapping natMapping = NatMapping.createMapping(isdAs, channel, brs);
      return natMapping.getMappedAddress(path);
    }
  }

  @Test
  void testCUSTOM() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "CUSTOM");

    MockNetwork.startTiny();
    InetSocketAddress br = MockNetwork.getBorderRouterAddress1();
    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, br.getHostString() + ":" + br.getPort());

    Path path = createPath(br);
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12444);
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();

    InetSocketAddress src = tryIFD(path, local, brs);
    assertEquals(local, src);
    assertFalse(src.getAddress().isAnyLocalAddress());
  }

  @Test
  void testAUTO_CUSTOM() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "AUTO");

    MockNetwork.startTiny();
    InetSocketAddress br = MockNetwork.getBorderRouterAddress1();
    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, br.getHostString() + ":" + br.getPort());

    Path path = createPath(br);
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12333);
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();

    InetSocketAddress src = tryIFD(path, local, brs);
    assertEquals(local, src);
    assertFalse(src.getAddress().isAnyLocalAddress());
  }

  @Test
  void testAUTO_noCUSTOM_BR() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "AUTO");

    MockNetwork.startTiny();

    Path path = createPath(MockNetwork.getBorderRouterAddress1());
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12777);
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();

    InetSocketAddress src = tryIFD(path, local, brs);
    assertEquals(local, src);
    assertFalse(src.getAddress().isAnyLocalAddress());
  }

  @Test
  void testAUTO_noCUSTOM_noBR() throws IOException {
    testAUTO_noCUSTOM_noBR(MockNetwork::stopTiny);
  }

  @Test
  void testAUTO_noCUSTOM_BRnoSTUN() throws IOException {
    testAUTO_noCUSTOM_noBR(MockNetwork::disableStun);
  }

  void testAUTO_noCUSTOM_noBR(Runnable breakBR) throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "AUTO");

    MockNetwork.startTiny();

    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12666);
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();
    Path path = createPath(MockNetwork.getBorderRouterAddress1());

    // Stop BR
    breakBR.run();

    // We now should have NO_NAT policy.
    InetSocketAddress src = tryIFD(path, local, brs);
    assertEquals(local, src);
    assertFalse(src.getAddress().isAnyLocalAddress());
  }

  @Test
  void testPrefetch_receive() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    MockNetwork.startTiny();
    ManagedThread receiver =
        ManagedThread.newBuilder().expectThrows(ClosedByInterruptException.class).build();
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.configureBlocking(true);

      receiver.submit(
          news -> {
            news.reportStarted();
            ByteBuffer buffer = ByteBuffer.allocate(1000);
            channel.receive(buffer);
            news.reportException(new IllegalStateException("This should never be reached."));
          });

      // TODO test jpan with DEFAULT=AUTO

      Path path = createPath(MockNetwork.getBorderRouterAddress1());
      ByteBuffer sendBuffer = ByteBuffer.allocate(1000);
      channel.send(sendBuffer, path);

      receiver.join(10);

      assertTrue(channel.isBlocking());

      // 1 normal packet
      assertEquals(1, MockNetwork.getAndResetForwardCount());
      // 2 STUN packets
      assertEquals(2, MockNetwork.getAndResetStunCount());
    } finally {
      receiver.stopNow();
    }
  }

  @Test
  void testExpiredCUSTOM() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_KEEPALIVE, "false");
    System.setProperty(Constants.PROPERTY_NAT, "CUSTOM");
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_TIMEOUT, "0.1"); // 100ms

    MockNetwork.startTiny();
    InetSocketAddress br = MockNetwork.getBorderRouterAddress1();
    System.setProperty(Constants.PROPERTY_NAT_STUN_SERVER, br.getHostString() + ":" + br.getPort());

    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12666);
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();
    Path path = createPath(MockNetwork.getBorderRouterAddress1());

    NatMapping natMapping = null;
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(local);
      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
      natMapping = NatMapping.createMapping(isdAs, channel, brs);
      // Two initial requests
      assertEquals(1, MockNetwork.getAndResetStunCount());
      TestUtil.sleep(150);
      assertEquals(0, MockNetwork.getAndResetStunCount());

      // Trigger isExpired() detection
      natMapping.getMappedAddress(path);
      assertEquals(1, MockNetwork.getAndResetStunCount());
    } finally {
      if (natMapping != null) {
        natMapping.close();
      }
    }
  }

  @Test
  void testExpiredBR() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_KEEPALIVE, "false");
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_TIMEOUT, "0.1"); // 100ms

    MockNetwork.startTiny();

    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12666);
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();
    Path path = createPath(MockNetwork.getBorderRouterAddress1());

    NatMapping natMapping = null;
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(local);
      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
      natMapping = NatMapping.createMapping(isdAs, channel, brs);
      // Two initial requests
      assertEquals(2, MockNetwork.getAndResetStunCount());
      TestUtil.sleep(150);
      assertEquals(0, MockNetwork.getAndResetStunCount());

      // Trigger isExpired() detection
      natMapping.getMappedAddress(path);
      assertEquals(1, MockNetwork.getAndResetStunCount());
    } finally {
      if (natMapping != null) {
        natMapping.close();
      }
    }
  }

  @Test
  void testKeepAliveBR() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_KEEPALIVE, "true");
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_TIMEOUT, "0.2"); // 200ms

    MockNetwork.startTiny();

    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12666);
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();

    NatMapping natMapping = null;
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(local);
      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
      natMapping = NatMapping.createMapping(isdAs, channel, brs);
      // Two initial requests
      assertEquals(2, MockNetwork.getAndResetStunCount());
      // One keep alive per IP
      awaitStunCount(2, 350);
    } finally {
      if (natMapping != null) {
        natMapping.close();
      }
    }
  }

  @Test
  void testKeepAliveBR_ResetTimerAfterUse() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_KEEPALIVE, "true");
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_TIMEOUT, "1");

    MockNetwork.startTiny();

    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12666);
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();

    NatMapping natMapping = null;
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(local);
      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
      natMapping = NatMapping.createMapping(isdAs, channel, brs);
      // Two initial requests
      assertEquals(2, MockNetwork.getAndResetStunCount());
      // Reset timer after 500ms
      TestUtil.sleep(500);
      natMapping.touch(brs.get(0));
      assertEquals(0, MockNetwork.getAndResetStunCount());

      // Wait for 1st IP to expire
      TestUtil.sleep(600);
      assertEquals(1, MockNetwork.getAndResetStunCount());

      // Wait for 2nd IP to expire
      TestUtil.sleep(600);
      // One keep alive per IP
      assertEquals(1, MockNetwork.getAndResetStunCount());
    } finally {
      if (natMapping != null) {
        natMapping.close();
      }
    }
  }

  @Test
  void testKeepAliveBR_ResetTimerAfterSend() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_KEEPALIVE, "true");
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_TIMEOUT, "1");

    MockNetwork.startTiny();

    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12666);
    List<InetSocketAddress> brs = MockNetwork.getBorderRouterAddresses();

    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.bind(local);
      // Two initial requests
      assertEquals(2, MockNetwork.getAndResetStunCount());
      // Reset timer after 500ms
      TestUtil.sleep(500);
      channel.send(ByteBuffer.allocate(0), createPath(brs.get(0)));
      assertEquals(0, MockNetwork.getAndResetStunCount());

      // Wait for 1st IP to expire
      TestUtil.sleep(600);
      assertEquals(1, MockNetwork.getAndResetStunCount());

      // Wait for 2nd IP to expire
      TestUtil.sleep(600);
      // One keep alive per IP
      assertEquals(1, MockNetwork.getAndResetStunCount());
    }
  }

  /** Test that receive() resets the time and that receive() does not block STUN. */
  @Test
  void testKeepAliveBR_ResetTimerAfterReceive() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_KEEPALIVE, "true");
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    System.setProperty(Constants.PROPERTY_NAT_MAPPING_TIMEOUT, "1");

    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);

    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 31111);
    ManagedThread receiver = ManagedThread.newBuilder().build();

    ScionService svc110 =
        Scion.newServiceWithTopologyFile(MockNetwork.TINY_CLIENT_TOPO_V4 + "/topology.json");
    ScionService svc112 =
        Scion.newServiceWithTopologyFile(MockNetwork.TINY_SRV_TOPO_V4 + "/topology.json");
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(svc112)) {
      channel.bind(local);
      receiver.submit(
          news -> {
            news.reportStarted();
            channel.receive(ByteBuffer.allocate(1000));
          });

      // Two initial requests
      assertEquals(2, MockNetwork.getAndResetStunCount());

      // Reset timer after 500ms
      TestUtil.sleep(500);
      try (ScionDatagramChannel sender = ScionDatagramChannel.open(svc110)) {
        sender.bind(null);
        assertEquals(2, MockNetwork.getAndResetStunCount());

        Path path = svc110.getPaths(ScionUtil.parseIA("1-ff00:0:112"), local).get(0);
        // Send a packet to the receiver.
        sender.send(ByteBuffer.allocate(100), path);
      } catch (Exception e) {
        e.printStackTrace();
        receiver.stopNow();
      }
      receiver.join(50);
      assertEquals(0, MockNetwork.getAndResetStunCount());

      // Wait for 1st IP to expire
      TestUtil.sleep(600);
      assertEquals(1, MockNetwork.getAndResetStunCount());

      // Wait for 2nd IP to expire
      TestUtil.sleep(600);
      // One keep alive per IP
      assertEquals(1, MockNetwork.getAndResetStunCount());
    } finally {
      receiver.stopNow();
      svc110.close();
      svc112.close();
    }
  }

  @Test
  void testDisconnectWithSend() throws IOException {
    testDisconnectWithSend(false);
  }

  @Test
  void testDisconnectWithReceive() throws IOException {
    // Verify that receive() triggers a STUN before blocking.
    testDisconnectWithSend(true);
  }

  void testDisconnectWithSend(boolean testReceive) throws IOException {
    // Disconnect should reset the NAT mapping.
    // Being on localhost and having no actual NAT, we can only count the number of STUN messages.
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    MockNetwork.startTiny();
    ManagedThread receiver =
        ManagedThread.newBuilder().expectThrows(ClosedByInterruptException.class).build();
    try (ScionDatagramChannel channelSend = ScionDatagramChannel.open()) {
      Path path = createPath(MockNetwork.getBorderRouterAddress1());
      ByteBuffer sendBuffer = ByteBuffer.allocate(100);
      channelSend.send(sendBuffer, path);

      // So far there should have been two STUN packets
      assertEquals(2, MockNetwork.getAndResetStunCount());
      channelSend.disconnect();

      // send again
      if (testReceive) {
        channelSend.configureBlocking(false);
        channelSend.receive(sendBuffer);
      } else {
        sendBuffer.flip();
        channelSend.send(sendBuffer, path);
      }
      // Now there should have been two more STUN packets
      assertEquals(2, MockNetwork.getAndResetStunCount());

      // finish
      receiver.join(10);
    } finally {
      receiver.stopNow();
    }
  }

  @Test
  void testBadStunPacket_BadTxID() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    MockNetwork.startTiny();
    MockNetwork.setStunCallback(
        (ByteBuffer out) -> {
          out.putLong(8, 1234567890);
          return true;
        });
    testIllegalStateException();
  }

  @Test
  void testBadStunPacket_BadLength() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    MockNetwork.startTiny();
    MockNetwork.setStunCallback(
        (ByteBuffer out) -> {
          out.putInt(3, 30000);
          return true;
        });
    testIllegalStateException();
  }

  @Test
  void testBadStunPacket_BadLength_Large() throws IOException {
    System.setProperty(Constants.PROPERTY_NAT, "BR");
    MockNetwork.startTiny();
    MockNetwork.setStunCallback(
        (ByteBuffer out) -> {
          out.put(new byte[30000]);
          return true;
        });
    testIllegalStateException();
  }

  void testIllegalStateException() throws IOException {
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      Path path = createPath(MockNetwork.getBorderRouterAddress1());
      NatMapping natMapping =
          NatMapping.createMapping(isdAs, channel, MockNetwork.getBorderRouterAddresses());
      Exception e =
          assertThrows(IllegalStateException.class, () -> natMapping.getMappedAddress(path));
      assertEquals("No mapped source for: " + path.getFirstHopAddress(), e.getMessage());
    }
  }

  private RequestPath createLocalPath(InetSocketAddress remoteHost) {
    long isdAs = ScionUtil.parseIA("1-ff00:0:110");
    byte[] remoteIP = remoteHost.getAddress().getAddress();
    return PackageVisibilityHelper.createDummyPath(
        isdAs, remoteIP, remoteHost.getPort(), new byte[] {}, remoteHost);
  }

  private RequestPath createPath(InetSocketAddress firstHop) {
    long isdAs = ScionUtil.parseIA("1-ff00:0:112");
    return PackageVisibilityHelper.createDummyPath(
        isdAs, new byte[] {127, 0, 0, 1}, 54321, ExamplePacket.PATH_RAW_TINY_110_112, firstHop);
  }

  private void awaitStunCount(int nExpected, int timeoutMs) {
    long startMs = System.currentTimeMillis();
    int n = 0;
    while (true) {
      n += MockNetwork.getAndResetStunCount();
      if (n == nExpected) {
        return;
      }
      long now = System.currentTimeMillis();
      assertTrue(now - startMs < timeoutMs);
      TestUtil.sleep(20);
    }
  }
}
