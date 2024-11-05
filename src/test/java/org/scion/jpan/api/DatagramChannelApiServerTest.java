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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockDaemon;
import org.scion.jpan.testutil.MockDatagramChannel;
import org.scion.jpan.testutil.MockNetwork;

/**
 * Test that typical "server" operations do not require a ScionService.
 *
 * <p>This is service-less operation is required for servers that should not use (or may not have)
 * access to a daemon or control service. Service-less operation is mainly implemented to ensure
 * good server performance by avoiding costly network calls to daemons etc.
 */
class DatagramChannelApiServerTest {

  @BeforeEach
  public void beforeEach() throws IOException {
    MockDaemon.closeDefault();
    MockDNS.clear();
    ScionService.closeDefault();
  }

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
      assertNull(channel.getService());
      ByteBuffer buffer = ByteBuffer.allocate(100);
      ScionSocketAddress responseAddress = channel.receive(buffer);
      assertNull(channel.getService());
      assertEquals(addr, responseAddress.getPath().getFirstHopAddress());
    }
  }

  @Disabled
  @Test
  void receive_correctSrc_divergentBR() throws IOException {
    // Check that the ResponsePath's first hop is looked up by from the border router table
    byte[] scionSrcBytes = {10, 0, 123, 123};
    InetSocketAddress src = new InetSocketAddress(InetAddress.getByAddress(scionSrcBytes), 12345);

    InetSocketAddress brAddress =
        new InetSocketAddress(
            InetAddress.getByAddress(new byte[] {192 - 256, 168 - 256, 42, 42}), 42424);

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
          return src;
        });

    MockNetwork.startTiny();
    ScionService service =
        Scion.newServiceWithTopologyFile("topologies/scionproto-tiny/topology-112.json");
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(service, mdc)) {
      ScionSocketAddress address = channel.receive(ByteBuffer.allocate(1000));
      assertEquals(brAddress, address.getPath().getFirstHopAddress());
      assertNotEquals(scionSrc, address.getPath().getFirstHopAddress());
      assertNotEquals(src, address.getPath().getFirstHopAddress());
      assertEquals(src, address);
    } finally {
      ScionService.closeDefault();
      MockNetwork.stopTiny();
    }

    fail();
  }

  @Disabled
  @Test
  void receive_correctSrc_emptyPath() throws IOException {
    // In case of an empty path, the ResponsePath's first hop is the IP src address
    byte[] scionSrcBytes = {10, 0, 123, 123};
    InetSocketAddress src =
        new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 0, 123, 123}), 12345);

    ScionPacketInspector spi = new ScionPacketInspector();
    spi.read(ByteBuffer.wrap(ExamplePacket.PACKET_BYTES_SERVER_E2E_PING));
    spi.getScionHeader().setSrcHostAddress(scionSrcBytes);
    spi.getPathHeaderScion().reset();
    InetAddress scionSrcIP = spi.getScionHeader().getSrcHostAddress();
    int scionSrcPort = spi.getOverlayHeaderUdp().getSrcPort();
    InetSocketAddress scionSrc = new InetSocketAddress(scionSrcIP, scionSrcPort);

    MockDatagramChannel mdc = MockDatagramChannel.open();
    mdc.setReceiveCallback(
        buf -> {
          spi.writePacket(buf, new byte[0]);
          return src;
        });

    MockNetwork.startTiny();
    ScionService service =
        Scion.newServiceWithTopologyFile("topologies/scionproto-tiny/topology-112.json");
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(service, mdc)) {
      ScionSocketAddress address = channel.receive(ByteBuffer.allocate(1000));
      assertEquals(src, address.getPath().getFirstHopAddress());
      assertEquals(scionSrc, address.getPath().getFirstHopAddress());
      assertEquals(src, address);
    } finally {
      ScionService.closeDefault();
      MockNetwork.stopTiny();
    }

    fail();
  }
}
