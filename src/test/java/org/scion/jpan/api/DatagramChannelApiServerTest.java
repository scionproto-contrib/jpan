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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.internal.IPHelper;
import org.scion.jpan.testutil.AsInfo;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.JsonFileParser;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockDaemon;
import org.scion.jpan.testutil.MockDatagramChannel;
import org.scion.jpan.testutil.MockNetwork;

class DatagramChannelApiServerTest {

  @AfterEach
  public void afterEach() throws IOException {
    MockDaemon.closeDefault();
    MockDNS.clear();
    ScionService.closeDefault();
  }

  @Test
  void open_withoutService() throws IOException {
    MockNetwork.startTiny();
    // check that open() (without service argument) creates a default service.
    ScionService.closeDefault();
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertEquals(Scion.defaultService(), channel.getService());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void open_withoutService2() throws IOException {
    MockNetwork.startTiny();
    // check that open() (without service argument) uses the default service.
    ScionService.closeDefault();
    ScionService service = Scion.defaultService();
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertEquals(service, channel.getService());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void open_withNullService() throws IOException {
    // check that open() (without service argument) does not internally create a ScionService.
    ScionService.closeDefault();
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(null)) {
      assertNull(channel.getService());
    }
  }

  @Test
  void open_withNullService2() throws IOException {
    MockNetwork.startTiny();
    // check that open() (without service argument) does not internally use a ScionService, even if
    // one exists.
    ScionService.closeDefault();
    Scion.defaultService();
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(null)) {
      assertNull(channel.getService());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void bind_withNullService() throws IOException {
    // check that bind() does not internally require a ScionService.
    ScionService.closeDefault();
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(null)) {
      channel.bind(new InetSocketAddress("127.0.0.1", 12345));
      assertNull(channel.getService());
    }
  }

  @Test
  void send_withNullService() throws IOException {
    // check that send(Path) does not internally require a ScionService.
    ScionService.closeDefault();
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(null)) {
      channel.bind(new InetSocketAddress("127.0.0.1", 12345));
      assertNull(channel.getService());
      ResponsePath path =
          PackageVisibilityHelper.createDummyResponsePath(
              new byte[0],
              1,
              new byte[4],
              1,
              1,
              new byte[4],
              1,
              new InetSocketAddress("127.0.0.1", 1));
      channel.send(ByteBuffer.allocate(0), path);
      assertNull(channel.getService());
    }
  }

  @Test
  void receive_withNullService() throws IOException {
    // check that receive() does not internally require a ScionService.
    SocketAddress addr = new InetSocketAddress("127.0.0.1", 12345);
    MockDatagramChannel mock = MockDatagramChannel.open();
    mock.setReceiveCallback(
        buf -> {
          buf.put(ExamplePacket.PACKET_BYTES_SERVER_E2E_PING);
          return addr;
        });
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(null, mock)) {
      channel.bind(new InetSocketAddress("127.0.0.1", 12345));
      assertNull(channel.getService());
      ByteBuffer buffer = ByteBuffer.allocate(100);
      ScionSocketAddress responseAddress = channel.receive(buffer);
      assertNull(channel.getService());
      assertEquals(addr, responseAddress.getPath().getFirstHopAddress());
    }
  }

  @Test
  void receive_correctSrc_divergentBR() throws IOException {
    String topoFile = "topologies/tiny4/ASff00_0_112/topology.json";
    // Check that the ResponsePath's first hop is looked up from the border router table,
    // i.e. that it doesn't simply use the underlay's source address as first hop.
    byte[] scionSrcBytes = {10, 0, 123, 123};
    byte[] underLaySrcBytes = {192 - 256, 168 - 256, 123, 123};
    InetSocketAddress underLaySrc =
        new InetSocketAddress(InetAddress.getByAddress(underLaySrcBytes), 12345);

    // Find border router address
    long isdAs112 = ScionUtil.parseIA(MockNetwork.TINY_CLIENT_ISD_AS);
    AsInfo as112 = JsonFileParser.parseTopologyFile(Paths.get(topoFile));
    String brAddressString = as112.getBorderRouterAddressByIA(isdAs112);
    InetSocketAddress brAddress = IPHelper.toInetSocketAddress(brAddressString);

    // prepare packet
    ScionPacketInspector spi = new ScionPacketInspector();
    spi.read(ByteBuffer.wrap(ExamplePacket.PACKET_BYTES_SERVER_E2E_PING));
    spi.getScionHeader().setSrcHostAddress(scionSrcBytes);
    InetAddress scionSrcIP = spi.getScionHeader().getSrcHostAddress();
    int scionSrcPort = spi.getOverlayHeaderUdp().getSrcPort();
    InetSocketAddress scionSrc = new InetSocketAddress(scionSrcIP, scionSrcPort);

    MockDatagramChannel mdc = MockDatagramChannel.open();
    mdc.setReceiveCallback(
        buf -> {
          spi.writePacket(buf, new byte[0]);
          return underLaySrc;
        });

    MockNetwork.startTiny();
    ScionService service = null;
    try {
      service = Scion.newServiceWithTopologyFile(topoFile);
      try (ScionDatagramChannel channel = ScionDatagramChannel.open(service, mdc)) {
        ScionSocketAddress address = channel.receive(ByteBuffer.allocate(1000));
        assertEquals(brAddress, address.getPath().getFirstHopAddress());
        assertNotEquals(scionSrc, address.getPath().getFirstHopAddress());
        assertNotEquals(underLaySrc, address.getPath().getFirstHopAddress());
        assertEquals(scionSrc.getAddress(), address.getAddress());
        assertEquals(scionSrc.getPort(), address.getPort());
      } finally {
        ScionService.closeDefault();
        MockNetwork.stopTiny();
      }
    } finally {
      if (service != null) {
        service.close();
      }
    }
  }

  @Test
  void receive_correctSrc_emptyPath() throws IOException {
    // In case of an empty path, the ResponsePath's first hop is the IP src address
    InetSocketAddress underLaySrc =
        new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 0, 123, 123}), 12345);

    ScionPacketInspector spi = new ScionPacketInspector();
    spi.read(ByteBuffer.wrap(ExamplePacket.PACKET_BYTES_SERVER_E2E_PING));
    spi.getPathHeaderScion().reset(); // set path to []
    InetAddress scionSrcIP = spi.getScionHeader().getSrcHostAddress();
    int scionSrcPort = spi.getOverlayHeaderUdp().getSrcPort();
    InetSocketAddress scionSrc = new InetSocketAddress(scionSrcIP, scionSrcPort);

    MockDatagramChannel mdc = MockDatagramChannel.open();
    mdc.setReceiveCallback(
        buf -> {
          spi.writePacket(buf, new byte[0]);
          return underLaySrc;
        });

    MockNetwork.startTiny();
    ScionService service = null;
    try {
      service = Scion.newServiceWithTopologyFile("topologies/tiny4/ASff00_0_112/topology.json");
      try (ScionDatagramChannel channel = ScionDatagramChannel.open(service, mdc)) {
        ScionSocketAddress address = channel.receive(ByteBuffer.allocate(1000));
        assertEquals(underLaySrc, address.getPath().getFirstHopAddress());
        assertNotEquals(scionSrc, address.getPath().getFirstHopAddress());
      } finally {
        ScionService.closeDefault();
        MockNetwork.stopTiny();
      }
    } finally {
      if (service != null) {
        service.close();
      }
    }
  }

  @Test
  void errorOnNullService() throws IOException {
    // check that operations that require a ScionService fail adequately.
    // E.g. sending with a RequestPath.
    ScionService.closeDefault();
    RequestPath clientPath = (RequestPath) ExamplePacket.PATH;
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(null)) {
      channel.bind(new InetSocketAddress("127.0.0.1", 12345));
      ByteBuffer bb = ByteBuffer.allocate(0);
      Exception e = assertThrows(IllegalStateException.class, () -> channel.send(bb, clientPath));
      assertEquals("This operation requires a ScionService.", e.getMessage());
    }
  }
}
