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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Constants;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Path;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.snap.SnapControlClient;
import org.scion.jpan.internal.snap.SnapService;
import org.scion.jpan.internal.snap.SnapTunnelSession;
import org.scion.jpan.internal.snap.TokenFetcher;
import org.scion.jpan.testutil.MockSnapApiTokenService;
import org.scion.jpan.testutil.MockSnapService;

/**
 * MockSnapService.java (filled in) — implements the full mock SNAP service: <br>
 * - Generates an X25519 static keypair on startup <br>
 * - UDP dataplane thread: receives 148-byte WireGuard handshake init packets, performs the complete
 * SNAP-variant Noise protocol (decrypts the initiator's static key, generates a responder
 * ephemeral, computes the key derivation chain), and responds with a valid 112-byte handshake
 * response containing an encrypted tunnel address assignment (10.0.0.1:12345) <br>
 * - HTTP control server (inner SnapControlServer, extends SimpleHttpServer): serves
 * GetSnapDataPlaneAddress (returns the UDP address + server static key) and RegisterSnapTunIdentity
 * (returns all-zeros PSK share → no PSK) <br>
 * - Uses port 0 (ephemeral) to avoid conflicts; getControlAddress() returns the actual host:port
 * after binding
 *
 * <p>PackageVisibilityHelper.java — added openSnapChannel(SnapTunnelSession) to construct a
 * SNAP-mode ScionDatagramChannel from a session directly, for testing without a full ScionService
 *
 * <p>SnapScionDatagramChannelTest.java (new) — connect_handshakeSucceeds(): starts the mock,
 * creates a SnapTunnelSession pointing at its dataplane, wraps it in a SNAP-mode
 * ScionDatagramChannel, calls ensureConnected(), and asserts localTunnelAddress() != null (proving
 * the WireGuard handshake completed end-to-end)
 *
 * <p>MockNetwork2.java (fixed two bugs): the start() factory was missing the new useSnap argument
 * to the constructor; close() was not shutting down the snap service or clearing the SNAP system
 * properties
 */
class SnapScionDatagramChannelTest {

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
  void connect_handshakeSucceeds() throws IOException {
    // Create a tunnel session pointing at the mock SNAP dataplane.
    // SnapTunnelSession opens its own internal UDP channel; the first argument is unused.
    SnapTunnelSession session =
        new SnapTunnelSession(
            null,
            mockSnapService.getDataplaneAddress(),
            mockSnapService.getStaticPublicKey(),
            null /* no HTTP control client needed for handshake */);

    // Wrap it in a SNAP-mode ScionDatagramChannel and trigger the WireGuard handshake.
    try (ScionDatagramChannel channel = PackageVisibilityHelper.openSnapChannel(session)) {
      assertNotNull(channel);

      session.ensureConnected();

      // A non-null localTunnelAddress proves the handshake completed successfully and the
      // mock assigned a tunnel address to the client.
      assertNotNull(session.localTunnelAddress());
    }
  }

  @Test
  void connect_handshakeSucceedsWithApiToken() throws IOException {
    // Start a mock AA service that issues tokens and a mock SNAP control service that requires one.
    try (MockSnapApiTokenService aaService = MockSnapApiTokenService.start();
        MockSnapService snapService =
            MockSnapService.start("127.0.0.1:0", MockSnapApiTokenService.SNAP_TOKEN)) {

      // Fetch the token from the mock AA service using the known API key.
      String token =
          TokenFetcher.fetchSnapToken(MockSnapApiTokenService.API_KEY, aaService.getBaseUrl());
      assertEquals(MockSnapApiTokenService.SNAP_TOKEN, token);

      System.setProperty(Constants.PROPERTY_SNAP_AUTH_TOKEN, token);
      try {
        // Obtain dataplane info through the authenticated SNAP control API.
        SnapControlClient controlClient = new SnapControlClient(snapService.getControlUrl());
        SnapService dataPlane = controlClient.getDataPlaneAddress();

        SnapTunnelSession session =
            new SnapTunnelSession(
                null, dataPlane.getAddress(), dataPlane.getSnapStaticX25519(), controlClient);

        session.ensureConnected();
        assertNotNull(session.localTunnelAddress());
      } finally {
        System.clearProperty(Constants.PROPERTY_SNAP_AUTH_TOKEN);
      }
    }
  }

  @Test
  void send_installsSnapAssignedSourceAddress() throws IOException {
    SnapTunnelSession session =
        new SnapTunnelSession(
            null,
            mockSnapService.getDataplaneAddress(),
            mockSnapService.getStaticPublicKey(),
            null /* no HTTP control client needed for handshake */);

    try (ScionDatagramChannel channel = PackageVisibilityHelper.openSnapChannel(session)) {
      // Loopback (not wildcard/ANY) avoids AbstractScionChannel falling back to NatMapping, which
      // requires a real ScionService that this channel (built with service=null) doesn't have.
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      assertNull(channel.getOverrideSourceAddress());

      Path path =
          PackageVisibilityHelper.createDummyPath(
              ScionUtil.parseIA("1-ff00:0:110"),
              ScionUtil.parseIA("1-ff00:0:112"),
              new byte[] {127, 0, 0, 1},
              54321,
              new byte[0],
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));

      channel.send(ByteBuffer.wrap(new byte[] {1, 2, 3}), path);

      // The SNAP tunnel's server-assigned address must be installed as the SCION source address
      // BEFORE the header is built -- this is the fix for JPAN previously using the wrong
      // (NAT-mapped/local) source address for all SNAP traffic.
      assertNotNull(session.localTunnelAddress());
      assertEquals(session.localTunnelAddress(), channel.getOverrideSourceAddress());
    }
  }

  @Test
  void send_reusesAdoptedChannelAsOuterChannel() throws IOException {
    // Matches how production code wires a SNAP channel (SnapUnderlaySupport.createFor()): the
    // same real channel is handed to both SnapTunnelSession and the ScionDatagramChannel that
    // wraps it, instead of SnapTunnelSession opening a second, throwaway one.
    DatagramChannel shared = DatagramChannel.open();
    assertNull(shared.getLocalAddress(), "must not be bound yet");

    SnapTunnelSession session =
        new SnapTunnelSession(
            shared,
            mockSnapService.getDataplaneAddress(),
            mockSnapService.getStaticPublicKey(),
            null /* no HTTP control client needed for handshake */);
    assertSame(shared, session.transportChannel());
    // SnapTunnelSession must not have bound it -- binding is the caller's job (see ensureBound()),
    // e.g. to honor an explicit port or the SCION dispatcher port range.
    assertNull(shared.getLocalAddress(), "SnapTunnelSession must defer binding to the caller");

    try (ScionDatagramChannel channel =
        PackageVisibilityHelper.openSnapChannelReusingTransport(session)) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      assertNotNull(shared.getLocalAddress(), "bind() on the channel must bind the shared socket");
      assertNull(channel.getOverrideSourceAddress());

      Path path =
          PackageVisibilityHelper.createDummyPath(
              ScionUtil.parseIA("1-ff00:0:110"),
              ScionUtil.parseIA("1-ff00:0:112"),
              new byte[] {127, 0, 0, 1},
              54321,
              new byte[0],
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));

      channel.send(ByteBuffer.wrap(new byte[] {1, 2, 3}), path);

      // The handshake (triggered by the send above) worked over the same channel that was bound
      // externally -- i.e. adopting a caller-provided channel doesn't break the handshake.
      assertNotNull(session.localTunnelAddress());
      assertEquals(session.localTunnelAddress(), channel.getOverrideSourceAddress());
    }

    // Closing the channel must close the single shared socket exactly once (no
    // AlreadyClosedException-style failure from a redundant close of the same channel).
    assertFalse(shared.isOpen());
  }
}
