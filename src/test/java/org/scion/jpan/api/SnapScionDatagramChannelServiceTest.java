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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionPathAddress;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionSocketAddress;
import org.scion.jpan.testutil.MockEchoServer;
import org.scion.jpan.testutil.MockNetwork2;

/**
 * Covers {@code ScionDatagramChannel.send(ByteBuffer, SocketAddress)} in SNAP mode with a real,
 * non-null {@link ScionService} -- as opposed to {@link SnapScionDatagramChannelTest}, which only
 * ever sends via an already-resolved {@code Path}. A non-null service is what lets the channel
 * resolve a destination address into a path via the path selector, which {@code
 * ScionDatagramChannel.Builder} only wires up when a real service is attached.
 *
 * <p>SNAP is enabled purely via system properties (through {@link MockNetwork2#startSnap}), not by
 * constructing a {@code SnapTunnel} directly. The test also verifies {@code send()} actually
 * delivers data: {@code MockSnapService} is pointed at a {@link MockEchoServer} -- a plain,
 * non-SNAP UDP echo -- so the sent bytes genuinely leave the tunnel, come back, and are picked up
 * by a real {@code receive()} call.
 */
class SnapScionDatagramChannelServiceTest {

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  @Test
  void send_addressBased_withRealService_installsSnapAssignedSourceAddressAndIsReceivedBack()
      throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startSnap(MockNetwork2.Topology.TINY4B, "ASff00_0_112");
        MockEchoServer mirror = MockEchoServer.start()) {
      nw.getSnapService().relayTo(mirror.getAddress());

      ScionService service = Scion.defaultService();
      assertNotNull(service);

      try (ScionDatagramChannel channel =
          ScionDatagramChannel.newBuilder().service(service).open()) {
        channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        assertNull(channel.getOverrideSourceAddress());

        // Address-based send: unlike send(buffer, Path), this requires a real, non-null service
        // to build a path selector for the destination -- ScionDatagramChannel.Builder only wires
        // one up when service != null. A ScionSocketAddress (rather than a plain InetSocketAddress)
        // avoids needing a DNS TXT lookup for the ISD/AS on top of that. The destination is the
        // same AS as the local client (ASff00_0_112): PathBuilder returns an empty raw path for
        // same-AS traffic, which is required for the round-trip verification below to work.
        ScionSocketAddress dst =
            PackageVisibilityHelper.toSSA(
                "1-ff00:0:112", new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));
        byte[] sent = {1, 2, 3};
        channel.send(ByteBuffer.wrap(sent), dst);

        // Same invariant as SnapScionDatagramChannelTest.send_installsSnapAssignedSourceAddress,
        // but reached via address-based resolution instead of an explicit Path.
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
