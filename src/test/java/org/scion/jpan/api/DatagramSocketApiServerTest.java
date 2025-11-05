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

class DatagramSocketApiServerTest {

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
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      assertEquals(Scion.defaultService(), socket.getService());
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
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      assertEquals(service, socket.getService());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void open_withNullService() throws IOException {
    // check that open() (without service argument) does not internally create a ScionService.
    ScionService.closeDefault();
    try (ScionDatagramSocket socket = ScionDatagramSocket.newBuilder().service(null).open()) {
      assertNull(socket.getService());
    }
  }

  @Test
  void open_withNullService2() throws IOException {
    MockNetwork.startTiny();
    // check that open() (without service argument) does not internally use a ScionService, even if
    // one exists.
    ScionService.closeDefault();
    Scion.defaultService();
    try (ScionDatagramSocket socket = ScionDatagramSocket.newBuilder().service(null).open()) {
      assertNull(socket.getService());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void bind_withNullService() throws IOException {
    // check that bind() does not internally require a ScionService.
    ScionService.closeDefault();
    try (ScionDatagramSocket socket = ScionDatagramSocket.newBuilder().service(null).open()) {
      socket.bind(new InetSocketAddress("127.0.0.1", 12345));
      assertNull(socket.getService());
    }
  }

  @Test
  void send_withNullService() throws IOException {
    // check that send(Path) does not internally require a ScionService.
    ScionService.closeDefault();

    SocketAddress addr = new InetSocketAddress("127.0.0.1", 1);
    MockDatagramChannel mock = MockDatagramChannel.open();
    mock.setReceiveCallback(
        buf -> {
          buf.put(ExamplePacket.PACKET_BYTES_SERVER_E2E_PING);
          return addr;
        });
    mock.setSendCallback((buf, addr2) -> 42);

    try (ScionDatagramSocket socket =
        ScionDatagramSocket.newBuilder().service(null).channel(mock).open()) {
      socket.bind(new InetSocketAddress("127.0.0.1", 12345));
      assertNull(socket.getService());
      // First, we need to receive a packet.
      DatagramPacket buffer = new DatagramPacket(new byte[1000], 1000);
      socket.receive(buffer);

      // Now, send it.
      socket.send(buffer);
      assertNull(socket.getService());
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
    try (ScionDatagramSocket socket =
        ScionDatagramSocket.newBuilder().service(null).channel(mock).open()) {
      socket.bind(new InetSocketAddress("127.0.0.1", 12345));
      assertNull(socket.getService());
      DatagramPacket packet = new DatagramPacket(new byte[100], 0);
      socket.receive(packet);
      assertNull(socket.getService());
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
      try (ScionDatagramSocket socket =
          ScionDatagramSocket.newBuilder().service(service).channel(mdc).open()) {
        DatagramPacket buffer = new DatagramPacket(new byte[1000], 1000);
        socket.receive(buffer);
        InetSocketAddress address = (InetSocketAddress) buffer.getSocketAddress();
        Path path = socket.getCachedPath(address);
        assertEquals(brAddress, path.getFirstHopAddress());
        assertNotEquals(scionSrc, path.getFirstHopAddress());
        assertNotEquals(underLaySrc, path.getFirstHopAddress());
        assertEquals(scionSrc.getAddress(), path.getRemoteAddress());
        assertEquals(scionSrc.getPort(), path.getRemotePort());
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
      try (ScionDatagramSocket socket =
          ScionDatagramSocket.newBuilder().service(service).channel(mdc).open()) {
        DatagramPacket buffer = new DatagramPacket(new byte[1000], 1000);
        socket.receive(buffer);
        Path path = socket.getCachedPath(((InetSocketAddress) buffer.getSocketAddress()));
        assertEquals(underLaySrc, path.getFirstHopAddress());
        assertNotEquals(scionSrc, path.getFirstHopAddress());
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
}
