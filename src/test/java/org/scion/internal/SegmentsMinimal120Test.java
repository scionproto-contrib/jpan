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
import org.scion.demo.util.ToStringUtil;
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
public class SegmentsMinimal120Test extends AbstractSegmentsMinimalTest {
  private static String firstFop210;
  private static MockTopologyServer topoServer;

  @BeforeAll
  public static void beforeAll() {
    topoServer = MockTopologyServer.start("topologies/minimal/ASff00_0_120/topology.json");
    InetSocketAddress topoAddr = topoServer.getAddress();
    firstFop210 = topoServer.getBorderRouterAddressByIA(AS_210);
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
      //  $ scion showpaths 2-ff00:0:211 --sciond 127.0.0.76:30255
      //  Available paths to 2-ff00:0:211
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:120 210>105 2-ff00:0:210 450>503 2-ff00:0:211]
      //            MTU: 1280 NextHop: 127.0.0.74:31012 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1708981044 / 2024-02-26T20:57:24Z  mtu=1280
      //  Path: first hop = 127.0.0.74:31012
      //  pathIf: 0: 210 561850441793824  1-ff00:0:120
      //  pathIf: 1: 105 843325418504720  2-ff00:0:210
      //  pathIf: 2: 450 843325418504720  2-ff00:0:210
      //  pathIf: 3: 503 843325418504721  2-ff00:0:211
      //  hop: 0: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, -128, 0, 0, 103, -107, 101, -36, -90, -12, 1, 0, 17, -78, 101, -36, -90, -6, 0,
        63, 0, -46, 0, 0, -24, 17, 75, 82, -115, -68, 0, 63, 0, 0, 0, 105, -79, -107, -100, 0, -114,
        -102, 0, 63, 0, 0, 1, -62, 122, -16, 77, -87, -117, -18, 0, 63, 1, -9, 0, 0, 29, 110, -26,
        -105, -8, -28
      };

      System.out.println(ToStringUtil.pathLong(raw)); // TODO
      System.out.println(ToStringUtil.path(raw)); // TODO
      Daemon.Path path = paths.get(0);
      System.out.println(ToStringUtil.path(path.getRaw().toByteArray())); // TODO
      System.out.println(ToStringUtil.pathLong(path.getRaw().toByteArray())); // TODO
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 2, 0);
      checkInfo(rawBB, 10619, 0);
      checkInfo(rawBB, 15299, 1);
      checkHopField(rawBB, 210, 0);
      checkHopField(rawBB, 0, 105);
      checkHopField(rawBB, 0, 450);
      checkHopField(rawBB, 503, 0);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1280, path.getMtu());
      assertEquals(firstFop210, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 210, "1-ff00:0:120");
      checkInterface(path, 1, 105, "2-ff00:0:210");
      checkInterface(path, 2, 450, "2-ff00:0:210");
      checkInterface(path, 3, 503, "2-ff00:0:211");
      assertEquals(4, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(2, controlServer.getAndResetCallCount());
  }

  @Test
  void caseI_DifferentIsd_Core_1_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_120, AS_210);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      // $ scion showpaths 2-ff00:0:210 --sciond 127.0.0.76:30255
      // Available paths to 2-ff00:0:210
      // 2 Hops:
      // [0] Hops: [1-ff00:0:120 210>105 2-ff00:0:210]
      //           MTU: 1280 NextHop: 127.0.0.74:31012 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1708980221 / 2024-02-26T20:43:41Z  mtu=1280
      //  Path: first hop = 127.0.0.74:31012
      //  pathIf: 0: 210 561850441793824  1-ff00:0:120
      //  pathIf: 1: 105 843325418504720  2-ff00:0:210
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, 0, 0, 0, 21, -99, 101, -36, -93, -67, 0, 63, 0, -46, 0, 0, 9, -38, 105, -93, -41,
        -97, 0, 63, 0, 0, 0, 105, 92, -28, 91, 82, -63, 23
      };

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 0, 0);
      checkInfo(rawBB, 10619, 0);
      checkHopField(rawBB, 210, 0);
      checkHopField(rawBB, 0, 105);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1280, path.getMtu());
      assertEquals(firstFop210, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 210, "1-ff00:0:120");
      checkInterface(path, 1, 105, "2-ff00:0:210");
      assertEquals(2, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(1, controlServer.getAndResetCallCount());
  }
}
