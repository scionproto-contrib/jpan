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
import org.scion.jpan.ScionPathAddress;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.testutil.MockEchoServer;
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

  // Same AS as the local client (ASff00_0_112): PathBuilder returns a path with an empty raw path
  // for same-AS traffic, which is required for the round-trip verification below to work -- see
  // SnapScionDatagramChannelTest's comment for why.
  private static final long DST_IA = ScionUtil.parseIA("1-ff00:0:112");
  private static final InetSocketAddress DST_ADDRESS = new InetSocketAddress("::1", 12345);

  @Test
  void twoChannels_withDifferentSnapServices_handshakeConcurrentlyAndStayIndependent()
      throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112");
        MockSnapService snapA = MockSnapService.start("127.0.0.1:0");
        MockSnapService snapB = MockSnapService.start("127.0.0.1:0");
        MockEchoServer mirrorA = MockEchoServer.start();
        MockEchoServer mirrorB = MockEchoServer.start()) {
      // Two independent mock SNAP services: separate UDP dataplanes and separate randomly
      // generated X25519 static keys -- i.e. genuinely different SNAP services.
      assertNotEquals(snapA.getDataplaneAddress(), snapB.getDataplaneAddress());
      // Each SNAP service relays through its own plain, non-SNAP mirror, so a round trip proves
      // data actually flowed through the correct tunnel and did not cross over to the other one.
      snapA.relayTo(mirrorA.getAddress());
      snapB.relayTo(mirrorB.getAddress());

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
            byte[] sentA = {1, 2, 3};
            byte[] sentB = {4, 5, 6};
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
              Future<byte[]> sendA =
                  pool.submit(
                      () -> {
                        awaitLatch(start);
                        channelA.send(ByteBuffer.wrap(sentA), pathA);
                        return receiveWithRetry(channelA);
                      });
              Future<byte[]> sendB =
                  pool.submit(
                      () -> {
                        awaitLatch(start);
                        channelB.send(ByteBuffer.wrap(sentB), pathB);
                        return receiveWithRetry(channelB);
                      });
              start.countDown();
              byte[] receivedA = sendA.get(10, TimeUnit.SECONDS);
              byte[] receivedB = sendB.get(10, TimeUnit.SECONDS);

              // Each channel must have gotten back exactly its own data, not the other's --
              // proving there is no cross-talk between the two concurrently-handshaking tunnels.
              assertArrayEquals(sentA, receivedA);
              assertArrayEquals(sentB, receivedB);
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

  /** {@code receive()} is non-blocking, so poll briefly for the mirrored reply to arrive. */
  private static byte[] receiveWithRetry(ScionDatagramChannel channel) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    for (int i = 0; i < 100; i++) {
      ScionPathAddress from = channel.receive(buffer);
      if (from != null) {
        buffer.flip();
        byte[] received = new byte[buffer.remaining()];
        buffer.get(received);
        return received;
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
