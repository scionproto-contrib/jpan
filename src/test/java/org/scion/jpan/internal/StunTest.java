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

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import org.junit.jupiter.api.Test;
import org.scion.jpan.testutil.MockNetwork;

class StunTest {

  @Test
  void testBorderRouter_Old() throws IOException {
    try (DatagramChannel channel = DatagramChannel.open()) {
      MockNetwork.startTiny();

      // send
      InetSocketAddress br = MockNetwork.getBorderRouterAddress1();
      ByteBuffer out = ByteBuffer.allocate(1000);
      STUN.TransactionID id = STUN.writeRequest(out);
      out.flip();
      channel.send(out, br);

      // receive
      ByteBuffer in = ByteBuffer.allocate(1000);
      InetSocketAddress server = (InetSocketAddress) channel.receive(in);
      assertEquals(br, server);
      in.flip();

      // check
      ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
      InetSocketAddress external = STUN.parseResponse(in, id::equals, error);
      assertNull(error.get(), error.get());

      // We compare only the port, the IP may differ ("any" vs localhost, etc...)
      assertEquals(((InetSocketAddress) channel.getLocalAddress()).getPort(), external.getPort());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void testBorderRouter() throws IOException {
    try (DatagramChannel channel = DatagramChannel.open()) {
      MockNetwork.startTiny();

      // send
      InetSocketAddress br = MockNetwork.getBorderRouterAddress1();
      ByteBuffer out = ByteBuffer.allocate(1000);
      STUN.TransactionID id = STUN.writeRequest(out);
      out.flip();
      channel.send(out, br);

      // receive
      ByteBuffer in = ByteBuffer.allocate(1000);
      InetSocketAddress server = (InetSocketAddress) channel.receive(in);
      assertEquals(br, server);
      in.flip();

      // check
      boolean isSTUN = STUN.isStunPacket(in, id);
      assertTrue(isSTUN);

      InetSocketAddress external = STUN.parseResponse(in, id);
      // We compare only the port, the IP may differ ("any" vs localhost, etc...)
      assertEquals(((InetSocketAddress) channel.getLocalAddress()).getPort(), external.getPort());
    } finally {
      MockNetwork.stopTiny();
    }
  }
}
