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

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.testutil.MockSnapService;

/**
 * Whitebox coverage for {@link SnapTunnel} branches the "everything works" round trips in {@code
 * SnapTunnelTest} and the {@code org.scion.jpan.api} SNAP tests never exercise: {@link
 * SnapTunnel#decrypt}'s not-established/unparseable/receiver-index-mismatch/AEAD-failure branches,
 * {@link SnapTunnel#receivePacket}'s nothing-available/wrong-source/undecryptable branches, and
 * every branch of {@link SnapTunnel#parseSnapSocketAddress}.
 */
class SnapTunnelWhiteboxTest {

  private MockSnapService mockSnapService;

  @BeforeEach
  void beforeEach() {
    mockSnapService = MockSnapService.start(MockSnapService.ADDRESS);
  }

  @AfterEach
  void afterEach() {
    mockSnapService.close();
  }

  /** No HTTP control client needed for the handshake in any of these tests. */
  private SnapTunnel newSession() {
    return new SnapTunnel(
        null, mockSnapService.getDataplaneAddress(), mockSnapService.getStaticPublicKey(), null);
  }

  // -------------------------------------------------------------------------
  // decrypt()
  // -------------------------------------------------------------------------

  @Test
  void decrypt_notEstablished_returnsNull() {
    // No network I/O happens before the "established" check, so this doesn't even need the mock
    // to be reachable.
    SnapTunnel session =
        new SnapTunnel(
            null, new InetSocketAddress(InetAddress.getLoopbackAddress(), 1), new byte[32], null);

    assertNull(session.decrypt(new byte[32]));
  }

  @Test
  void decrypt_unparseablePacket_returnsNull() {
    SnapTunnel session = newSession();
    session.ensureConnected();

    // Shorter than WireGuardPacket's 16-byte data-packet header: parseDataPacket() returns null.
    assertNull(session.decrypt(new byte[10]));
  }

  @Test
  void decrypt_receiverIndexMismatch_returnsNull() {
    SnapTunnel session = newSession();
    session.ensureConnected();

    // Well-formed data packet, but addressed to a receiver index that cannot possibly be this
    // session's -- the check must reject it before ever touching AEAD.
    byte[] fakePacket = WireGuardPacket.buildDataPacket(Integer.MAX_VALUE, 0, new byte[16]);
    assertNull(session.decrypt(fakePacket));
  }

  @Test
  void decrypt_aeadFailure_returnsNull() throws Exception {
    SnapTunnel session = newSession();
    session.sendPacket(new byte[] {1, 2, 3});
    session.awaitReadable(2000);

    // Capture the mock's real, validly-encrypted reply (correct receiver index and AEAD tag) off
    // the wire directly, then corrupt only the ciphertext -- proving that the AEAD check itself,
    // not the receiver-index check above, is what rejects the tampered packet.
    ByteBuffer raw = ByteBuffer.allocate(4096);
    InetSocketAddress from = (InetSocketAddress) session.transportChannel().receive(raw);
    assertNotNull(from, "expected the mock's encrypted reply on the wire");
    raw.flip();
    byte[] tampered = new byte[raw.remaining()];
    raw.get(tampered);
    tampered[tampered.length - 1] ^= 0xFF;

    assertNull(session.decrypt(tampered));
  }

  // -------------------------------------------------------------------------
  // receivePacket()
  // -------------------------------------------------------------------------

  @Test
  void receivePacket_noDataAvailable_returnsNullWithoutBlocking() throws Exception {
    SnapTunnel session = newSession();
    // Establishes the handshake but never sends a data packet, so the mock has nothing to reply
    // with -- receivePacket() must return null immediately (it is non-blocking).
    session.ensureConnected();

    ByteBuffer buffer = ByteBuffer.allocate(1024);
    assertNull(session.receivePacket(buffer));
    assertEquals(0, buffer.position());
  }

  @Test
  @DisabledOnOs({OS.MAC, OS.WINDOWS})
  void receivePacket_ignoresPacketFromUnexpectedSource() throws Exception {
    SnapTunnel session = newSession();
    // Complete one real round trip first and drain it, so nothing legitimate is pending on the
    // wire before the spoofed packet below.
    session.sendPacket(new byte[] {1, 2, 3});
    session.awaitReadable(2000);
    assertNotNull(session.receivePacket(ByteBuffer.allocate(1024)));

    InetSocketAddress tunnelLocalAddr =
        (InetSocketAddress) session.transportChannel().getLocalAddress();
    try (DatagramChannel spoofer = DatagramChannel.open(StandardProtocolFamily.INET)) {
      spoofer.send(ByteBuffer.wrap(new byte[] {9, 9, 9, 9}), tunnelLocalAddr);
      session.awaitReadable(2000);
    }

    ByteBuffer buffer = ByteBuffer.allocate(1024);
    InetSocketAddress from = session.receivePacket(buffer);
    assertNull(from, "a packet from an unexpected source must be discarded, not delivered");
    assertEquals(0, buffer.position(), "buffer must be untouched when the packet is discarded");
  }

  @Test
  @DisabledOnOs({OS.MAC, OS.WINDOWS})
  void receivePacket_skipsUndecryptableGarbageFromRealDataplaneAddress() throws Exception {
    SnapTunnel session = newSession();
    session.ensureConnected();

    // Correctly-addressed (from the real dataplane) but nonsense "data" packet: passes the
    // source-address check, but decrypt() must reject it (receiver-index mismatch here, though any
    // decrypt() failure exercises the same "skip and keep looking" branch) rather than delivering
    // it or throwing.
    InetSocketAddress tunnelLocalAddr =
        (InetSocketAddress) session.transportChannel().getLocalAddress();
    byte[] garbage = WireGuardPacket.buildDataPacket(Integer.MAX_VALUE, 0, new byte[16]);
    mockSnapService.sendRawFromDataplane(garbage, tunnelLocalAddr);
    session.awaitReadable(2000);

    ByteBuffer buffer = ByteBuffer.allocate(1024);
    assertNull(session.receivePacket(buffer));
    assertEquals(0, buffer.position());
  }

  // -------------------------------------------------------------------------
  // parseSnapSocketAddress()
  // -------------------------------------------------------------------------

  @Test
  void parseSnapSocketAddress_wrongLength_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> SnapTunnel.parseSnapSocketAddress(new byte[19]));
  }

  @Test
  void parseSnapSocketAddress_nonZeroReservedByte_throwsScionRuntimeException() {
    byte[] encoded = new byte[20];
    encoded[0] = 0x04;
    encoded[1] = 0x01; // reserved byte must be zero
    assertThrows(ScionRuntimeException.class, () -> SnapTunnel.parseSnapSocketAddress(encoded));
  }

  // No existing test infrastructure exercises the IPv6 case on its own (MockSnapService always
  // encodes IPv4), so it's covered here alongside IPv4.
  @ParameterizedTest
  @CsvSource({"4, 10.0.0.1, 12345", "6, ::1, 54321"})
  void parseSnapSocketAddress_variousIpVersions_returnsCorrectAddress(
      int ipVersion, String ip, int port) throws UnknownHostException {
    InetSocketAddress expected = new InetSocketAddress(InetAddress.getByName(ip), port);

    InetSocketAddress actual = SnapTunnel.parseSnapSocketAddress(encode(ipVersion, expected));

    assertEquals(expected, actual);
  }

  @Test
  void parseSnapSocketAddress_unsupportedIpVersion_throwsScionRuntimeException() {
    byte[] encoded = new byte[20];
    encoded[0] = 0x09; // neither 0x04 (IPv4) nor 0x06 (IPv6)
    assertThrows(ScionRuntimeException.class, () -> SnapTunnel.parseSnapSocketAddress(encoded));
  }

  /** Mirrors the wire format {@code SnapTunnel.parseSnapSocketAddress} expects. */
  private static byte[] encode(int ipVersion, InetSocketAddress addr) {
    ByteBuffer out = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
    out.put((byte) ipVersion);
    out.put((byte) 0x00);
    out.putShort((short) addr.getPort());
    byte[] rawIp = addr.getAddress().getAddress();
    byte[] ipField = new byte[16];
    if (ipVersion == 0x04) {
      System.arraycopy(rawIp, 0, ipField, 12, 4);
    } else {
      System.arraycopy(rawIp, 0, ipField, 0, 16);
    }
    out.put(ipField);
    return out.array();
  }
}
