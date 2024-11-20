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

package org.scion.jpan.internal;

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
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionService;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.testutil.DNSUtil;
import org.scion.jpan.testutil.MockBootstrapServer;
import org.scion.jpan.testutil.MockControlServer;

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
public class SegmentsMinimal211Test extends AbstractSegmentsMinimalTest {

  private static String firstHop210;
  private static MockBootstrapServer topoServer;

  @BeforeAll
  public static void beforeAll() {
    topoServer = MockBootstrapServer.start(CFG_MINIMAL, "ASff00_0_211/topology.json");
    InetSocketAddress topoAddr = topoServer.getAddress();
    firstHop210 = topoServer.getBorderRouterAddressByIA(AS_210);
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
  void caseB_Up() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_211, AS_210);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  $ scion showpaths 2-ff00:0:210 --sciond 127.0.0.99:30255
      //  Available paths to 2-ff00:0:210
      //  2 Hops:
      //  [0] Hops: [2-ff00:0:211 503>450 2-ff00:0:210]
      //            MTU: 1280 NextHop: 127.0.0.97:31052 Status: alive LocalIP: 127.0.0.1


      //  Path:  exp=1732141476 / 2024-11-20T22:24:36Z  mtu=1280
      //  Path: first hop = 127.0.0.97:31052
      //  pathIf: 0: 503 843325418504721  2-ff00:0:211
      //  pathIf: 1: 450 843325418504720  2-ff00:0:210
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {0, 0, 32, 0, 0, 0, 20, -94, 103, 62, 13, 68, 0, 63, 1, -9, 0, 0, -5, 79, -123,
              -14, -82, -105, 0, 63, 0, 0, 1, -62, 24, 79, 52, 50, -112, 53};

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 0, 0);
      checkInfo(rawBB, 18215, 0);
      checkHopField(rawBB, 503, 0);
      checkHopField(rawBB, 0, 450);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1280, path.getMtu());
      assertEquals(firstHop210, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 503, "2-ff00:0:211");
      checkInterface(path, 1, 450, "2-ff00:0:210");
      assertEquals(2, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertTrue(controlServer.getAndResetCallCount() <= 3);
  }
}
