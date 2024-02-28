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
public class SegmentsMinimal111Test extends AbstractSegmentsMinimalTest {

  private static String firstFop110;
  private static MockTopologyServer topoServer;

  @BeforeAll
  public static void beforeAll() {
    topoServer = MockTopologyServer.start("topologies/minimal/ASff00_0_111/topology.json");
    InetSocketAddress topoAddr = topoServer.getAddress();
    firstFop110 = topoServer.getBorderRouterAddressByIA(AS_110);
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
  void caseA_SameNonCoreAS() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_111);
      //  ListService: control
      //  Service: 127.0.0.26:31014
      //  Paths found: 1
      //  Path:  exp=seconds: 1704893251  mtu=1472
      //  Path: first hop =
      Daemon.Path path = paths.get(0);
      assertEquals(0, path.getRaw().size());
      assertEquals(1472, path.getMtu());
      assertEquals(0, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(0, controlServer.getAndResetCallCount());
  }

  @Test
  void caseB_SameIsd_Up() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_110);
      //    scion showpaths 1-ff00:0:110 --sciond 127.0.0.27:30255
      //    Available paths to 1-ff00:0:110
      //    2 Hops:
      //    [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110] MTU: 1472 NextHop: 127.0.0.25:31016 ...

      //  Path:  exp=seconds: 1704824845  mtu=1472
      //  Path: first hop = 127.0.0.25:31016
      //  pathIf: 0: 111 561850441793809  1-ff00:0:111
      //  pathIf: 1: 2 561850441793808  1-ff00:0:110
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, 0, 0, 0, 102, 60,
        101, -99, 59, -51, 0, 63, 0, 111,
        0, 0, -76, 34, 42, -4, -95, -85,
        0, 63, 0, 0, 0, 2, -33, 64,
        -50, 110, -121, 17
      };

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 0, 0);
      checkInfo(rawBB, 18215, 0);
      checkHopField(rawBB, 111, 0);
      checkHopField(rawBB, 0, 2);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1472, path.getMtu());
      assertEquals(firstFop110, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 111, "1-ff00:0:111");
      checkInterface(path, 1, 2, "1-ff00:0:110");
      assertEquals(2, path.getInterfacesCount());
    }

    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(1, controlServer.getAndResetCallCount());
  }

  @Test
  void caseE_SameIsd_UpDown_OneCoreAS() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_112);
      //  Available paths to 1-ff00:0:112
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110 3>453 1-ff00:0:112] MTU: 1450
      //            NextHop: 127.0.0.25:31016 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=seconds: 1704824845  mtu=1450
      //  Path: first hop = 127.0.0.25:31016
      //  pathIf: 0: 111 561850441793809  1-ff00:0:111
      //  pathIf: 1: 2 561850441793808  1-ff00:0:110
      //  pathIf: 2: 3 561850441793808  1-ff00:0:110
      //  pathIf: 3: 453 561850441793810  1-ff00:0:112
      //  hop: 4: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, -128, 0, 0, 102, 60,
        101, -99, 59, -51, 1, 0, 42, 121,
        101, -99, 59, -51, 0, 63, 0, 111,
        0, 0, -76, 34, 42, -4, -95, -85,
        0, 63, 0, 0, 0, 2, -33, 64,
        -50, 110, -121, 17, 0, 63, 0, 0,
        0, 3, 0, -26, 36, -72, 68, 97,
        0, 63, 1, -59, 0, 0, -97, -34,
        -17, -101, -5, -55
      };

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 2, 0);
      checkInfo(rawBB, 18215, 0);
      checkInfo(rawBB, 5701, 1);
      checkHopField(rawBB, 111, 0);
      checkHopField(rawBB, 0, 2);
      checkHopField(rawBB, 0, 3);
      checkHopField(rawBB, 453, 0);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1450, path.getMtu());
      assertEquals(firstFop110, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 111, "1-ff00:0:111");
      checkInterface(path, 1, 2, "1-ff00:0:110");
      checkInterface(path, 2, 3, "1-ff00:0:110");
      checkInterface(path, 3, 453, "1-ff00:0:112");
      assertEquals(4, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(2, controlServer.getAndResetCallCount());
  }

  @Test
  void caseE_SameIsd_UpDownTwoCoreAS() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_121);
      //  Available paths to 1-ff00:0:121
      //  4 Hops:
      //  [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110 1>10
      //            1-ff00:0:120 21>104 1-ff00:0:121]
      //            MTU: 1472 NextHop: 127.0.0.25:31016 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1704913425 / 2024-01-10T19:03:45Z  mtu=1472
      //  Path: first hop = 127.0.0.25:31016
      //  pathIf: 0: 111 561850441793809  1-ff00:0:111
      //  pathIf: 1: 2 561850441793808  1-ff00:0:110
      //  pathIf: 2: 1 561850441793808  1-ff00:0:110
      //  pathIf: 3: 10 561850441793824  1-ff00:0:120
      //  pathIf: 4: 21 561850441793824  1-ff00:0:120
      //  pathIf: 5: 104 561850441793825  1-ff00:0:121
      //  hop: 0: 0
      //  hop: 1: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, -126, 0, 0, -121, 41, 101, -98, -107, -47, 0, 0, -89, -17, 101, -98, -107, -47, 1,
        0, 62, -8, 101, -98, -107, -47, 0, 63, 0, 111, 0, 0, 114, 69, 82, 121, -115, 98, 0, 63, 0,
        0, 0, 2, -30, 18, 58, -46, -115, 127, 0, 63, 0, 1, 0, 0, -4, 64, 25, 86, -39, 95, 0, 63, 0,
        0, 0, 10, 65, -88, 59, 74, -123, -81, 0, 63, 0, 0, 0, 21, -72, 46, 13, -41, 94, -52, 0, 63,
        0, 104, 0, 0, 117, 9, 111, 106, -77, 69
      };

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 2, 2);
      checkInfo(rawBB, 18215, 0);
      checkInfo(rawBB, 26755, 0);
      checkInfo(rawBB, 48280, 1);
      checkHopField(rawBB, 111, 0);
      checkHopField(rawBB, 0, 2);
      checkHopField(rawBB, 1, 0);
      checkHopField(rawBB, 0, 10);
      checkHopField(rawBB, 0, 21);
      checkHopField(rawBB, 104, 0);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1472, path.getMtu());
      assertEquals(firstFop110, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 111, "1-ff00:0:111");
      checkInterface(path, 1, 2, "1-ff00:0:110");
      checkInterface(path, 2, 1, "1-ff00:0:110");
      checkInterface(path, 3, 10, "1-ff00:0:120");
      checkInterface(path, 4, 21, "1-ff00:0:120");
      checkInterface(path, 5, 104, "1-ff00:0:121");
      assertEquals(6, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(3, controlServer.getAndResetCallCount());
  }

  @Test
  void caseF0_SameIsd_UpCore() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_120);
      //  Available paths to 1-ff00:0:120
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110 1>10 1-ff00:0:120]
      //            MTU: 1472 NextHop: 127.0.0.33:31016 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1709074792 / 2024-02-27T22:59:52Z  mtu=1472
      //  Path: first hop = 127.0.0.33:31016
      //  pathIf: 0: 111 561850441793809  1-ff00:0:111
      //  pathIf: 1: 2 561850441793808  1-ff00:0:110
      //  pathIf: 2: 1 561850441793808  1-ff00:0:110
      //  pathIf: 3: 10 561850441793824  1-ff00:0:120
      //  hop: 0: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, -128, 0, 0, 118, 2, 101, -34, 21, 41, 0, 0, -69, -38, 101, -34, 21, 40, 0, 63, 0,
        111, 0, 0, -42, -89, 38, 20, -89, 97, 0, 63, 0, 0, 0, 2, -74, -35, -65, 113, 121, -83, 0,
        63, 0, 1, 0, 0, 80, 119, -69, 123, 22, -126, 0, 63, 0, 0, 0, 10, 10, 95, -95, -101, -4, -24
      };

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 2, 0);
      checkInfo(rawBB, 10619, 0);
      checkInfo(rawBB, 10619, 0);
      checkHopField(rawBB, 111, 0);
      checkHopField(rawBB, 0, 2);
      checkHopField(rawBB, 1, 0);
      checkHopField(rawBB, 0, 10);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1472, path.getMtu());
      String FIRST_HOP = topoServer.getBorderRouterAddressByIA(AS_110);
      assertEquals(FIRST_HOP, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 111, "1-ff00:0:111");
      checkInterface(path, 1, 2, "1-ff00:0:110");
      checkInterface(path, 2, 1, "1-ff00:0:110");
      checkInterface(path, 3, 10, "1-ff00:0:120");
      assertEquals(4, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(2, controlServer.getAndResetCallCount());
  }

  @Test
  void caseF_DifferentIsd_UpCore_2_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_210);
      //  Available paths to 2-ff00:0:210
      //  4 Hops:
      //  [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110 1>10
      //            1-ff00:0:120 210>105 2-ff00:0:210]
      //            MTU: 1280 NextHop: 127.0.0.25:31016 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1704913573 / 2024-01-10T19:06:13Z  mtu=1280
      //  Path: first hop = 127.0.0.25:31016
      //  pathIf: 0: 111 561850441793809  1-ff00:0:111
      //  pathIf: 1: 2 561850441793808  1-ff00:0:110
      //  pathIf: 2: 1 561850441793808  1-ff00:0:110
      //  pathIf: 3: 10 561850441793824  1-ff00:0:120
      //  pathIf: 4: 210 561850441793824  1-ff00:0:120
      //  pathIf: 5: 105 843325418504720  2-ff00:0:210
      //  hop: 0: 0
      //  hop: 1: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, -64, 0, 0, -107, -43, 101, -98, -105, 0, 0, 0, -60, -49, 101, -98, -106, 101, 0,
        63, 0, 111, 0, 0, 120, -18, 10, 98, 23, 27, 0, 63, 0, 0, 0, 2, 115, 81, -65, -103, -46, 75,
        0, 63, 0, 1, 0, 0, 98, -84, 112, -78, -116, 119, 0, 63, 0, -46, 0, 10, -91, -126, 44, 8,
        -86, 36, 0, 63, 0, 0, 0, 105, 127, 70, 14, 113, 60, -12
      };

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 3, 0);
      checkInfo(rawBB, 18215, 0);
      checkInfo(rawBB, 15767, 0);
      checkHopField(rawBB, 111, 0);
      checkHopField(rawBB, 0, 2);
      checkHopField(rawBB, 1, 0);
      checkHopField(rawBB, 210, 10);
      checkHopField(rawBB, 0, 105);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1280, path.getMtu());
      assertEquals(firstFop110, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 111, "1-ff00:0:111");
      checkInterface(path, 1, 2, "1-ff00:0:110");
      checkInterface(path, 2, 1, "1-ff00:0:110");
      checkInterface(path, 3, 10, "1-ff00:0:120");
      checkInterface(path, 4, 210, "1-ff00:0:120");
      checkInterface(path, 5, 105, "2-ff00:0:210");
      assertEquals(6, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(2, controlServer.getAndResetCallCount());
  }

  @Test
  void caseH_DifferentIsd_UpCoreDown_2_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_211);
      //  Available paths to 2-ff00:0:211
      //  5 Hops:
      //  [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110 1>10
      //            1-ff00:0:120 210>105 2-ff00:0:210 450>503
      //            2-ff00:0:211]
      //            MTU: 1280 NextHop: 127.0.0.25:31016 Status: alive LocalIP: 127.0.0.1

      //  Path:  exp=1704913425 / 2024-01-10T19:03:45Z  mtu=1280
      //  Path: first hop = 127.0.0.25:31016
      //  pathIf: 0: 111 561850441793809  1-ff00:0:111
      //  pathIf: 1: 2 561850441793808  1-ff00:0:110
      //  pathIf: 2: 1 561850441793808  1-ff00:0:110
      //  pathIf: 3: 10 561850441793824  1-ff00:0:120
      //  pathIf: 4: 210 561850441793824  1-ff00:0:120
      //  pathIf: 5: 105 843325418504720  2-ff00:0:210
      //  pathIf: 6: 450 843325418504720  2-ff00:0:210
      //  pathIf: 7: 503 843325418504721  2-ff00:0:211
      //  hop: 0: 0
      //  hop: 1: 0
      //  hop: 2: 0
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      //  linkType: 0 LINK_TYPE_UNSPECIFIED
      byte[] raw = {
        0, 0, 32, -62, 0, 0, 55, 4, 101, -98, -78, -48, 0, 0, -93, 73, 101, -98, -78, -31, 1, 0,
        -44, 9, 101, -98, -78, -25, 0, 63, 0, 111, 0, 0, 30, 24, -28, 1, -40, -81, 0, 63, 0, 0, 0,
        2, -111, 103, -15, -45, -31, 52, 0, 63, 0, 1, 0, 0, -7, 108, -89, -126, 51, -13, 0, 63, 0,
        -46, 0, 10, 121, 50, -4, 5, 8, 31, 0, 63, 0, 0, 0, 105, -70, -103, -111, 11, 89, -109, 0,
        63, 0, 0, 1, -62, -38, -106, 40, 117, 39, -116, 0, 63, 1, -9, 0, 0, -80, -103, 89, 88, -21,
        38
      };

      Daemon.Path path = paths.get(0);
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 3, 2);
      checkInfo(rawBB, 18215, 0);
      checkInfo(rawBB, 15767, 0);
      checkInfo(rawBB, 15299, 1);
      checkHopField(rawBB, 111, 0);
      checkHopField(rawBB, 0, 2);
      checkHopField(rawBB, 1, 0);
      checkHopField(rawBB, 210, 10);
      checkHopField(rawBB, 0, 105);
      checkHopField(rawBB, 0, 450);
      checkHopField(rawBB, 503, 0);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(1280, path.getMtu());
      assertEquals(firstFop110, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 111, "1-ff00:0:111");
      checkInterface(path, 1, 2, "1-ff00:0:110");
      checkInterface(path, 2, 1, "1-ff00:0:110");
      checkInterface(path, 3, 10, "1-ff00:0:120");
      checkInterface(path, 4, 210, "1-ff00:0:120");
      checkInterface(path, 5, 105, "2-ff00:0:210");
      checkInterface(path, 6, 450, "2-ff00:0:210");
      checkInterface(path, 7, 503, "2-ff00:0:211");
      assertEquals(8, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(3, controlServer.getAndResetCallCount());
  }
}
