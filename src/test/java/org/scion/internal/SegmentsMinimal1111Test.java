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
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.PackageVisibilityHelper;
import org.scion.Scion;
import org.scion.ScionUtil;
import org.scion.demo.util.ToStringUtil;
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
 * F (UP, CORE): srcISD != dstISD; dst == core; (different ISDs, dst is core); Book: 1b<br>
 * G (CORE, DOWN): srcISD != dstISD; src == core; (different ISDs, src is cores); Book: -<br>
 * H (UP, CORE, DOWN): srcISD != dstISD; (different ISDs, src/dst are non-cores); Book: 1a<br>
 * I (CORE): srcISD != dstISD; (different ISDs, src/dst are cores); Book: 1c<br>
 */
public class SegmentsMinimal1111Test extends SegmentsMinimalTest {

  private static final String FIRST_HOP = "127.0.0.41:31024"; // TODO read from TOPO
  private static MockTopologyServer topoServer;

  @BeforeAll
  public static void beforeAll() throws IOException {
    topoServer =
        MockTopologyServer.start(Paths.get("topologies/minimal/ASff00_0_1111/topology.json"));
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
  }

  @Test
  void caseA_SameNonCoreAS() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_1111, AS_1111);
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
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_1111, AS_110);
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
      byte[] raw = {0, 0, 48, 0, 0, 0, -29, -115, 101, -98, -67, -19, 0, 63, 0, 123, 0, 0, -28, 18, 40, -128, 23, -125, 0, 63, 0, 111, 4, 87, 119, 122, 17, -93, 63, -79, 0, 63, 0, 0, 0, 2, 12, 90, -19, -90, -67, 121};

      System.out.println(ToStringUtil.pathLong(raw)); // TODO
      System.out.println(ToStringUtil.path(raw)); // TODO
      Daemon.Path path = paths.get(0);
      System.out.println(ToStringUtil.path(path.getRaw().toByteArray())); // TODO
      System.out.println(ToStringUtil.pathLong(path.getRaw().toByteArray())); // TODO
      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 3, 0, 0);
      checkInfo(rawBB, 10619, 0);
      checkHopField(rawBB, 123, 0);
      checkHopField(rawBB, 111, 1111);
      checkHopField(rawBB, 0, 2);
      assertEquals(0, rawBB.remaining());

      // compare with recorded byte[]
      checkRaw(raw, path.getRaw().toByteArray());

      assertEquals(0, path.getMtu()); // TODO ? Why not 1472 as for 111->110?
      assertEquals(FIRST_HOP, path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 123, "1-ff00:0:1111");
      checkInterface(path, 1, 1111, "1-ff00:0:111");
      checkInterface(path, 2, 0, "1-ff00:0:110");
      checkInterface(path, 3, 2, "1-ff00:0:110");
      assertEquals(3, path.getInterfacesCount());
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
      assertEquals("127.0.0.25:31016", path.getInterface().getAddress().getAddress());
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
      assertEquals("127.0.0.25:31016", path.getInterface().getAddress().getAddress());
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

  @Disabled
  @Test
  void caseF_DifferentIsd_UpCore_1_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_121, AS_210);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  Available paths to 2-ff00:0:210
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:121 104>2 1-ff00:0:120 1>105 2-ff00:0:210] MTU: 1280
      //             NextHop: 127.0.0.49:31024 Status: alive LocalIP: 127.0.0.1

    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(3, controlServer.getAndResetCallCount());
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

      System.out.println(ToStringUtil.pathLong(raw));
      System.out.println(ToStringUtil.path(raw));

      Daemon.Path path = paths.get(0);
      System.out.println(ToStringUtil.path(path.getRaw().toByteArray())); // TODO
      System.out.println(ToStringUtil.pathLong(path.getRaw().toByteArray()));
      //      System.out.println(ToStringUtil.path(path.getRaw().toByteArray())); // TODO
      //      System.out.println(ToStringUtil.pathLong(path.getRaw().toByteArray()));
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
      assertEquals("127.0.0.25:31016", path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 111, "1-ff00:0:111");
      checkInterface(path, 1, 2, "1-ff00:0:110");
      checkInterface(path, 2, 1, "1-ff00:0:110");
      checkInterface(path, 3, 10, "1-ff00:0:120");
      checkInterface(path, 4, 0, "2-ff00:0:210");
      assertEquals(5, path.getInterfacesCount()); // TODO!
      //checkInterface(path, 4, 1, "1-ff00:0:120");
      //checkInterface(path, 5, 105, "2-ff00:0:210");
      assertEquals(6, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(2, controlServer.getAndResetCallCount());
  }

  @Disabled
  @Test
  void caseH_DifferentIsd_UpCoreDown_1_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_121, AS_211);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  Available paths to 2-ff00:0:211
      //  4 Hops:
      //  [0] Hops: [1-ff00:0:121 104>2 1-ff00:0:120 1>105 2-ff00:0:210 450>503 2-ff00:0:211]
      //             MTU: 1280 NextHop: 127.0.0.49:31024 Status: alive LocalIP: 127.0.0.1
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(3, controlServer.getAndResetCallCount());
  }

  @Test
  void caseH_DifferentIsd_UpCoreDown_2_Hop() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_211);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
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
      byte[] raw2 = {
        0, 0, 32, -62, 0, 0, -121, 41, 101, -98, -107, -47, 0, 0, -60, -49, 101, -98, -106, 101, 1,
        0, -18, -110, 101, -98, -106, -48, 0, 63, 0, 111, 0, 0, 114, 69, 82, 121, -115, 98, 0, 63,
        0, 0, 0, 2, -30, 18, 58, -46, -115, 127, 0, 63, 0, 1, 0, 0, 98, -84, 112, -78, -116, 119, 0,
        63, 0, -46, 0, 10, -91, -126, 44, 8, -86, 36, 0, 63, 0, 0, 0, 105, 127, 70, 14, 113, 60,
        -12, 0, 63, 0, 0, 1, -62, 22, 103, -61, -48, -53, -108, 0, 63, 1, -9, 0, 0, 91, 23, 96, 25,
        122, -26
      };
      byte[] raw = {0, 0, 32, -62, 0, 0, 55, 4, 101, -98, -78, -48, 0, 0, -93, 73, 101, -98, -78, -31, 1, 0, -44, 9, 101, -98, -78, -25, 0, 63, 0, 111, 0, 0, 30, 24, -28, 1, -40, -81, 0, 63, 0, 0, 0, 2, -111, 103, -15, -45, -31, 52, 0, 63, 0, 1, 0, 0, -7, 108, -89, -126, 51, -13, 0, 63, 0, -46, 0, 10, 121, 50, -4, 5, 8, 31, 0, 63, 0, 0, 0, 105, -70, -103, -111, 11, 89, -109, 0, 63, 0, 0, 1, -62, -38, -106, 40, 117, 39, -116, 0, 63, 1, -9, 0, 0, -80, -103, 89, 88, -21, 38};


      System.out.println(ToStringUtil.pathLong(raw));
      System.out.println(ToStringUtil.path(raw));

      Daemon.Path path = paths.get(0);
      System.out.println(ToStringUtil.path(path.getRaw().toByteArray())); // TODO
      System.out.println(ToStringUtil.pathLong(path.getRaw().toByteArray()));
      //      System.out.println(ToStringUtil.path(path.getRaw().toByteArray())); // TODO
      //      System.out.println(ToStringUtil.pathLong(path.getRaw().toByteArray()));
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
      assertEquals("127.0.0.25:31016", path.getInterface().getAddress().getAddress());
      checkInterface(path, 0, 111, "1-ff00:0:111");
      checkInterface(path, 1, 2, "1-ff00:0:110");
      checkInterface(path, 2, 1, "1-ff00:0:110");
      checkInterface(path, 3, 10, "1-ff00:0:120");
      checkInterface(path, 4, 0, "2-ff00:0:210");
      checkInterface(path, 5, 450, "2-ff00:0:210");
      checkInterface(path, 6, 503, "2-ff00:0:211");
      // checkInterface(path, 4, 210, "1-ff00:0:120");
      // checkInterface(path, 5, 105, "2-ff00:0:210");
      // checkInterface(path, 6, 450, "2-ff00:0:210");
      // checkInterface(path, 7, 503, "2-ff00:0:211");
      assertEquals(8, path.getInterfacesCount());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(3, controlServer.getAndResetCallCount());
  }

  private static void checkMetaHeader(
      ByteBuffer rawBB, int hopCount0, int hopCount1, int hopCount2) {
    int bits = (((hopCount0 << 6) | hopCount1) << 6) | hopCount2;
    assertEquals(bits, rawBB.getInt()); // MetaHeader
  }

  private static void checkInfo(ByteBuffer rawBB, int segmentId, int flags) {
    assertEquals(flags, rawBB.get()); // Info0 flags
    assertEquals(0, rawBB.get()); // Info0 etc
    assertEquals(segmentId, ByteUtil.toUnsigned(rawBB.getShort())); // Info0 SegID
    assertNotEquals(0, rawBB.getInt()); // Info0 timestamp
  }

  private static void checkHopField(ByteBuffer rawBB, int ingress, int egress) {
    assertEquals(63, rawBB.getShort()); // Hop0 flags/expiry
    assertEquals(ingress, rawBB.getShort()); // Hop0 ingress
    assertEquals(egress, rawBB.getShort()); // Hop0 egress
    assertNotEquals(0, rawBB.getShort()); // Hop0 MAC
    assertNotEquals(0, rawBB.getInt()); // Hop0 MAC
  }

  private void checkInterface(Daemon.Path path, int i, int id, String isdAs) {
    assertEquals(id, path.getInterfaces(i).getId());
    System.out.println("IA:" + ScionUtil.toStringIA(path.getInterfaces(i).getIsdAs())); // TODO
    assertEquals(ScionUtil.parseIA(isdAs), path.getInterfaces(i).getIsdAs());
  }

  private void checkRaw(byte[] exp, byte[] act) {
    // Path meta header
    for (int i = 0; i < 4; i++) {
      assertEquals(exp[i], act[i], "ofs=" + i);
    }
    int pmh = ByteBuffer.wrap(exp).getInt();
    int s0 = (pmh << 14) >>> (12 + 14);
    int s1 = (pmh << 20) >>> (6 + 20);
    int s2 = (pmh << 26) >>> 26;
    int s = s0 + s1 + s2;

    int ofs = 4;

    // info fields
    assertEquals(exp[ofs], act[ofs++]); // flags
    assertEquals(exp[ofs], act[ofs++]); // RSV
    ofs += 6;
    if (s1 > 0) {
      assertEquals(exp[ofs], act[ofs++]);
      assertEquals(exp[ofs], act[ofs++]);
      ofs += 6;
    }
    if (s2 > 0) {
      assertEquals(exp[ofs], act[ofs++]);
      assertEquals(exp[ofs], act[ofs++]);
      ofs += 6;
    }

    // hop fields
    for (int h = 0; h < s; h++) {
      for (int i = 0; i < 6; i++) {
        assertEquals(exp[ofs], act[ofs++]);
      }
      ofs += 6;
    }

    assertEquals(ofs, exp.length);
    assertEquals(ofs, act.length);
  }
}
