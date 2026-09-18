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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Constants;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionService;
import org.scion.jpan.testutil.MockNetwork2;

/**
 * Covers {@code org.scion.underlay.mode=auto}: SNAP should be used opportunistically, but a missing
 * or unreachable SNAP control endpoint must fall back to plain UDP rather than failing {@code
 * ScionService}/channel construction, so a transient SNAP outage can't break an application that
 * never asked for SNAP specifically.
 *
 * <p>None of these tests configure a SNAP control-plane entry ({@link MockNetwork2#startPS}, not
 * {@code startSnap}), so the endhost API genuinely advertises no usable SNAP entry.
 */
class ScionServiceSnapAutoFallbackTest {

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
    System.clearProperty(Constants.PROPERTY_UNDERLAY_MODE);
    System.clearProperty(Constants.PROPERTY_SNAP_CONTROL_PLANE);
  }

  @Test
  void autoMode_noSnapControlEndpointAdvertised_fallsBackToPlainUdp() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "auto");

      // Must not throw: ScionService.initializeSnapDataPlaneIfEnabled() should treat "no SNAP
      // control endpoint available" as "use plain UDP" rather than a hard failure under auto.
      ScionService service = Scion.defaultService();
      assertNull(PackageVisibilityHelper.getSnapDataPlaneAddress(service));

      // Opening a channel must also not throw: SnapUnderlay.tryCreate() has the same auto-vs-snap
      // distinction as the ScionService-level check above.
      try (ScionDatagramChannel channel =
          ScionDatagramChannel.newBuilder().service(service).open()) {
        assertNull(channel.getOverrideSourceAddress());
      }
    }
  }

  @Test
  void autoMode_snapControlEndpointUnreachable_fallsBackToPlainUdp() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      // A concrete, momentarily-bound-then-released port: guaranteed free, so the SNAP control
      // request fails with connection-refused (as opposed to "no control node advertised" above,
      // which never even attempts a network call).
      int deadPort;
      try (ServerSocket socket = new ServerSocket(0)) {
        deadPort = socket.getLocalPort();
      }
      System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "auto");
      System.setProperty(Constants.PROPERTY_SNAP_CONTROL_PLANE, "http://127.0.0.1:" + deadPort);

      ScionService service = Scion.defaultService();
      assertNull(PackageVisibilityHelper.getSnapDataPlaneAddress(service));

      try (ScionDatagramChannel channel =
          ScionDatagramChannel.newBuilder().service(service).open()) {
        assertNull(channel.getOverrideSourceAddress());
      }
    }
  }

  @Test
  void snapMode_noSnapControlEndpointAdvertised_stillThrows() throws Exception {
    // Regression guard: the auto-mode fallback above must not weaken strict "snap" mode, which
    // should keep failing loudly when SNAP is unavailable rather than silently using plain UDP.
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");

      assertThrows(ScionRuntimeException.class, Scion::defaultService);
    }
  }
}
