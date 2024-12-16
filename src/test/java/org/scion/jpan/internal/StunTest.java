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
import java.util.function.Predicate;
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
  void testScionProtoBorderRouter() throws UnknownHostException {
    // 3 packets recorded from Golang border router STUN using the "default" topology and
    // ScmpDemoDefault which starts out in 1-ff00:0:131.
    // BR packets include:
    // - FINGERPRINT
    // - no SOFTWARE
    //
    STUN.TransactionID id0 = STUN.TransactionID.from(0x0aeb0481, 0x4e5d718a, 0x5146039f);
    STUN.TransactionID id1 = STUN.TransactionID.from(0xab1c5374, 0xba09d606, 0x8c32c486);
    STUN.TransactionID id2 = STUN.TransactionID.from(0xcc6e01dd, 0xe71e2906, 0xcf6b2856);
    int[] ia = {
      0x01, 0x01, 0x00, 0x0c, 0x21, 0x12, 0xa4, 0x42,
      0x0a, 0xeb, 0x04, 0x81, 0x4e, 0x5d, 0x71, 0x8a,
      0x51, 0x46, 0x03, 0x9f, 0x00, 0x20, 0x00, 0x08,
      0x00, 0x01, 0x58, 0x0b, 0x5e, 0x12, 0xa4, 0x43
    };
    int[] in2 = {
      0x01, 0x01, 0x00, 0x0c, 0x21, 0x12, 0xa4, 0x42,
      0xab, 0x1c, 0x53, 0x74, 0xba, 0x09, 0xd6, 0x06,
      0x8c, 0x32, 0xc4, 0x86, 0x00, 0x20, 0x00, 0x08,
      0x00, 0x01, 0x58, 0x0b, 0x5e, 0x12, 0xa4, 0x43
    };
    int[] in3 = {
      0x01, 0x01, 0x00, 0x0c, 0x21, 0x12, 0xa4, 0x42,
      0xcc, 0x6e, 0x01, 0xdd, 0xe7, 0x1e, 0x29, 0x06,
      0xcf, 0x6b, 0x28, 0x56, 0x00, 0x20, 0x00, 0x08,
      0x00, 0x01, 0x58, 0x0b, 0x5e, 0x12, 0xa4, 0x43
    };
    test(ia, id0);
    test(in2, id1);
    test(in3, id2);
  }

  void test(int[] ia, STUN.TransactionID id) throws UnknownHostException {

    ByteBuffer bb = ByteBuffer.allocate(100);
    for (int i : ia) {
      bb.put(ByteUtil.toByte(i));
    }
    bb.flip();

    assertTrue(STUN.isStunPacket(bb, id));
    assertTrue(STUN.isStunResponse(bb, id));

    ByteUtil.MutInt handled = new ByteUtil.MutInt(0);
    Predicate<STUN.TransactionID> idHandler =
        txID -> {
          assertEquals(txID, id);
          handled.set(1);
          return true;
        };
    ByteUtil.MutRef<STUN.TransactionID> txIdOut = new ByteUtil.MutRef<>();
    ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
    InetSocketAddress addr = STUN.parseResponse(bb, idHandler, txIdOut, error);
    assertNotNull(addr);
    assertEquals(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), addr.getAddress());
    assertEquals(31001, addr.getPort());

    assertNull(error.get());
    assertEquals(id, txIdOut.get());
    assertEquals(1, handled.get());
  }
}
