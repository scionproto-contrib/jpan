// Copyright 2024 ETH Zurich
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

package org.scion.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.PackageVisibilityHelper;
import org.scion.Scion;
import org.scion.ScionService;
import org.scion.proto.daemon.Daemon;
import org.scion.testutil.DNSUtil;
import org.scion.testutil.MockControlServer;
import org.scion.testutil.MockTopologyServer;

/**
 * Test cases: <br>
 * A (-): srcISD == dstISD; src == dst; (same ISD, same AS)<br>
 * B (UP): srcISD == dstISD; dst == core; (same ISD, dst is core)<br>
 * C (DOWN): srcISD == dstISD; src == core; (same ISD, dst is core)<br>
 * D (CORE): srcISD == dstISD; src == core, dst == core; (same ISD, src/dst are cores)<br>
 * E (UP, DOWN): srcISD == dstISD; (same ISD, src/dst are non-cores)<br>
 * F (UP, CORE): srcISD != dstISD; dst == core; (different ISDs, dst is core)<br>
 * G (CORE, DOWN): srcISD != dstISD; src == core; (different ISDs, src is cores)<br>
 * H (UP, CORE, DOWN): srcISD != dstISD; (different ISDs, src/dst are non-cores)<br>
 * I (CORE): srcISD != dstISD; (different ISDs, src/dst are cores)
 */
@Disabled
public class SegmentsMinimal120Test extends AbstractSegmentsMinimalTest {
  private static MockTopologyServer topoServer;

  @BeforeAll
  public static void beforeAll() throws IOException {
    topoServer =
        MockTopologyServer.start(Paths.get("topologies/minimal/ASff00_0_120/topology.json"));
    InetSocketAddress topoAddr = topoServer.getAddress();
    DNSUtil.installNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
    controlServer = MockControlServer.start(topoServer.getControlServerPort());
  }

  @AfterEach
  public void afterEach() {
    controlServer.clearSegments();
    topoServer.getAndResetCallCount();
    controlServer.getAndResetCallCount();
  }

  @AfterAll
  public static void afterAll() {
    controlServer.close();
    topoServer.close();
    DNSUtil.clear();
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void caseG_DifferentIsd_CoreDown_1_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_120, AS_211);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(3, controlServer.getAndResetCallCount());
  }

  @Test
  void caseI_DifferentIsd_Core_1_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_120, AS_210);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(1, controlServer.getAndResetCallCount());
  }
}
