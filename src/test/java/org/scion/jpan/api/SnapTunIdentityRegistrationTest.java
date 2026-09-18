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
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.testutil.MockEchoServer;
import org.scion.jpan.testutil.MockNetwork2;
import org.scion.jpan.testutil.MockSnapService;
import org.scion.jpan.testutil.TestUtil;

/**
 * Covers {@code SnapControlClient.registerSnapTunIdentity()} through the real, public {@code
 * ScionDatagramChannel} API rather than by calling {@code internal.snap} classes directly.
 *
 * <p>{@code SnapTunnel} only calls {@code registerSnapTunIdentity()} when the dataplane response
 * advertises a {@code snap_tun_control_address}, an optional field the other SNAP API tests leave
 * unset. {@link MockSnapService#enableSnapTunControlAddress()} makes the mock advertise its own
 * control URL for that field, so the client genuinely round-trips an HTTP request to it, observed
 * via {@link MockSnapService#getRegisterIdentityCallCount()}.
 */
class SnapTunIdentityRegistrationTest {

  // Same AS as the local client (ASff00_0_112): PathBuilder returns a path with an empty raw path
  // for same-AS traffic, which is required for the round-trip verification below to work -- see
  // SnapScionDatagramChannelTest's comment for why.
  private static final long DST_IA = ScionUtil.parseIA("1-ff00:0:112");
  private static final InetSocketAddress DST_ADDRESS = new InetSocketAddress("::1", 12345);

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  @Test
  void send_withSnapTunControlAdvertised_registersTunIdentityAndCompletesHandshake()
      throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startSnap(MockNetwork2.Topology.TINY4B, "ASff00_0_112");
        MockEchoServer mirror = MockEchoServer.start()) {
      MockSnapService snap = nw.getSnapService();
      snap.relayTo(mirror.getAddress());
      snap.enableSnapTunControlAddress();
      assertEquals(0, snap.getRegisterIdentityCallCount());

      ScionService service = Scion.defaultService();
      Path path = service.getPaths(DST_IA, DST_ADDRESS).get(0);
      assertNotNull(path);

      try (ScionDatagramChannel channel =
          ScionDatagramChannel.newBuilder().service(service).open()) {
        byte[] sent = {1, 2, 3};
        channel.send(ByteBuffer.wrap(sent), path);

        // The handshake must have called registerSnapTunIdentity() against the mock's control
        // server.
        assertEquals(1, snap.getRegisterIdentityCallCount());

        // And the resulting handshake still works end-to-end: data round-trips through the mirror.
        byte[] received = TestUtil.receiveWithRetry(channel);
        assertNotNull(received, "expected the mirrored reply to come back through the SNAP tunnel");
        assertArrayEquals(sent, received);
      }
    }
  }
}
