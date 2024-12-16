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
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.MockNetwork;

class NatMappingTest {

  @BeforeEach
  void beforeEach() {
    System.clearProperty(Constants.PROPERTY_NAT);
    System.clearProperty(Constants.PROPERTY_NAT_STUN_SERVER);
    MockNetwork.stopTiny();
  }

  @AfterAll
  static void afterAll() {
    System.clearProperty(Constants.PROPERTY_NAT);
    System.clearProperty(Constants.PROPERTY_NAT_STUN_SERVER);
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
      assertTrue(e.getMessage().startsWith("Illegal value for STUN config: "));
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

  private RequestPath createLocalPath(InetSocketAddress remoteHost) {
    long isdAs = ScionUtil.parseIA("1-ff00:0:110");
    return PackageVisibilityHelper.createDummyPath(
        isdAs, new byte[] {127, 0, 0, 1}, 54321, new byte[] {}, remoteHost);
  }

  private RequestPath createPath(InetSocketAddress firstHop) {
    long isdAs = ScionUtil.parseIA("1-ff00:0:112");
    return PackageVisibilityHelper.createDummyPath(
        isdAs, new byte[] {127, 0, 0, 1}, 54321, ExamplePacket.PATH_RAW_TINY_110_112, firstHop);
  }
}
