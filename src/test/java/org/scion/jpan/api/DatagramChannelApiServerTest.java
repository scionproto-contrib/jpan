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
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
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
}
