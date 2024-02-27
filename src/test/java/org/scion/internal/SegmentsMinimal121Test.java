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
 * Test cases: (with references to book p105 Fig. 5.8)<br>
 * A (-): srcISD == dstISD; src == dst; (same ISD, same AS); Book: -<br>
 * B (UP): srcISD == dstISD; dst == core; (same ISD, dst is core); Book: -<br>
 * C (DOWN): srcISD == dstISD; src == core; (same ISD, dst is core); Book: 2b<br>
 * D (CORE): srcISD == dstISD; src == core, dst == core; (same ISD, src/dst are cores); Book: 1c<br>
 * E (UP, DOWN): srcISD == dstISD; (same ISD, src/dst are non-cores); Book: 1d,2a,4a<br>
 * F0 (UP, CORE): srcISD == dstISD; dst == core; (same ISD, dst is core); Book: 1b<br>
 * F (UP, CORE): srcISD != dstISD; dst == core; (different ISDs, dst is core); Book: 1b<br>
 * G (CORE, DOWN): srcISD != dstISD; src == core; (different ISDs, src is cores); Book: -<br>
 * H (UP, CORE, DOWN): srcISD != dstISD; (different ISDs, src/dst are non-cores); Book: 1a<br>
 * I (CORE): srcISD != dstISD; (different ISDs, src/dst are cores); Book: 1c<br>
 */
public class SegmentsMinimal121Test extends AbstractSegmentsMinimalTest {

  private static String firstHop120 = "127.0.0.81:31042";
  private static MockTopologyServer topoServer;

  @BeforeAll
  public static void beforeAll() {
    topoServer = MockTopologyServer.start("topologies/minimal/ASff00_0_121/topology.json");
    InetSocketAddress topoAddr = topoServer.getAddress();
    firstHop120 = topoServer.getBorderRouterAddressByIA(AS_120);
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
  void caseF_DifferentIsd_UpCore_1_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_121, AS_210);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  $ scion showpaths 2-ff00:0:210 --sciond 127.0.0.83:30255
      //  Available paths to 2-ff00:0:210
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:121 104>21 1-ff00:0:120 210>105 2-ff00:0:210]
      //            MTU: 1280 NextHop: 127.0.0.81:31042 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1708982875 / 2024-02-26T21:27:55Z  mtu=1280
      //  Path: first hop = 127.0.0.81:31042
      //  pathIf: 0: 104 561850441793825  1-ff00:0:121
      //  pathIf: 1: 21 561850441793824  1-ff00:0:120
      //  pathIf: 2: 210 561850441793824  1-ff00:0:120
      //  pathIf: 3: 105 843325418504720  2-ff00:0:210
      //  hop: 0: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, -128, 0, 0, -94, -77, 101, -36, -82, 27, 0, 0, -99, 68, 101, -36, -82, 126, 0, 63,
        0, 104, 0, 0, 31, 26, -8, 80, -30, 7, 0, 63, 0, 0, 0, 21, -52, -74, -110, -77, -24, 113, 0,
        63, 0, -46, 0, 0, 59, -13, -112, -6, -2, -117, 0, 63, 0, 0, 0, 105, 49, -83, -44, -68, -87,
        -39
      };

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 2, 0);
      checkInfo(rawBB, 18215, 0);
      checkInfo(rawBB, 15767, 0);
      checkHopField(rawBB, 104, 0);
      checkHopField(rawBB, 0, 21);
      checkHopField(rawBB, 210, 0);
      checkHopField(rawBB, 0, 105);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1280, path.getMtu());
      assertEquals(firstHop120, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 104, "1-ff00:0:121");
      checkInterface(path, 1, 21, "1-ff00:0:120");
      checkInterface(path, 2, 210, "1-ff00:0:120");
      checkInterface(path, 3, 105, "2-ff00:0:210");
      assertEquals(4, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(2, controlServer.getAndResetCallCount());
  }

  @Test
  void caseH_DifferentIsd_UpCoreDown_1_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_121, AS_211);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  $ scion showpaths 2-ff00:0:211 --sciond 127.0.0.83:30255
      //  Available paths to 2-ff00:0:211
      //  4 Hops:
      //  [0] Hops: [1-ff00:0:121 104>21 1-ff00:0:120 210>105 2-ff00:0:210 450>503 2-ff00:0:211]
      //            MTU: 1280 NextHop: 127.0.0.81:31042 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1708982875 / 2024-02-26T21:27:55Z  mtu=1280
      //  Path: first hop = 127.0.0.81:31042
      //  pathIf: 0: 104 561850441793825  1-ff00:0:121
      //  pathIf: 1: 21 561850441793824  1-ff00:0:120
      //  pathIf: 2: 210 561850441793824  1-ff00:0:120
      //  pathIf: 3: 105 843325418504720  2-ff00:0:210
      //  pathIf: 4: 450 843325418504720  2-ff00:0:210
      //  pathIf: 5: 503 843325418504721  2-ff00:0:211
      //  hop: 0: 0
      //  hop: 1: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, -126, 0, 0, -94, -77, 101, -36, -82, 27, 0, 0, -99, 68, 101, -36, -82, 126, 1, 0,
        -87, -39, 101, -36, -82, 126, 0, 63, 0, 104, 0, 0, 31, 26, -8, 80, -30, 7, 0, 63, 0, 0, 0,
        21, -52, -74, -110, -77, -24, 113, 0, 63, 0, -46, 0, 0, 59, -13, -112, -6, -2, -117, 0, 63,
        0, 0, 0, 105, 49, -83, -44, -68, -87, -39, 0, 63, 0, 0, 1, -62, 14, 45, 18, -111, -2, -106,
        0, 63, 1, -9, 0, 0, -53, 7, -112, 22, 32, -23
      };

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 2, 2);
      checkInfo(rawBB, 18215, 0);
      checkInfo(rawBB, 15767, 0);
      checkInfo(rawBB, 15299, 1);
      checkHopField(rawBB, 104, 0);
      checkHopField(rawBB, 0, 21);
      checkHopField(rawBB, 210, 0);
      checkHopField(rawBB, 0, 105);
      checkHopField(rawBB, 0, 450);
      checkHopField(rawBB, 503, 0);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1280, path.getMtu());
      assertEquals(firstHop120, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 104, "1-ff00:0:121");
      checkInterface(path, 1, 21, "1-ff00:0:120");
      checkInterface(path, 2, 210, "1-ff00:0:120");
      checkInterface(path, 3, 105, "2-ff00:0:210");
      checkInterface(path, 4, 450, "2-ff00:0:210");
      checkInterface(path, 5, 503, "2-ff00:0:211");
      assertEquals(6, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(3, controlServer.getAndResetCallCount());
  }
}
