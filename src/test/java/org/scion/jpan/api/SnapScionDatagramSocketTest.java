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

import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.*;

/**
 * Covers {@link ScionDatagramSocket} wired up for SNAP end-to-end through the real, public {@code
 * ScionDatagramSocket.Builder}, backed by a real (mock) {@link ScionService} pointed at a running
 * {@code MockSnapService} (via {@link MockNetwork2#startSnap}). SNAP is enabled purely via system
 * properties, matching real application configuration, so this also exercises the endhost-API/
 * AA-token bootstrap flow, not just the tunnel crypto.
 *
 * <p>The {@code send_*} tests verify {@code send()} actually delivers data: {@code MockSnapService}
 * relays through a {@link MockEchoServer} -- a plain, non-SNAP UDP echo -- so the sent bytes
 * genuinely leave the tunnel, come back, and are picked up by {@code receive()}.
 */
class SnapScionDatagramSocketTest {

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

      try (ScionDatagramSocket channel = ScionDatagramSocket.newBuilder().service(service).open()) {
        byte[] sent = {1, 2, 3};
        ScionDatagramPacket packet = new ScionDatagramPacket(sent, 3, path);
        channel.send(packet);

        // And the data must actually have gone somewhere and come back: the mirror server (a
        // plain, non-SNAP UDP echo) received it and sent it back through the tunnel.
        byte[] received = TestUtil.receiveWithRetry(channel);
        assertNotNull(received, "expected the mirrored reply to come back through the SNAP tunnel");
        assertArrayEquals(sent, received);
      }
    }
  }

  @Test
  void send_withApiKeyAuthFlow_installsSnapAssignedSourceAddressAndIsReceivedBack()
      throws Exception {
    // Exercises the full API-key -> AA token -> SNAP control -> handshake chain: setting
    // PROPERTY_SNAP_AUTH_SERVICE/PROPERTY_SNAP_AUTH_KEY makes Scion.defaultService() fetch a real
    // token from MockSnapApiTokenService and overwrite PROPERTY_SNAP_AUTH_TOKEN with it, before
    // falling back to the path service MockNetwork2.startSnap() already configured.
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

        try (ScionDatagramSocket channel =
            ScionDatagramSocket.newBuilder().service(service).open()) {
          byte[] sent = {1, 2, 3};
          ScionDatagramPacket packet = new ScionDatagramPacket(sent, 3, path);
          channel.send(packet);

          byte[] received = TestUtil.receiveWithRetry(channel);
          assertNotNull(
              received, "expected the mirrored reply to come back through the SNAP tunnel");
          assertArrayEquals(sent, received);
        }
      } finally {
        System.clearProperty(Constants.PROPERTY_SNAP_AUTH_SERVICE);
        System.clearProperty(Constants.PROPERTY_SNAP_AUTH_KEY);
      }
    }
  }
}
