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
import org.junit.jupiter.api.Test;
import org.scion.PackageVisibilityHelper;
import org.scion.Scion;
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
public class SegmentsMinimal110Test extends SegmentsMinimalTest {
  private static MockTopologyServer topoServer;

  @BeforeAll
  public static void beforeAll() throws IOException {
    topoServer =
        MockTopologyServer.start(Paths.get("topologies/minimal/ASff00_0_110/topology.json"));
    InetSocketAddress topoAddr = topoServer.getAddress();
    DNSUtil.installNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
    controlServer = MockControlServer.start(31000); // TODO get port from topo
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
  }

  @Test
  void caseA_SameCoreAS() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_110, AS_110);
      assertNotNull(paths);
      assertTrue(paths.isEmpty());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(0, controlServer.getAndResetCallCount());
  }

  @Test
  void caseC_SameIsd_Down() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_110, AS_111);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  Available paths to 1-ff00:0:111
      //  2 Hops:
      //  [0] Hops: [1-ff00:0:110 2>111 1-ff00:0:111] MTU: 1472 NextHop: 127.0.0.18:31004
      //             Status: alive LocalIP: 127.0.0.1

      //      ASInfo found: 561850441793808 1-ff00:0:110  core=true  mtu=1472
      //      Interfaces found: 3
      //      Interface: 3 -> address: "127.0.0.19:31006"
      //      Interface: 1 -> address: "127.0.0.17:31002"
      //      Interface: 2 -> address: "127.0.0.18:31004"
      //      Services found: 1
      //      ListService: control
      //      Service: 127.0.0.20:31000
      //      Paths found: 1
      //      Path:  exp=seconds: 1704747225
      //      mtu=1472
      //      Path: interfaces = 127.0.0.18:31004
      //      Path: first hop = 127.0.0.18:31004
      //      pathIf: 0: 2 561850441793808  1-ff00:0:110
      //      pathIf: 1: 111 561850441793809  1-ff00:0:111
      //      linkType: 0 LINK_TYPE_UNSPECIFIED
      //      raw: [
      //      0x0, 0x0, 0x20, 0x0, 0x1, 0x0, 0xea, 0x3c,
      //      0x65, 0x9c, 0xc, 0x99, 0x0, 0x3f, 0x0, 0x0,
      //      0x0, 0x2, 0x73, 0x52, 0x68, 0x90, 0xb0, 0x35,
      //      0x0, 0x3f, 0x0, 0x6f, 0x0, 0x0, 0x6a, 0xde,
      //      0xc, 0xa3, 0x38, 0x61]

      byte[] raw = {0, 0, 32, 0, 1, 0, -22, 60,
              101, -100, 12, -103, 0, 63, 0, 0,
              0, 2, 115, 82, 104, -112, -80, 53,
              0, 63, 0, 111, 0, 0, 106, -34,
              12, -93, 56, 97};
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(1, controlServer.getAndResetCallCount());
  }

  @Test
  void caseD_SameIsd_Core() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_110, AS_120);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  Available paths to 1-ff00:0:120
      //  2 Hops:
      //  [0] Hops: [1-ff00:0:110 1>10 1-ff00:0:120] MTU: 1472 NextHop: 127.0.0.17:31002
      //             Status: alive LocalIP: 127.0.0.1

    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(1, controlServer.getAndResetCallCount());
  }

  @Test
  void caseG_DifferentIsd_CoreDown_2_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_110, AS_211);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  Available paths to 2-ff00:0:211
      //  4 Hops:
      //  [0] Hops: [1-ff00:0:110 1>10 1-ff00:0:120 1>105 2-ff00:0:210 450>503 2-ff00:0:211]
      //             MTU: 1280 NextHop: 127.0.0.17:31002 Status: alive LocalIP: 127.0.0.1
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(2, controlServer.getAndResetCallCount());
  }

  @Test
  void caseI_DifferentIsd_Core_2_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_110, AS_210);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  Available paths to 2-ff00:0:210
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:110 1>10 1-ff00:0:120 1>105 2-ff00:0:210] MTU: 1280
      //             NextHop: 127.0.0.17:31002 Status: alive LocalIP: 127.0.0.1
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(1, controlServer.getAndResetCallCount());
  }
}
