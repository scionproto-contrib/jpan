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
import java.util.Arrays;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.scion.jpan.testutil.MockNetwork;

class StunTest {

  private static final int[] error400 = {
    0x1, 0x11, 0x0, 0x14, 0x21, 0x12, 0xa4, 0x42,
    0xfa, 0x62, 0xc1, 0xd8, 0x2a, 0x7, 0xd2, 0xbb,
    0x13, 0x16, 0x97, 0xa6, 0x0, 0x9, 0x0, 0x10,
    0x0, 0x0, 0x4, 0x0, 0x42, 0x61, 0x64, 0x20,
    0x52, 0x65, 0x71, 0x75, 0x65, 0x73, 0x74, 0x20,
  };

  @Test
  void smokeTest() throws UnknownHostException {
    ByteBuffer request = ByteBuffer.allocate(1000);
    STUN.TransactionID id = STUN.writeRequest(request);
    request.flip();
    assertTrue(STUN.isStunRequest(request));

    STUN.TransactionID id2 = STUN.parseRequest(request);
    assertNotNull(id2);
    assertEquals(id, id2);
    InetSocketAddress src =
        new InetSocketAddress(InetAddress.getByAddress(new byte[] {123, 12, 13, 23}), 54321);
    ByteBuffer response = ByteBuffer.allocate(1000);
    STUN.writeResponse(response, id2, src);
    response.flip();

    assertTrue(STUN.isStunResponse(response, id));
    ByteUtil.MutRef<STUN.TransactionID> receivedID = new ByteUtil.MutRef<>();
    ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
    InetSocketAddress src2 =
        STUN.parseResponse(response, txID -> txID.equals(id), receivedID, error);
    assertNull(error.get());
    assertEquals(id, receivedID.get());
    assertEquals(src, src2);
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

      // parse
      ByteUtil.MutRef<STUN.TransactionID> txId = new ByteUtil.MutRef<>();
      ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
      InetSocketAddress external = STUN.parseResponse(in, id::equals, txId, error);
      assertNull(error.get(), error.get());
      assertNotNull(external);

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
    testRecordedResponse(ia, id0);
    testRecordedResponse(in2, id1);
    testRecordedResponse(in3, id2);
  }

  void testRecordedResponse(int[] ia, STUN.TransactionID id) throws UnknownHostException {
    ByteBuffer bb = toByteBuffer(ia);

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

  void testRecordedResponseNoCheck(int[] ia) {
    ByteBuffer bb = toByteBuffer(ia);

    STUN.TransactionID id = STUN.TransactionID.from(bb.getInt(8), bb.getInt(12), bb.getInt(16));

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
    assertTrue(addr.getPort() > 32000);

    assertNull(error.get());
    assertEquals(id, txIdOut.get());
    assertEquals(1, handled.get());
  }

  void testRecordedErrorNoCheck(int[] ia, String errorMsg) {
    ByteBuffer bb = toByteBuffer(ia);

    STUN.TransactionID id = STUN.TransactionID.from(bb.getInt(8), bb.getInt(12), bb.getInt(16));

    assertTrue(STUN.isStunPacket(bb, id));

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
    assertNull(addr);

    assertNotNull(error.get());
    assertTrue(error.get().contains(errorMsg), error.get());
    assertEquals(id, txIdOut.get());
    assertEquals(1, handled.get());
  }

  void testErrorSimple(int[] ia, String errorMsg) {
    ByteBuffer bb = toByteBuffer(ia);

    ByteUtil.MutRef<STUN.TransactionID> txIdOut = new ByteUtil.MutRef<>();
    ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
    assertNull(STUN.parseResponse(bb, x -> true, txIdOut, error));

    assertNotNull(error.get());
    assertTrue(error.get().contains(errorMsg), error.get());
  }

  private static ByteBuffer toByteBuffer(int[] ia) {
    ByteBuffer bb = ByteBuffer.allocate(200);
    for (int i : ia) {
      bb.put(ByteUtil.toByte(i));
    }
    bb.flip();
    return bb;
  }

  @Test
  void testResponsesFromPublicServers() {
    int[] ba = {
      0x1, 0x1, 0x0, 0x44, 0x21, 0x12, 0xa4, 0x42,
      0x28, 0xd2, 0x66, 0x9c, 0x7e, 0xdb, 0x56, 0xf,
      0x36, 0xc9, 0x31, 0xb1, 0x0, 0x1, 0x0, 0x8,
      0x0, 0x1, 0x82, 0x75, 0x81, 0x84, 0xe6, 0x49,
      0x0, 0x4, 0x0, 0x8, 0x0, 0x1, 0xd, 0x96,
      0x42, 0x33, 0x80, 0xb, 0x0, 0x5, 0x0, 0x8,
      0x0, 0x1, 0xd, 0x97, 0x42, 0x33, 0x80, 0xc,
      0x80, 0x20, 0x0, 0x8, 0x0, 0x1, 0xa3, 0x67,
      0xa0, 0x96, 0x42, 0xb, 0x80, 0x22, 0x0, 0x10,
      0x56, 0x6f, 0x76, 0x69, 0x64, 0x61, 0x2e, 0x6f,
      0x72, 0x67, 0x20, 0x30, 0x2e, 0x39, 0x36, 0x0,
    };
    //  [main] INFO org.scion.jpan.internal.STUN - MAPPED_ADDRESS: /129.132.230.73:33397
    //  [main] INFO org.scion.jpan.internal.STUN - SOURCE_ADDRESS: /66.51.128.11:3478
    //  [main] INFO org.scion.jpan.internal.STUN - CHANGED_ADDRESS: /66.51.128.12:3479
    //  [main] INFO org.scion.jpan.internal.STUN - OLD_XOR_MAPPED_ADDRESS: /129.132.230.73:33397
    //  [main] INFO org.scion.jpan.internal.STUN - SOFTWARE: Vovida.org 0.96
    testRecordedResponseNoCheck(ba);
  }

  @Test
  void testResponseFromPublicServers2() {
    int[] ba = {
      0x1, 0x1, 0x0, 0x58, 0x21, 0x12, 0xa4, 0x42,
      0x87, 0x16, 0xbe, 0x65, 0x8c, 0xec, 0x16, 0xaf,
      0xa8, 0x24, 0x50, 0x45, 0x0, 0x20, 0x0, 0x8,
      0x0, 0x1, 0x94, 0xe4, 0xa0, 0x96, 0x42, 0xb,
      0x0, 0x1, 0x0, 0x8, 0x0, 0x1, 0xb5, 0xf6,
      0x81, 0x84, 0xe6, 0x49, 0x80, 0x2b, 0x0, 0x8,
      0x0, 0x1, 0xd, 0x96, 0xb9, 0x7d, 0xb4, 0x46,
      0x80, 0x2c, 0x0, 0x8, 0x0, 0x1, 0xd, 0x97,
      0xb9, 0x7d, 0xb4, 0x47, 0x80, 0x22, 0x0, 0x1a,
      0x43, 0x6f, 0x74, 0x75, 0x72, 0x6e, 0x2d, 0x34,
      0x2e, 0x35, 0x2e, 0x31, 0x2e, 0x31, 0x20, 0x27,
      0x64, 0x61, 0x6e, 0x20, 0x45, 0x69, 0x64, 0x65,
      0x72, 0x27, 0x0, 0x0, 0x80, 0x28, 0x0, 0x4,
      0x80, 0x4b, 0xc2, 0x43,
    };
    //  [main] INFO org.scion.jpan.internal.STUN - XOR_MAPPED_ADDRESS: /129.132.230.73:46582
    //  [main] INFO org.scion.jpan.internal.STUN - MAPPED_ADDRESS: /129.132.230.73:46582
    //  [main] INFO org.scion.jpan.internal.STUN - RESPONSE_ORIGIN: /185.125.180.70:3478
    //  [main] INFO org.scion.jpan.internal.STUN - OTHER_ADDRESS: /185.125.180.71:3479
    //  [main] INFO org.scion.jpan.internal.STUN - SOFTWARE: Coturn-4.5.1.1 'dan Eider'
    //  [main] INFO org.scion.jpan.internal.STUN - FINGERPRINT: match = true
    testRecordedResponseNoCheck(ba);
  }

  @Test
  void testResponseFromPublicServers_Error300() {
    testErrorMessage(3, 0, "Try Alternate");
  }

  @Test
  void testResponseFromPublicServers_Error400() {
    //  [main] ERROR org.scion.jpan.internal.STUN - 400: Bad Request
    //  Bad Request: The request was malformed.  The client SHOULD NOT
    //  retry the request without modification from the previous
    //  attempt.  The server may not be able to generate a valid
    //  MESSAGE-INTEGRITY for this error, so the client MUST NOT expect
    //  a valid MESSAGE-INTEGRITY attribute on this response.
    testRecordedErrorNoCheck(error400, "Bad Request");
  }

  @Test
  void testResponseFromPublicServers_Error401() {
    testErrorMessage(4, 1, "Unauthorized");
  }

  @Test
  void testResponseFromPublicServers_Error420() {
    testErrorMessage(4, 20, "Unknown Attribute");
  }

  @Test
  void testResponseFromPublicServers_Error438() {
    testErrorMessage(4, 38, "Stale Nonce");
  }

  @Test
  void testResponseFromPublicServers_Error500() {
    testErrorMessage(5, 0, "Server Error");
  }

  @Test
  void testResponseFromPublicServers_Errorxxx() {
    testErrorMessage(2, 42, "Unknown error 242");
  }

  private void testErrorMessage(int id1, int id2, String expected) {
    int[] ba = Arrays.copyOf(error400, error400.length);
    ba[26] = id1;
    ba[27] = id2;
    testRecordedErrorNoCheck(ba, expected);
  }

  @Test
  void testCorrupted_Type0000() {
    int[] ba = Arrays.copyOf(error400, error400.length);
    // RESERVED_0
    ba[21] = 0;
    ba[22] = 0;
    testErrorSimple(ba, "not implemented");
  }

  @Test
  void testCorrupted_length_noHeader() {
    int[] ba = Arrays.copyOf(error400, 11);
    testErrorSimple(ba, "packet too short");
  }

  @Test
  void testCorrupted_length_too_short() {
    int[] ba = Arrays.copyOf(error400, 21);
    testErrorSimple(ba, "invalid length");
  }

  @Test
  void testCorrupted_length_too_long() {
    int[] ba = Arrays.copyOf(error400, 199);
    testErrorSimple(ba, "invalid length");
  }

  @Test
  void testCorrupted_MagicNumber() {
    int[] ba = Arrays.copyOf(error400, error400.length);
    ba[4] = 123;
    testErrorSimple(ba, "invalid MAGIC_COOKIE");
  }

  @Test
  void testCorrupted_TxID() {
    int[] ba = Arrays.copyOf(error400, error400.length);
    ByteBuffer bb = toByteBuffer(ba);

    ByteUtil.MutRef<STUN.TransactionID> txIdOut = new ByteUtil.MutRef<>();
    ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
    assertNull(STUN.parseResponse(bb, x -> false, txIdOut, error));
    assertNotNull(error.get());
    assertTrue(error.get().contains("TxID validation failed"), error.get());
  }
}
