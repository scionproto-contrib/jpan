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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Constants;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.testutil.MockNetwork2;
import org.scion.jpan.testutil.MockSnapService;

/**
 * Covers two independent, genuinely concurrent SNAP tunnels -- each backed by its own {@link
 * MockSnapService} and its own, independently-constructed {@link org.scion.jpan.ScionService} (via
 * {@link Scion#newServiceWithEndhostApi}) -- being handshaked and used at the same time from two
 * separate {@link ScionDatagramChannel}s in the same process. This guards against per-tunnel state
 * (crypto session, assigned tunnel address, resolved SNAP dataplane) accidentally leaking across
 * independent SNAP channels.
 *
 * <p>SNAP is enabled purely via system properties here, not by constructing a {@code SnapTunnel}
 * directly. That does mean the two {@link org.scion.jpan.ScionService} instances can't be {@link
 * Scion#defaultService()} (a single, process-wide singleton, so it cannot represent two
 * independently-configured SNAP dataplanes at once) -- {@link Scion#newServiceWithEndhostApi}
 * exists specifically to build extra, independent instances like this one for exactly this case.
 */
class SnapScionDatagramChannelMultiServiceTest {

  private static final long DST_IA = ScionUtil.parseIA("1-ff00:0:111");
  private static final InetSocketAddress DST_ADDRESS = new InetSocketAddress("::1", 12345);

  @Test
  void twoChannels_withDifferentSnapServices_handshakeConcurrentlyAndStayIndependent()
      throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112");
        MockSnapService snapA = MockSnapService.start("127.0.0.1:0");
        MockSnapService snapB = MockSnapService.start("127.0.0.1:0")) {
      // Two independent mock SNAP services: separate UDP dataplanes and separate randomly
      // generated X25519 static keys -- i.e. genuinely different SNAP services.
      assertNotEquals(snapA.getDataplaneAddress(), snapB.getDataplaneAddress());

      System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");
      String pathServiceAddress = System.getProperty(Constants.PROPERTY_BOOTSTRAP_PATH_SERVICE);

      // Each newServiceWithEndhostApi() call resolves its SNAP dataplane immediately, from
      // whatever org.scion.snap.controlPlane points at *at that moment* -- unlike
      // Scion.defaultService(), which is a single process-wide singleton and could not represent
      // two different SNAP dataplanes concurrently.
      System.setProperty(Constants.PROPERTY_SNAP_CONTROL_PLANE, snapA.getControlUrl());
      try (Scion.CloseableService serviceA = Scion.newServiceWithEndhostApi(pathServiceAddress)) {
        System.setProperty(Constants.PROPERTY_SNAP_CONTROL_PLANE, snapB.getControlUrl());
        try (Scion.CloseableService serviceB = Scion.newServiceWithEndhostApi(pathServiceAddress)) {

          // Each service resolved its own dataplane -- not each other's, and not a shared/stale
          // one -- proving no property-race between the two sequential constructions above.
          assertEquals(
              snapA.getDataplaneAddress(),
              PackageVisibilityHelper.getSnapDataPlaneAddress(serviceA));
          assertEquals(
              snapB.getDataplaneAddress(),
              PackageVisibilityHelper.getSnapDataPlaneAddress(serviceB));
          assertNotEquals(
              PackageVisibilityHelper.getSnapDataPlaneAddress(serviceA),
              PackageVisibilityHelper.getSnapDataPlaneAddress(serviceB));

          Path pathA = serviceA.getPaths(DST_IA, DST_ADDRESS).get(0);
          Path pathB = serviceB.getPaths(DST_IA, DST_ADDRESS).get(0);

          try (ScionDatagramChannel channelA =
                  ScionDatagramChannel.newBuilder().service(serviceA).open();
              ScionDatagramChannel channelB =
                  ScionDatagramChannel.newBuilder().service(serviceB).open()) {
            assertNull(channelA.getOverrideSourceAddress());
            assertNull(channelB.getOverrideSourceAddress());

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

            // Each channel must have completed its own handshake against its own SNAP service --
            // proving there is no cross-talk between the two concurrently-handshaking tunnels.
            assertNotNull(channelA.getOverrideSourceAddress());
            assertNotNull(channelB.getOverrideSourceAddress());
          }
        }
      } finally {
        System.clearProperty(Constants.PROPERTY_SNAP_CONTROL_PLANE);
        System.clearProperty(Constants.PROPERTY_UNDERLAY_MODE);
      }
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
