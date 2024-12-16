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

  @Test
  void testGo() {
    // Packet recorded from Golang border router STUN:
    // - Includes FINGERPRINT but no SOFTWARE
    int[] ia = {
      0x00, 0x01, 0x00, 0x08, 0x21, 0x12, 0xa4, 0x42,
      0xaf, 0x58, 0x9f, 0x77, 0xaa, 0x35, 0x89, 0xc4,
      0xce, 0x83, 0x82, 0xd7, 0x80, 0x28, 0x00, 0x04,
      0x5e, 0xc8, 0x68, 0x6e
    };
    ByteBuffer bb = ByteBuffer.allocate(100);
    for (int i : ia) {
      bb.put(ByteUtil.toByte(i));
    }
    bb.flip();
    STUN.TransactionID id = STUN.TransactionID.from(0xaf589f77, 0xaa3589c4, 0xce8382d7);
    assertTrue(STUN.isStunPacket(bb, id));
  }
}
