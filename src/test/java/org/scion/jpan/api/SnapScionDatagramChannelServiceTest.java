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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionService;
import org.scion.jpan.internal.snap.SnapTunnel;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockSnapService;

/**
 * Covers {@link PackageVisibilityHelper#openSnapChannel(ScionService, SnapTunnel)} with a real,
 * non-null {@link ScionService} -- as opposed to {@link SnapScionDatagramChannelTest}, which only
 * ever uses the null-service overload. A non-null service is what lets the channel resolve a plain
 * destination address into a path via {@code send(ByteBuffer, SocketAddress)}, instead of requiring
 * an already-resolved {@code Path}.
 */
class SnapScionDatagramChannelServiceTest {

  private static final InetSocketAddress DUMMY_ADDRESS =
      new InetSocketAddress(IPHelper.toInetAddress("dummyHost", "127.0.0.1"), 44444);

  private MockSnapService mockSnapService;

  @BeforeEach
  void beforeEach() {
    MockNetwork.startTiny(); // real, daemon-backed ScionService via Scion.defaultService()
    MockDNS.install("1-ff00:0:112", DUMMY_ADDRESS.getAddress());
    mockSnapService = MockSnapService.start(MockSnapService.ADDRESS);
  }

  @AfterEach
  void afterEach() {
    mockSnapService.close();
    MockNetwork.stopTiny();
    MockDNS.clear();
    ScionService.closeDefault();
  }

  @Test
  void send_addressBased_withRealService_installsSnapAssignedSourceAddress() throws IOException {
    ScionService service = Scion.defaultService();
    assertNotNull(service);

    SnapTunnel session =
        new SnapTunnel(
            null,
            mockSnapService.getDataplaneAddress(),
            mockSnapService.getStaticPublicKey(),
            null /* no HTTP control client needed for handshake */);

    try (ScionDatagramChannel channel = PackageVisibilityHelper.openSnapChannel(service, session)) {
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      assertNull(channel.getOverrideSourceAddress());

      // Address-based send: unlike send(buffer, Path), this requires a real, non-null service --
      // it resolves DUMMY_ADDRESS into a path via service.lookup(...) + the path selector
      // factory, both of which are wired up by openSnapChannel only when service != null.
      channel.send(ByteBuffer.wrap(new byte[] {1, 2, 3}), DUMMY_ADDRESS);

      // Same invariant as SnapScionDatagramChannelTest.send_installsSnapAssignedSourceAddress,
      // but reached via address-based resolution instead of an explicit dummy Path.
      assertNotNull(session.localTunnelAddress());
      assertEquals(session.localTunnelAddress(), channel.getOverrideSourceAddress());
    }
  }
}
