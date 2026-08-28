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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.Scmp;
import org.scion.jpan.ScmpSenderAsync;
import org.scion.jpan.testutil.MockNetwork2;

/**
 * Covers {@link ScmpSenderAsync} wired up for SNAP end-to-end through the real, public {@link
 * ScmpSenderAsync.Builder} -- i.e. through {@code SnapUnderlaySupport.createFor()} exactly as
 * production code does, backed by a real (mock) {@code ScionService} whose {@code
 * preferSnapUnderlay()}/{@code getSnapDataPlane()} point at a genuinely running {@link
 * org.scion.jpan.testutil.MockSnapService} dataplane+control server (via {@link
 * MockNetwork2#startSnap}).
 *
 * <p>There is no mock border router wired into this path (unlike the older {@code MockNetwork} +
 * {@code MockScmpHandler} used by {@link ScmpSenderAsyncTest}), so this cannot assert an actual
 * SCMP reply -- {@code MockNetwork} and {@code MockNetwork2} are independent mocks that don't
 * compose (both drive the {@code Scion.defaultService()} singleton via conflicting system
 * properties). What this test does cover, exercising exactly the machinery that regressed
 * repeatedly during the channel-reuse work (see doc/SnapChannelAbstraction.md): building the
 * channel via the real SNAP-aware {@code Builder} path, completing the real WireGuard handshake,
 * installing the SNAP-assigned source address, actually encrypting and sending SCMP echo and
 * traceroute requests over the tunnel, and a clean, non-throwing {@code close()} afterward.
 */
class ScmpSenderAsyncSnapTest {

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  private static final class NoOpHandler implements ScmpSenderAsync.ResponseHandler {
    @Override
    public void onResponse(Scmp.TimedMessage msg) {
      // Nothing to do
    }

    @Override
    public void onTimeout(Scmp.TimedMessage msg) {
      // Nothing to do
    }
  }

  @Test
  void sendEchoAndTraceroute_overRealSnapTunnel_succeedAndCloseCleanly() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startSnap(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      long dstIA = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
      ScionService service = Scion.defaultService();
      assertTrue(PackageVisibilityHelper.preferSnapUnderlay(service));
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);

      ScmpSenderAsync sender =
          Scmp.newSenderAsyncBuilder(new NoOpHandler()).setService(service).build();
      try {
        int seqId = sender.sendEcho(path, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertTrue(seqId >= 0);
        sender.sendTraceroute(path);
      } finally {
        // Must not throw: this is exactly the shared-channel double-close hazard that
        // AbstractScionChannel.close()'s snapOwnsChannel guard exists to avoid.
        assertDoesNotThrow(sender::close);
      }

      // Safe to call more than once.
      assertDoesNotThrow(sender::close);
    }
  }
}
