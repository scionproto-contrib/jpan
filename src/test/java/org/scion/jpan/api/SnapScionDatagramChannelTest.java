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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Constants;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionPathAddress;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.testutil.MockEchoServer;
import org.scion.jpan.testutil.MockNetwork2;
import org.scion.jpan.testutil.MockSnapApiTokenService;

/**
 * Covers {@link ScionDatagramChannel} wired up for SNAP end-to-end through the real, public {@code
 * ScionDatagramChannel.Builder} -- i.e. through {@code SnapUnderlay.createFor()} exactly as
 * production code does, backed by a real (mock) {@link ScionService} whose {@code
 * preferSnapUnderlay()}/{@code getSnapDataPlane()} point at a genuinely running {@code
 * MockSnapService} dataplane+control server (via {@link MockNetwork2#startSnap}).
 *
 * <p>Neither test here constructs a {@code SnapTunnel} (or any other {@code internal.snap} class)
 * directly -- SNAP is enabled purely via system properties, matching how a real application would
 * configure it, so this also exercises the actual bootstrap wiring (endhost-API/AA-token flow
 * included), not just the tunnel crypto in isolation. Both tests also verify {@code send()}
 * actually delivers data, not just that it doesn't throw: {@code MockSnapService} is pointed at a
 * {@link MockEchoServer} -- a plain, non-SNAP UDP echo -- so the sent bytes genuinely leave the
 * tunnel, come back, and are picked up by a real {@code receive()} call.
 */
class SnapScionDatagramChannelTest {

  // Same AS as the local client (ASff00_0_112): PathBuilder returns a path with an empty raw path
  // for same-AS traffic, which is required for the round-trip verification below to work -- see
  // its comment for why.
  private static final long DST_IA = ScionUtil.parseIA("1-ff00:0:112");
  private static final InetSocketAddress DST_ADDRESS = new InetSocketAddress("::1", 12345);

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  @Test
  void send_installsSnapAssignedSourceAddressAndIsReceivedBack() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startSnap(MockNetwork2.Topology.TINY4B, "ASff00_0_112");
        MockEchoServer mirror = MockEchoServer.start()) {
      nw.getSnapService().relayTo(mirror.getAddress());

      ScionService service = Scion.defaultService();
      Path path = service.getPaths(DST_IA, DST_ADDRESS).get(0);
      assertNotNull(path);

      try (ScionDatagramChannel channel =
          ScionDatagramChannel.newBuilder().service(service).open()) {
        assertNull(channel.getOverrideSourceAddress());

        byte[] sent = {1, 2, 3};
        channel.send(ByteBuffer.wrap(sent), path);

        // The SNAP tunnel's server-assigned address must be installed as the SCION source address
        // BEFORE the header is built -- this is the fix for JPAN previously using the wrong
        // (NAT-mapped/local) source address for all SNAP traffic.
        assertNotNull(channel.getOverrideSourceAddress());

        // And the data must actually have gone somewhere and come back: the mirror server (a
        // plain, non-SNAP UDP echo) received it and sent it back through the tunnel.
        ByteBuffer recvBuf = ByteBuffer.allocate(1024);
        ScionPathAddress from = receiveWithRetry(channel, recvBuf);
        assertNotNull(from, "expected the mirrored reply to come back through the SNAP tunnel");
        recvBuf.flip();
        byte[] received = new byte[recvBuf.remaining()];
        recvBuf.get(received);
        assertArrayEquals(sent, received);
      }
    }
  }

  @Test
  void send_withApiKeyAuthFlow_installsSnapAssignedSourceAddressAndIsReceivedBack()
      throws Exception {
    // Exercises the full API-key -> AA token -> SNAP control -> handshake chain, purely via
    // properties: MockNetwork2.startSnap() already points PROPERTY_SNAP_CONTROL_PLANE at its
    // MockSnapService, but also pre-sets a plain PROPERTY_SNAP_AUTH_TOKEN directly. Setting
    // PROPERTY_SNAP_AUTH_SERVICE/PROPERTY_SNAP_AUTH_KEY on top of that makes
    // Scion.defaultService() take the API-key branch instead, fetching a real token from
    // MockSnapApiTokenService and overwriting the token property with it before falling back to
    // the already-configured path service (the AA mock returns no discovery URL).
    try (MockNetwork2 nw = MockNetwork2.startSnap(MockNetwork2.Topology.TINY4B, "ASff00_0_112");
        MockSnapApiTokenService aaService = MockSnapApiTokenService.start();
        MockEchoServer mirror = MockEchoServer.start()) {
      nw.getSnapService().relayTo(mirror.getAddress());
      System.setProperty(Constants.PROPERTY_SNAP_AUTH_SERVICE, aaService.getBaseUrl());
      System.setProperty(Constants.PROPERTY_SNAP_AUTH_KEY, MockSnapApiTokenService.API_KEY);
      try {
        ScionService service = Scion.defaultService();
        Path path = service.getPaths(DST_IA, DST_ADDRESS).get(0);
        assertNotNull(path);

        try (ScionDatagramChannel channel =
            ScionDatagramChannel.newBuilder().service(service).open()) {
          byte[] sent = {1, 2, 3};
          channel.send(ByteBuffer.wrap(sent), path);
          assertNotNull(channel.getOverrideSourceAddress());

          ByteBuffer recvBuf = ByteBuffer.allocate(1024);
          ScionPathAddress from = receiveWithRetry(channel, recvBuf);
          assertNotNull(from, "expected the mirrored reply to come back through the SNAP tunnel");
          recvBuf.flip();
          byte[] received = new byte[recvBuf.remaining()];
          recvBuf.get(received);
          assertArrayEquals(sent, received);
        }
      } finally {
        System.clearProperty(Constants.PROPERTY_SNAP_AUTH_SERVICE);
        System.clearProperty(Constants.PROPERTY_SNAP_AUTH_KEY);
      }
    }
  }

  /** {@code receive()} is non-blocking, so poll briefly for the mirrored reply to arrive. */
  private static ScionPathAddress receiveWithRetry(ScionDatagramChannel channel, ByteBuffer buffer)
      throws IOException {
    for (int i = 0; i < 100; i++) {
      ScionPathAddress from = channel.receive(buffer);
      if (from != null) {
        return from;
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    return null;
  }
}
