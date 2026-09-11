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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Path;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.snap.SnapTunnel;
import org.scion.jpan.testutil.MockSnapService;

/**
 * Covers two independent SNAP tunnels -- each backed by its own {@link MockSnapService} with a
 * distinct dataplane address and a distinct, randomly generated static key -- being handshaked and
 * used concurrently from two separate {@link ScionDatagramChannel}s in the same process. This
 * guards against per-tunnel state (crypto session, assigned tunnel address, transport channel)
 * accidentally leaking across independent SNAP channels, since {@code SnapUnderlay}/{@code
 * SnapTunnel} state is meant to be per-instance rather than global/static.
 */
class SnapScionDatagramChannelMultiServiceTest {

  private MockSnapService serviceA;
  private MockSnapService serviceB;

  @AfterEach
  void afterEach() {
    if (serviceA != null) {
      serviceA.close();
    }
    if (serviceB != null) {
      serviceB.close();
    }
  }

  @Test
  void twoChannels_withDifferentSnapServices_handshakeConcurrentlyAndStayIndependent()
      throws Exception {
    // Two independent mock SNAP services: separate UDP dataplanes and separate randomly generated
    // X25519 static keys -- i.e. genuinely different SNAP services, not just two sessions against
    // the same one. Both use ephemeral ports (unlike MockSnapService.ADDRESS's fixed 24242) so
    // they don't collide with each other.
    serviceA = MockSnapService.start("127.0.0.1:0");
    serviceB = MockSnapService.start("127.0.0.1:0");
    assertNotEquals(serviceA.getDataplaneAddress(), serviceB.getDataplaneAddress());
    assertFalse(Arrays.equals(serviceA.getStaticPublicKey(), serviceB.getStaticPublicKey()));

    SnapTunnel sessionA =
        new SnapTunnel(null, serviceA.getDataplaneAddress(), serviceA.getStaticPublicKey(), null);
    SnapTunnel sessionB =
        new SnapTunnel(null, serviceB.getDataplaneAddress(), serviceB.getStaticPublicKey(), null);

    try (ScionDatagramChannel channelA = PackageVisibilityHelper.openSnapChannel(sessionA);
        ScionDatagramChannel channelB = PackageVisibilityHelper.openSnapChannel(sessionB)) {
      channelA.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      channelB.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      assertNull(channelA.getOverrideSourceAddress());
      assertNull(channelB.getOverrideSourceAddress());

      Path pathA =
          PackageVisibilityHelper.createDummyPath(
              ScionUtil.parseIA("1-ff00:0:110"),
              ScionUtil.parseIA("1-ff00:0:112"),
              new byte[] {127, 0, 0, 1},
              54321,
              new byte[0],
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));
      Path pathB =
          PackageVisibilityHelper.createDummyPath(
              ScionUtil.parseIA("1-ff00:0:120"),
              ScionUtil.parseIA("1-ff00:0:122"),
              new byte[] {127, 0, 0, 1},
              54322,
              new byte[0],
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 12346));

      // A shared start signal makes both threads race into their handshake as close to
      // simultaneously as possible, rather than one finishing before the other even starts.
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<?> sendA =
            pool.submit(
                () -> {
                  awaitLatch(start);
                  channelA.send(ByteBuffer.wrap(new byte[] {1, 2, 3}), pathA);
                  return null;
                });
        Future<?> sendB =
            pool.submit(
                () -> {
                  awaitLatch(start);
                  channelB.send(ByteBuffer.wrap(new byte[] {4, 5, 6}), pathB);
                  return null;
                });
        start.countDown();
        sendA.get(10, TimeUnit.SECONDS);
        sendB.get(10, TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
      }

      // Each channel must have completed its own handshake against its own SNAP service and
      // installed the tunnel address that service (and only that service) assigned to it --
      // proving there is no cross-talk between the two concurrently-handshaking sessions.
      assertNotNull(sessionA.localTunnelAddress());
      assertNotNull(sessionB.localTunnelAddress());
      assertEquals(sessionA.localTunnelAddress(), channelA.getOverrideSourceAddress());
      assertEquals(sessionB.localTunnelAddress(), channelB.getOverrideSourceAddress());
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
