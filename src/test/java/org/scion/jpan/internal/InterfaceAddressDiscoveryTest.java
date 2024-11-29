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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Constants;
import org.scion.jpan.Path;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockNetwork;

class InterfaceAddressDiscoveryTest {

  @BeforeEach
  void beforeEach() {
    System.clearProperty(Constants.PROPERTY_STUN);
    System.clearProperty(Constants.PROPERTY_STUN_SERVER);
    InterfaceAddressDiscovery.uninstall();
  }

  @Test
  void testOFF() throws IOException {
    System.setProperty(Constants.PROPERTY_STUN, "OFF");
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));
      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
      Path path = ExamplePacket.PATH_IPV4;
      InterfaceAddressDiscovery idf = InterfaceAddressDiscovery.getInstance();
      InetSocketAddress src =
          idf.getSourceAddress(path, local.getAddress(), local.getPort(), isdAs, channel);
      assertEquals(local, src);
      assertEquals(local.getAddress(), idf.getExternalIP(path, isdAs));
    }
  }

  //
  //  @Test
  //  void testBR_notRunning() throws IOException {
  //    System.setProperty(Constants.PROPERTY_STUN, "BR");
  //    try (DatagramChannel channel = DatagramChannel.open()) {
  //      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));
  //      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
  //      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
  //      Path path = ExamplePacket.PATH_IPV4;
  //      InterfaceAddressDiscovery idf = InterfaceAddressDiscovery.getInstance();
  //      InetSocketAddress src =
  //              idf.getSourceAddress(path, local.getAddress(), local.getPort(), isdAs, channel);
  //      assertEquals(local, src);
  //      assertEquals(local.getAddress(), idf.getExternalIP(path, isdAs));
  //    }
  //  }

  @Test
  void testPUBLIC() throws IOException {
    System.setProperty(Constants.PROPERTY_STUN, "PUBLIC");
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));
      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
      Path path = ExamplePacket.PATH_IPV4;
      InterfaceAddressDiscovery idf = InterfaceAddressDiscovery.getInstance();
      InetSocketAddress src =
          idf.getSourceAddress(path, local.getAddress(), local.getPort(), isdAs, channel);
      assertNotEquals(local, src);
      assertEquals(local.getPort(), src.getPort());
      assertEquals(local.getAddress(), idf.getExternalIP(path, isdAs));
    }
  }

  @Test
  void testCUSTOM_fails() {
    System.setProperty(Constants.PROPERTY_STUN, "CUSTOM");

    Path path = ExamplePacket.PATH_IPV4;
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "nonsense");
    Exception e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local));
    assertTrue(e.getMessage().contains("Could not resolve STUN_SERVER address: \"nonsense\""));

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "nonsense:12345");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local));
    assertTrue(
        e.getMessage().contains("Could not resolve STUN_SERVER address: \"nonsense:12345\""));

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "nonsense:1234567");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local));
    assertTrue(
        e.getMessage().contains("Could not resolve STUN_SERVER address: \"nonsense:1234567\""));

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "127.0.0.1:1234567");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local));
    assertTrue(e.getMessage().contains("Could not resolve STUN_SERVER address: "), e.getMessage());

    System.setProperty(Constants.PROPERTY_STUN_SERVER, "127.0.0.0.1:12345");
    e = assertThrows(IllegalArgumentException.class, () -> tryIFD(path, local));
    assertTrue(e.getMessage().contains("Could not resolve STUN_SERVER address: "));
  }

  private InetSocketAddress tryIFD(Path path, InetSocketAddress local) throws IOException {
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));
      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
      InterfaceAddressDiscovery idf = InterfaceAddressDiscovery.getInstance();
      return idf.getSourceAddress(path, local.getAddress(), local.getPort(), isdAs, channel);
    }
  }

  @Test
  void testCUSTOM() throws IOException {
    System.setProperty(Constants.PROPERTY_STUN, "CUSTOM");

    MockNetwork.startTiny();
    InetSocketAddress br = MockNetwork.getBorderRouterAddress1();
    System.setProperty(Constants.PROPERTY_STUN_SERVER, br.getHostString() + ":" + br.getPort());

    Path path = ExamplePacket.PATH_IPV4;
    InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);

    InetSocketAddress src = tryIFD(path, local);
    assertEquals(local, src);

    //    try (DatagramChannel channel = DatagramChannel.open()) {
    //      channel.bind();
    //      InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();
    //      long isdAs = ScionUtil.parseIA("1-ff00:0:123");
    //      Path path = ExamplePacket.PATH_IPV4;
    //      InterfaceAddressDiscovery idf = InterfaceAddressDiscovery.getInstance();
    //      InetSocketAddress src =
    //              idf.getSourceAddress(path, local.getAddress(), local.getPort(), isdAs, channel);
    //      assertNotEquals(local, src);
    //      assertEquals(local.getPort(), src.getPort());
    //      assertEquals(local.getAddress(), idf.getExternalIP(path, isdAs));
    //    }
  }
}
