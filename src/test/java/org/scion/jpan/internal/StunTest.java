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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.demo.util.ToStringUtil;
import org.scion.jpan.testutil.MockNetwork;

@Disabled
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
    STUN.isStunPacket(bb, null);

    ByteBuffer bb2 = ByteBuffer.allocate(100);
    STUN.writeRequest(bb2);
    System.out.println("testGo(): ");
    System.out.println(ToStringUtil.toStringHex(bb2.array())); // TODO
    int[] ia2 = {
      0x00, 0x01, 0x00, 0x24, 0x42, 0xa4, 0x12, 0x21,
      0x9e, 0xa1, 0x70, 0xaf, 0x9f, 0x02, 0xe6, 0xe9,
      0xc6, 0x32, 0xce, 0xa8, 0x80, 0x22, 0x0, 0x18,
      0x6a, 0x70, 0x61, 0x6e, 0x2e, 0x73, 0x63, 0x69,
      0x6f, 0x6e, 0x2e, 0x6f, 0x72, 0x67, 0x20, 0x76,
      0x30, 0x2e, 0x34, 0x2e, 0x30, 0x00, 0x00, 0x00,
      0x80, 0x28, 0x00, 0x04, 0xd1, 0xf9, 0x9a, 0x3b
    };
  }

  // No fingerprint, reversed Magic
  int[] ia3 = {
    0x00, 0x01, 0x00, 0x1c, 0x42, 0xa4, 0x12, 0x21,
    0xc5, 0xa5, 0x0f, 0x38, 0xb2, 0x72, 0x61, 0x24,
    0x37, 0x3c, 0x68, 0x20, 0x80, 0x22, 0x00, 0x18,
    0x6a, 0x70, 0x61, 0x6e, 0x2e, 0x73, 0x63, 0x69,
    0x6f, 0x6e, 0x2e, 0x6f, 0x72, 0x67, 0x20, 0x76,
    0x30, 0x2e, 0x34, 0x2e, 0x30, 0x0, 0x0, 0x0
  };

  // No fingerprint, reversed MAGIC, no SOFTWARE
  int[] ia4 = {
    0x00, 0x01, 0x00, 0x00, 0x42, 0xa4, 0x12, 0x21,
    0xbd, 0xa3, 0xc7, 0xe0, 0x22, 0xf0, 0x48, 0x77,
    0x6d, 0x41, 0xaf, 0xb5
  };

  // FIngerprint, reversed magic, no SOFTWARE
  int[] ia5 = {
    0x00, 0x01, 0x00, 0x08, 0x42, 0xa4, 0x12, 0x21,
    0xc0, 0x2d, 0x7c, 0x49, 0x0b, 0x94, 0x55, 0x2c,
    0xde, 0x52, 0xed, 0xb8, 0x80, 0x28, 0x00, 0x04,
    0x7b, 0x09, 0x4e, 0xb2
  };
  // TODO continue:
  // - Two initial bytes are correct.
  // - Length seems correct
  // - Includes FINGERPRINT but no SOFTWARE

  // - MAGIC COOKIE is REVERSED
  // - PORT-XOR uses 0x2112
  // - TODO test: IPv6 is probably broken??? Or just MAGIC reversed?

  // TODO next: verify FINGERPRINT calculation + byte order
}
