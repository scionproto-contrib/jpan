// Copyright 2026 ETH Zurich
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

package org.scion.jpan.internal.snap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.testutil.MockEchoServer;
import org.scion.jpan.testutil.MockSnapService;

class SnapTunnelTest {

  private MockSnapService mockSnapService;

  @BeforeEach
  void beforeEach() {
    mockSnapService = MockSnapService.start(MockSnapService.ADDRESS);
  }

  @AfterEach
  void afterEach() {
    mockSnapService.close();
  }

  @Test
  void sendPacket_returnsScionByteCount_notWireGuardWireSize() throws IOException {
    SnapTunnel session =
        new SnapTunnel(
            null,
            mockSnapService.getDataplaneAddress(),
            mockSnapService.getStaticPublicKey(),
            null /* no HTTP control client needed for handshake */);

    byte[] scionPacket = new byte[123];
    int sent = session.sendPacket(scionPacket);

    // Before the fix, this returned scionPacket.length + 32 (the WireGuard data-header + AEAD
    // tag overhead added by encrypt()) -- the size of the packet actually on the wire, not the
    // number of SCION-level bytes the caller asked to send. Callers such as
    // ScionDatagramChannel.send() subtract their own header size from this return value to report
    // "payload bytes sent" to the API user, so leaking the WireGuard overhead here made send()
    // report too many bytes.
    assertEquals(scionPacket.length, sent);
  }

  @Test
  void sendPacket_zeroLengthPacket_returnsZero() throws IOException {
    SnapTunnel session =
        new SnapTunnel(
            null,
            mockSnapService.getDataplaneAddress(),
            mockSnapService.getStaticPublicKey(),
            null /* no HTTP control client needed for handshake */);

    int sent = session.sendPacket(new byte[0]);

    assertEquals(0, sent);
  }

  @Test
  void receivePacket_decryptsRealDataPacketFromDataPlane() throws IOException {
    // Coverage gap this closes: every other SnapTunnel test only ever sends (encrypt()) -- none
    // ever receives a real post-handshake WireGuard data packet, so decrypt() was never actually
    // exercised. MockSnapService relays every data packet through an internal MockEchoServer by
    // default (no relayTo() call needed), which lets receivePacket() drive the real AEAD-decrypt
    // path on a genuine round trip instead of just the handshake crypto.
    SnapTunnel session =
        new SnapTunnel(
            null,
            mockSnapService.getDataplaneAddress(),
            mockSnapService.getStaticPublicKey(),
            null /* no HTTP control client needed for handshake */);

    // Triggers the handshake, then sends one data packet so the mock has a peer index to reply to.
    byte[] sent = {1, 2, 3};
    session.sendPacket(sent);

    // Wait for the mock's relayed reply, then read and decrypt it.
    session.awaitReadable(2000);
    ByteBuffer received = ByteBuffer.allocate(1024);
    InetSocketAddress from = session.receivePacket(received);

    assertNotNull(from, "expected a relayed reply from the mock SNAP dataplane");
    received.flip();
    byte[] payload = new byte[received.remaining()];
    received.get(payload);
    assertArrayEquals(sent, payload);
  }

  @Test
  void receivePacket_relaysThroughPlainMirrorServer() throws IOException {
    // Unlike the default-echo test above, this proves the mock can be pointed at a *different*
    // plain (non-SNAP) UDP mirror via relayTo(): the mock decrypts what the client sent, forwards
    // the plaintext verbatim to that mirror, and re-encrypts whatever it sends back -- so the
    // bytes received here must match exactly what was sent, not a fixed constant.
    try (MockEchoServer mirror = MockEchoServer.start()) {
      mockSnapService.relayTo(mirror.getAddress());

      SnapTunnel session =
          new SnapTunnel(
              null,
              mockSnapService.getDataplaneAddress(),
              mockSnapService.getStaticPublicKey(),
              null /* no HTTP control client needed for handshake */);

      byte[] sent = {9, 8, 7, 6, 5};
      session.sendPacket(sent);

      session.awaitReadable(2000);
      ByteBuffer received = ByteBuffer.allocate(1024);
      InetSocketAddress from = session.receivePacket(received);

      assertNotNull(from, "expected a relayed reply via the mirror server");
      received.flip();
      byte[] payload = new byte[received.remaining()];
      received.get(payload);
      assertArrayEquals(sent, payload);
    }
  }
}
