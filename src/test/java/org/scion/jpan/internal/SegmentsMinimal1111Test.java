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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.DNSUtil;
import org.scion.jpan.testutil.MockNetwork2;

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
class SegmentsMinimal1111Test extends AbstractSegmentsTest {

  private static String firstHop;
  private static MockNetwork2 network;

  @BeforeAll
  static void beforeAll() {
    network = MockNetwork2.start(MockNetwork2.Topology.MINIMAL, "ASff00_0_1111");
    firstHop = network.getTopoServer().getBorderRouterAddressByIA(AS_111);
  }

  @AfterEach
  void afterEach() {
    network.getTopoServer().getAndResetCallCount();
    network.getControlServer().getAndResetCallCount();
  }

  @AfterAll
  static void afterAll() {
    network.close();
    DNSUtil.clear();
    // Defensive clean up
    ScionService.closeDefault();
    System.clearProperty(Constants.PROPERTY_RESOLVER_MINIMIZE_REQUESTS);
  }

  @Test
  void caseA_SameNonCoreAS() {
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_1111, AS_1111);
      //  ListService: control
      //  Service: 127.0.0.26:31014
      //  Paths found: 1
      //  Path:  exp=seconds: 1704893251  mtu=1472
      //  Path: first hop =
      PathMetadata path = paths.get(0);
      assertEquals(0, path.getRawPath().length);
      assertEquals(1472, path.getMtu());
      assertEquals(0, path.getInterfaces().size());
    }
    assertEquals(1, network.getTopoServer().getAndResetCallCount());
    assertEquals(0, network.getControlServer().getAndResetCallCount());
  }

  @Test
  void caseB_SameIsd_Up() {
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_1111, AS_110);
      //  $ scion showpaths 1-ff00:0:110 --sciond 127.0.0.43:30255
      //  Available paths to 1-ff00:0:110
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:1111 123>1111 1-ff00:0:111 111>2 1-ff00:0:110]
      //            MTU: 1472 NextHop: 127.0.0.41:31024 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1704923693 / 2024-01-10T21:54:53Z  mtu=1472
      //  Path: first hop = 127.0.0.41:31024
      //  pathIf: 0: 123 561850441797905  1-ff00:0:1111
      //  pathIf: 1: 1111 561850441793809  1-ff00:0:111
      //  pathIf: 2: 111 561850441793809  1-ff00:0:111
      //  pathIf: 3: 2 561850441793808  1-ff00:0:110
      //  hop: 0: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 48, 0, 0, 0, -29, -115, 101, -98, -67, -19, 0, 63, 0, 123, 0, 0, -28, 18, 40, -128,
        23, -125, 0, 63, 0, 111, 4, 87, 119, 122, 17, -93, 63, -79, 0, 63, 0, 0, 0, 2, 12, 90, -19,
        -90, -67, 121
      };

      PathMetadata path = paths.get(0);
      ByteBuffer rawBB = ByteBuffer.wrap(path.getRawPath()).asReadOnlyBuffer();
      checkMetaHeader(rawBB, 3, 0, 0);
      checkInfo(rawBB, 9744, 0);
      checkHopField(rawBB, 123, 0);
      checkHopField(rawBB, 111, 1111);
      checkHopField(rawBB, 0, 2);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRawPath());

      assertEquals(1460, path.getMtu());
      assertEquals(firstHop, path.getLocalInterface().getAddress());
      checkInterface(path, 0, 123, "1-ff00:0:1111");
      checkInterface(path, 1, 1111, "1-ff00:0:111");
      checkInterface(path, 2, 111, "1-ff00:0:111");
      checkInterface(path, 3, 2, "1-ff00:0:110");
      assertEquals(4, path.getInterfaces().size());
    }

    assertEquals(1, network.getTopoServer().getAndResetCallCount());
    assertEquals(1, network.getControlServer().getAndResetCallCount());
  }

  @Test
  void caseE_SameIsd_UpDown_OneCoreAS_Shortcut() {
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_1111, AS_1112);
      //  Available paths to 1-ff00:0:1112
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:1111 123>1111 1-ff00:0:111 1112>234 1-ff00:0:1112]
      //  MTU: 1472 NextHop: 127.0.0.41:31024 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1704928949 / 2024-01-10T23:22:29Z  mtu=1472
      //  Path: first hop = 127.0.0.41:31024
      //  pathIf: 0: 123 561850441797905  1-ff00:0:1111
      //  pathIf: 1: 1111 561850441793809  1-ff00:0:111
      //  pathIf: 2: 1112 561850441793809  1-ff00:0:111
      //  pathIf: 3: 234 561850441797906  1-ff00:0:1112
      //  hop: 0: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, -128, 0, 0, -29, -10, 102, -126, -73, -34, 1, 0, 98, -73, 102, -126, -72, 66, 0,
        63, 0, 123, 0, 0, 29, 108, 17, 27, -111, -85, 0, 63, 0, 111, 4, 87, -71, 79, 79, -52, 14,
        -35, 0, 63, 0, 111, 4, 88, -112, -22, -128, -13, 23, -100, 0, 63, 0, -22, 0, 0, -42, -5,
        -68, -22, 100, 105
      };

      PathMetadata path = paths.get(0);
      ByteBuffer rawBB = ByteBuffer.wrap(path.getRawPath()).asReadOnlyBuffer();
      checkMetaHeader(rawBB, 2, 2, 0);
      checkInfo(rawBB, 18215, 0);
      checkInfo(rawBB, 9744, 1);
      checkHopField(rawBB, 123, 0);
      checkHopField(rawBB, 111, 1111);
      checkHopField(rawBB, 111, 1112);
      checkHopField(rawBB, 234, 0);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRawPath());

      assertEquals(1472, path.getMtu());
      assertEquals("127.0.0.41:31024", path.getLocalInterface().getAddress());
      checkInterface(path, 0, 123, "1-ff00:0:1111");
      checkInterface(path, 1, 1111, "1-ff00:0:111");
      checkInterface(path, 2, 1112, "1-ff00:0:111");
      checkInterface(path, 3, 234, "1-ff00:0:1112");
      assertEquals(4, path.getInterfaces().size());
    }
    assertEquals(1, network.getTopoServer().getAndResetCallCount());
    assertEquals(3, network.getControlServer().getAndResetCallCount());
  }

  @Test
  void caseE_SameIsd_UpDown_OneCoreAS_OnPathUp() {
    caseE_SameIsd_UpDown_OneCoreAS_OnPathUp(false);
  }

  @Test
  void caseE_SameIsd_UpDown_OneCoreAS_OnPathUp_MinRequests() {
    caseE_SameIsd_UpDown_OneCoreAS_OnPathUp(true);
  }

  private void caseE_SameIsd_UpDown_OneCoreAS_OnPathUp(boolean minRequests) {
    System.setProperty(
        Constants.PROPERTY_RESOLVER_MINIMIZE_REQUESTS, Boolean.toString(minRequests));
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_1111, AS_111);
      //  Available paths to 1-ff00:0:1112
      //  2 Hops:
      //  Hops: [1-ff00:0:1111 123>1111 1-ff00:0:111] MTU: 1472 NextHop: 127.0.0.41:31024
      //  [0] Hops: [1-ff00:0:1111 123>1111 1-ff00:0:111 1112>234 1-ff00:0:1112]
      //  MTU: 1472 NextHop: 127.0.0.41:31024 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1721068881 / 2024-07-15T18:41:21Z  mtu=1472
      //  Path: first hop = 127.0.0.41:31024
      //  pathIf: 0: 123 561850441797905  1-ff00:0:1111
      //  pathIf: 1: 1111 561850441793809  1-ff00:0:111
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, 0, 0, 0, -51, 26, 102, -107, 24, -15, 0, 63, 0, 123, 0, 0, 63, -75, 36, -64, 69,
        73, 0, 63, 0, 111, 4, 87, -29, 103, -48, 31, -82, -75
      };

      //      System.out.println(ToStringUtil.pathLong(raw));
      //      System.out.println(ToStringUtil.path(raw));
      PathMetadata path = paths.get(0);
      //      System.out.println(ToStringUtil.path(path.getRaw().toByteArray()));
      //      System.out.println(ToStringUtil.pathLong(path.getRaw().toByteArray()));

      ByteBuffer rawBB = ByteBuffer.wrap(path.getRawPath()).asReadOnlyBuffer();
      checkMetaHeader(rawBB, 2, 0, 0);
      checkInfo(rawBB, 18215, 0);
      checkHopField(rawBB, 123, 0);
      checkHopField(rawBB, 111, 1111);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRawPath());

      assertEquals(1472, path.getMtu());
      assertEquals("127.0.0.41:31024", path.getLocalInterface().getAddress());
      checkInterface(path, 0, 123, "1-ff00:0:1111");
      checkInterface(path, 1, 1111, "1-ff00:0:111");
      assertEquals(2, path.getInterfaces().size());
    }
    assertEquals(1, network.getTopoServer().getAndResetCallCount());
    assertEquals(minRequests ? 1 : 3, network.getControlServer().getAndResetCallCount());
  }

  @Test
  void caseE_SameIsd_UpDown_OneCoreAS_b() {
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_1111, AS_1121);
      //  Available paths to 1-ff00:0:1121
      //  5 Hops:
      //  [0] Hops: [1-ff00:0:1111 123>1111 1-ff00:0:111 111>2
      //            1-ff00:0:110 3>453 1-ff00:0:112 1121>345
      //            1-ff00:0:1121]
      //            MTU: 1450 NextHop: 127.0.0.41:31024 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1704928949 / 2024-01-10T23:22:29Z  mtu=1450
      //  Path: first hop = 127.0.0.41:31024
      //  pathIf: 0: 123 561850441797905  1-ff00:0:1111
      //  pathIf: 1: 1111 561850441793809  1-ff00:0:111
      //  pathIf: 2: 111 561850441793809  1-ff00:0:111
      //  pathIf: 3: 2 561850441793808  1-ff00:0:110
      //  pathIf: 4: 3 561850441793808  1-ff00:0:110
      //  pathIf: 5: 453 561850441793810  1-ff00:0:112
      //  pathIf: 6: 1121 561850441793810  1-ff00:0:112
      //  pathIf: 7: 345 561850441797921  1-ff00:0:1121
      //  hop: 0: 0
      //  hop: 1: 0
      //  hop: 2: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 48, -64, 0, 0, -26, -50, 101, -98, -46, 117, 1, 0, -118, 0, 101, -98, -46, -80, 0, 63,
        0, 123, 0, 0, 3, -114, 122, -10, -44, -122, 0, 63, 0, 111, 4, 87, -3, 42, 7, 118, 83, 47, 0,
        63, 0, 0, 0, 2, 113, -50, -88, -9, -76, -66, 0, 63, 0, 0, 0, 3, 59, 24, -5, -116, 51, 17, 0,
        63, 1, -59, 4, 97, 31, -30, -83, 78, -88, -45, 0, 63, 1, 89, 0, 0, -98, -49, -64, -72, 77,
        72
      };

      PathMetadata path = paths.get(0);
      ByteBuffer rawBB = ByteBuffer.wrap(path.getRawPath()).asReadOnlyBuffer();
      checkMetaHeader(rawBB, 3, 3, 0);
      checkInfo(rawBB, 10619, 0);
      checkInfo(rawBB, 10003, 1);
      checkHopField(rawBB, 123, 0);
      checkHopField(rawBB, 111, 1111);
      checkHopField(rawBB, 0, 2);
      checkHopField(rawBB, 0, 3);
      checkHopField(rawBB, 453, 1121);
      checkHopField(rawBB, 345, 0);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRawPath());

      assertEquals(1450, path.getMtu());
      assertEquals(firstHop, path.getLocalInterface().getAddress());
      checkInterface(path, 0, 123, "1-ff00:0:1111");
      checkInterface(path, 1, 1111, "1-ff00:0:111");
      checkInterface(path, 2, 111, "1-ff00:0:111");
      checkInterface(path, 3, 2, "1-ff00:0:110");
      checkInterface(path, 4, 3, "1-ff00:0:110");
      checkInterface(path, 5, 453, "1-ff00:0:112");
      checkInterface(path, 6, 1121, "1-ff00:0:112");
      checkInterface(path, 7, 345, "1-ff00:0:1121");
      assertEquals(8, path.getInterfaces().size());
    }
    assertEquals(1, network.getTopoServer().getAndResetCallCount());
    assertEquals(3, network.getControlServer().getAndResetCallCount());
  }
}
