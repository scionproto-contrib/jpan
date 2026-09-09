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

package org.scion.jpan.internal.snap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.testutil.MockSnapService;

class SnapTunnelTest {

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
  void sendPacket_returnsScionByteCount_notWireGuardWireSize() throws IOException {
    SnapTunnel session =
        new SnapTunnel(
            null,
            mockSnapService.getDataplaneAddress(),
            mockSnapService.getStaticPublicKey(),
            null /* no HTTP control client needed for handshake */);

    byte[] scionPacket = new byte[123];
    int sent = session.sendPacket(scionPacket);

    // Before the fix, this returned scionPacket.length + 32 (the WireGuard data-header + AEAD
    // tag overhead added by encrypt()) -- the size of the packet actually on the wire, not the
    // number of SCION-level bytes the caller asked to send. Callers such as
    // ScionDatagramChannel.send() subtract their own header size from this return value to report
    // "payload bytes sent" to the API user, so leaking the WireGuard overhead here made send()
    // report too many bytes.
    assertEquals(scionPacket.length, sent);
  }

  @Test
  void sendPacket_zeroLengthPacket_returnsZero() throws IOException {
    SnapTunnel session =
        new SnapTunnel(
            null,
            mockSnapService.getDataplaneAddress(),
            mockSnapService.getStaticPublicKey(),
            null /* no HTTP control client needed for handshake */);

    int sent = session.sendPacket(new byte[0]);

    assertEquals(0, sent);
  }
}
