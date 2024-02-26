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

package org.scion.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.testutil.ExamplePacket;
import org.scion.testutil.MockDNS;
import org.scion.testutil.MockDaemon;
import org.scion.testutil.MockDatagramChannel;

/**
 * Test that typical "server" operations do not require a ScionService.
 *
 * <p>This is service-less operation is required for servers that should not use (or may not have)
 * access to a daemon or control service. Service-less operation is mainly implemented to ensure
 * good server performance by avoiding costly network calls to daemons etc.
 */
class DatagramChannelApiServerTest {

  private static final int dummyPort = 44444;

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
    // check that open() (without service argument) does not internally require a ScionService.
    try (DatagramChannel channel = DatagramChannel.open()) {
      assertNull(channel.getService());
    }
  }

  @Test
  void bind_withoutService() throws IOException {
    // check that open() (without service argument) does not internally require a ScionService.
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.bind(new InetSocketAddress("127.0.0.1", 12345));
      assertNull(channel.getService());
    }
  }

  @Test
  void send_withoutService() throws IOException {
    // check that send(ResponsePath) does not internally require a ScionService.
    try (DatagramChannel channel = DatagramChannel.open()) {
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
      Path p2 = channel.send(ByteBuffer.allocate(0), path);
      assertNull(channel.getService());
      assertEquals(path, p2);
    }
  }

  @Test
  void receive_withoutService() throws IOException {
    // check that receive() does not internally require a ScionService.
    SocketAddress addr = new InetSocketAddress("127.0.0.1", 12345);
    MockDatagramChannel mock = MockDatagramChannel.open();
    mock.setReceiveCallback(
        buf -> {
          buf.put(ExamplePacket.PACKET_BYTES_SERVER_E2E_PING);
          return addr;
        });
    try (DatagramChannel channel = DatagramChannel.open(null, mock)) {
      assertNull(channel.getService());
      ByteBuffer buffer = ByteBuffer.allocate(100);
      Path path = channel.receive(buffer);
      assertNull(channel.getService());
      assertEquals(addr, path.getFirstHopAddress());
    }
  }
}
