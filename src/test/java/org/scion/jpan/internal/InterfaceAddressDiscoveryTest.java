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
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockNetwork;

class InterfaceAddressDiscoveryTest {

  @BeforeEach
  void beforeEach() {
    System.clearProperty(Constants.PROPERTY_STUN);
    System.clearProperty(Constants.PROPERTY_STUN_SERVER);
    InterfaceAddressDiscovery.uninstall();
    MockNetwork.stopTiny();
  }

  @AfterAll
  static void afterAll() {
    System.clearProperty(Constants.PROPERTY_STUN);
    System.clearProperty(Constants.PROPERTY_STUN_SERVER);
    InterfaceAddressDiscovery.uninstall();
    MockNetwork.stopTiny();
  }

  @Test
  void test_wrongSetting() throws IOException {
    System.setProperty(Constants.PROPERTY_STUN, "Hello!");
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      Exception e =
          assertThrows(IllegalArgumentException.class, InterfaceAddressDiscovery::getInstance);
      assertTrue(e.getMessage().startsWith("Illegal value for STUN: "));
    }
  }

  @Test
  void testOFF() throws IOException {
    System.setProperty(Constants.PROPERTY_STUN, "OFF");
    MockNetwork.startTiny();
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      Path path = ExamplePacket.PATH_IPV4;
      InterfaceAddressDiscovery idf = InterfaceAddressDiscovery.getInstance();
      idf.prefetchMappings(isdAs, channel, MockNetwork.getBorderRouterAddresses());
      InetSocketAddress src = idf.getMappedAddress(path, isdAs, channel);
      assertEquals(local, src);
      assertFalse(src.getAddress().isAnyLocalAddress());
      assertEquals(local.getAddress(), idf.getExternalIP(path, isdAs));
    }
  }

  @Test
  void testBR() throws IOException {
    System.setProperty(Constants.PROPERTY_STUN, "BR");
    MockNetwork.startTiny();
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      Path path = createPath(MockNetwork.getBorderRouterAddress1());
      InterfaceAddressDiscovery idf = InterfaceAddressDiscovery.getInstance();
      idf.prefetchMappings(isdAs, channel, MockNetwork.getBorderRouterAddresses());
      InetSocketAddress src = idf.getMappedAddress(path, isdAs, channel);
      assertEquals(local, src);
      assertFalse(src.getAddress().isAnyLocalAddress());
      assertEquals(local.getAddress(), idf.getExternalIP(path, isdAs));
    }
  }

  @Test
  void testBR_notRunning() throws IOException {
    MockNetwork.startTiny();
    InetSocketAddress firstHop = MockNetwork.getBorderRouterAddress1();
    List<String> brs = MockNetwork.getBorderRouterAddresses();
    MockNetwork.stopTiny();

    System.setProperty(Constants.PROPERTY_STUN, "BR");
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 12555));
      long isdAs = ScionUtil.parseIA("1-ff00:0:110");
      Path path = createPath(firstHop);
      InterfaceAddressDiscovery idf = InterfaceAddressDiscovery.getInstance();
      idf.prefetchMappings(isdAs, channel, brs);
      InetSocketAddress src = idf.getMappedAddress(path, isdAs, channel);
      assertNull(src); // TODO assertThrows()?
    }
  }

  @Test
  void testCUSTOM_fails_STUN_router_problem() {
    System.setProperty(Constants.PROPERTY_STUN, "CUSTOM");
    List<String> brs = new ArrayList<>();
    brs.add("127.0.0.1:55555");

    Path path = ExamplePacket.PATH_IPV4;
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "127.0.0.1:34343");
    Exception e = assertThrows(ScionRuntimeException.class, () -> tryIFD(path, local, brs));
    assertEquals("Failed to connect to STUN servers: 127.0.0.1:34343", e.getMessage());
  }

  @Test
  void testCUSTOM_fails_syntax_problem() {
    System.setProperty(Constants.PROPERTY_STUN, "CUSTOM");
    List<String> brs = new ArrayList<>();
    brs.add("127.0.0.1:55555");

    Path path = ExamplePacket.PATH_IPV4;
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);

    String prefix =
        "Please provide a valid STUN server address as 'address:port' in "
            + "SCION_STUN_SERVER or org.scion.stun.server, was: ";

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "");
    Exception e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "nonsense");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"nonsense\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "nonsense:12345");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"nonsense:12345\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "nonsense:1234567");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"nonsense:1234567\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "127.0.0.1:1234567");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"127.0.0.1:1234567\"", e.getMessage());

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "127.0.0.0.1:12345");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local, brs));
    assertEquals(prefix + "\"127.0.0.0.1:12345\"", e.getMessage());
  }

  private InetSocketAddress tryIFD(Path path, InetSocketAddress bind, List<String> brs)
      throws IOException {
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(bind);
      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
      InterfaceAddressDiscovery idf = InterfaceAddressDiscovery.getInstance();
      idf.prefetchMappings(isdAs, channel, brs);
      return idf.getMappedAddress(path, isdAs, channel);
    }
  }

  @Test
  void testCUSTOM() throws IOException {
    System.setProperty(Constants.PROPERTY_STUN, "CUSTOM");

    MockNetwork.startTiny();
    InetSocketAddress br = MockNetwork.getBorderRouterAddress1();
    System.setProperty(Constants.PROPERTY_STUN_SERVER, br.getHostString() + ":" + br.getPort());

    Path path = createPath(br);
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12444);
    List<String> brs = MockNetwork.getBorderRouterAddresses();

    InetSocketAddress src = tryIFD(path, local, brs);
    assertEquals(local, src);
    assertFalse(src.getAddress().isAnyLocalAddress());
  }

  @Test
  void testAUTO_CUSTOM() throws IOException {
    System.setProperty(Constants.PROPERTY_STUN, "AUTO");

    MockNetwork.startTiny();
    InetSocketAddress br = MockNetwork.getBorderRouterAddress1();
    System.setProperty(Constants.PROPERTY_STUN_SERVER, br.getHostString() + ":" + br.getPort());

    Path path = createPath(br);
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12333);
    List<String> brs = MockNetwork.getBorderRouterAddresses();

    InetSocketAddress src = tryIFD(path, local, brs);
    assertEquals(local, src);
    assertFalse(src.getAddress().isAnyLocalAddress());
  }

  @Test
  void testAUTO_noCUSTOM_BR() throws IOException {
    System.setProperty(Constants.PROPERTY_STUN, "AUTO");

    MockNetwork.startTiny();

    Path path = createPath(MockNetwork.getBorderRouterAddress1());
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12777);
    List<String> brs = MockNetwork.getBorderRouterAddresses();

    InetSocketAddress src = tryIFD(path, local, brs);
    assertEquals(local, src);
    assertFalse(src.getAddress().isAnyLocalAddress());
  }

  @Test
  void testAUTO_noCUSTOM_noBR_noPUBLIC() {
    System.setProperty(Constants.PROPERTY_STUN, "AUTO");

    MockNetwork.startTiny();

    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12666);
    List<String> brs = MockNetwork.getBorderRouterAddresses();
    Path path = createPath(MockNetwork.getBorderRouterAddress1());

    // Stop BR
    MockNetwork.stopTiny();

    // This is not nice, we assume this fails because the PUBLIC servers are not reachable from
    // the loopback interface
    Exception e = assertThrows(ScionRuntimeException.class, () -> tryIFD(path, local, brs));
    assertEquals("Could not find a STUN/NAT solution for the border router.", e.getMessage());
  }

  private RequestPath createPath(InetSocketAddress firstHop) {
    return PackageVisibilityHelper.createDummyPath(
        ScionUtil.parseIA("1-ff00:0:112"),
        new byte[] {127, 0, 0, 1},
        54321,
        ExamplePacket.PATH_RAW_TINY_110_112,
        firstHop);
  }
}
