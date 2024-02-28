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
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
 * F0 (UP, CORE): srcISD == dstISD; dst == core; (same ISD, dst is core); Book: 1b<br>
 * F (UP, CORE): srcISD != dstISD; dst == core; (different ISDs, dst is core)<br>
 * G (CORE, DOWN): srcISD != dstISD; src == core; (different ISDs, src is cores)<br>
 * H (UP, CORE, DOWN): srcISD != dstISD; (different ISDs, src/dst are non-cores)<br>
 * I (CORE): srcISD != dstISD; (different ISDs, src/dst are cores)
 */
public class SegmentsMinimal110Test extends AbstractSegmentsMinimalTest {
  private static MockTopologyServer topoServer;

  @BeforeAll
  public static void beforeAll() {
    topoServer = MockTopologyServer.start("topologies/minimal/ASff00_0_110/topology.json");
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
  void caseA_SameCoreAS() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_110, AS_110);
      Daemon.Path path = paths.get(0);
      assertEquals(0, path.getRaw().size());
      assertEquals(1472, path.getMtu());
      assertEquals(0, path.getInterfacesCount());
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
      //  [0] Hops: [1-ff00:0:110 2>111 1-ff00:0:111]
      //            MTU: 1472 NextHop: 127.0.0.18:31004 Status: alive LocalIP: 127.0.0.1

      //      Path: interfaces = 127.0.0.18:31004
      //      Path: first hop = 127.0.0.18:31004
      //      pathIf: 0: 2 561850441793808  1-ff00:0:110
      //      pathIf: 1: 111 561850441793809  1-ff00:0:111

      byte[] raw = {
        0, 0, 32, 0, 1, 0, -22, 60, 101, -100, 12, -103, 0, 63, 0, 0, 0, 2, 115, 82, 104, -112, -80,
        53, 0, 63, 0, 111, 0, 0, 106, -34, 12, -93, 56, 97
      };
      Daemon.Path path = paths.get(0);
      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());
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
      //  [0] Hops: [1-ff00:0:110 1>10 1-ff00:0:120]
      //            MTU: 1472 NextHop: 127.0.0.17:31002 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1709133356 / 2024-02-28T15:15:56Z  mtu=1472
      //  Path: first hop = 127.0.0.25:31002
      //  pathIf: 0: 1 561850441793808  1-ff00:0:110
      //  pathIf: 1: 10 561850441793824  1-ff00:0:120
      byte[] raw = {
        0, 0, 32, 0, 0, 0, 120, -69, 101, -34, -7, -20, 0, 63, 0, 1, 0, 0, 108, -99, 77, -58, -17,
        -27, 0, 63, 0, 0, 0, 10, -57, 0, 47, 115, -27, 19
      };
      Daemon.Path path = paths.get(0);
      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(1, controlServer.getAndResetCallCount());
  }

  @Test
  void caseF0_SameIsd_CoreDown() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_110, AS_121);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  Available paths to 1-ff00:0:121
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:110 1>10 1-ff00:0:120 21>104 1-ff00:0:121]
      //            MTU: 1472 NextHop: 127.0.0.25:31002 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1709073168 / 2024-02-27T22:32:48Z  mtu=1472
      //  Path: first hop = 127.0.0.25:31002
      //  pathIf: 0: 1 561850441793808  1-ff00:0:110
      //  pathIf: 1: 10 561850441793824  1-ff00:0:120
      //  pathIf: 2: 21 561850441793824  1-ff00:0:120
      //  pathIf: 3: 104 561850441793825  1-ff00:0:121
      byte[] raw = {
        0, 0, 32, -128, 0, 0, -2, 50, 101, -34, 14, -48, 1, 0, -88, -116, 101, -34, 14, -48, 0, 63,
        0, 1, 0, 0, -32, 11, -116, 98, 40, 59, 0, 63, 0, 0, 0, 10, 69, 7, 15, -100, -60, -113, 0,
        63, 0, 0, 0, 21, -1, 100, 76, 70, -81, 125, 0, 63, 0, 104, 0, 0, -74, -115, 123, 0, -56, 48
      };
      // System.out.println(ToStringUtil.pathLong(raw)); // TODO
      // System.out.println(ToStringUtil.path(raw)); // TODO
      Daemon.Path path = paths.get(0);
      // System.out.println(ToStringUtil.path(path.getRaw().toByteArray())); // TODO
      // System.out.println(ToStringUtil.pathLong(path.getRaw().toByteArray())); // TODO
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 2, 0);
      checkInfo(rawBB, 10619, 0);
      checkInfo(rawBB, 48280, 1);
      checkHopField(rawBB, 1, 0);
      checkHopField(rawBB, 0, 10);
      checkHopField(rawBB, 0, 21);
      checkHopField(rawBB, 104, 0);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1472, path.getMtu());
      String firstHop = topoServer.getBorderRouterAddressByIA(AS_120);
      assertEquals(firstHop, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 1, "1-ff00:0:110");
      checkInterface(path, 1, 10, "1-ff00:0:120");
      checkInterface(path, 2, 21, "1-ff00:0:120");
      checkInterface(path, 3, 104, "1-ff00:0:121");
      assertEquals(4, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(3, controlServer.getAndResetCallCount());
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

      //  Path:  exp=1709133655 / 2024-02-28T15:20:55Z  mtu=1280
      //  Path: first hop = 127.0.0.25:31002
      //  pathIf: 0: 1 561850441793808  1-ff00:0:110
      //  pathIf: 1: 10 561850441793824  1-ff00:0:120
      //  pathIf: 2: 210 561850441793824  1-ff00:0:120
      //  pathIf: 3: 105 843325418504720  2-ff00:0:210
      //  pathIf: 4: 450 843325418504720  2-ff00:0:210
      //  pathIf: 5: 503 843325418504721  2-ff00:0:211
      byte[] raw = {
        0, 0, 48, -128, 0, 0, -67, -51, 101, -34, -5, 23, 1, 0, 82, -120, 101, -34, -5, 34, 0, 63,
        0, 1, 0, 0, 48, -24, 88, -54, -62, 116, 0, 63, 0, -46, 0, 10, 9, 70, -53, -49, 111, 48, 0,
        63, 0, 0, 0, 105, 85, 20, 63, -118, -11, 40, 0, 63, 0, 0, 1, -62, 28, -47, -60, 92, 29, 18,
        0, 63, 1, -9, 0, 0, -95, -82, 79, 25, 23, -85
      };

      Daemon.Path path = paths.get(0);
      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());
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

      //  Paths found: 1
      //  Path:  exp=1709132908 / 2024-02-28T15:08:28Z  mtu=1280
      //  Path: first hop = 127.0.0.25:31002
      //  pathIf: 0: 1 561850441793808  1-ff00:0:110
      //  pathIf: 1: 10 561850441793824  1-ff00:0:120
      //  pathIf: 2: 210 561850441793824  1-ff00:0:120
      //  pathIf: 3: 105 843325418504720  2-ff00:0:210
      byte[] raw = {
        0, 0, 48, 0, 0, 0, -101, 35, 101, -34, -8, 44, 0, 63, 0, 1, 0, 0, -16, -42, -110, 6, -74,
        25, 0, 63, 0, -46, 0, 10, 28, 63, -128, -4, 58, 66, 0, 63, 0, 0, 0, 105, 53, -90, -75, -124,
        -62, 70
      };
      Daemon.Path path = paths.get(0);
      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(1, controlServer.getAndResetCallCount());
  }
}
