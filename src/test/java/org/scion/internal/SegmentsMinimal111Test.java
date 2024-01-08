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
public class SegmentsMinimal111Test extends SegmentsMinimalTest {

  private static MockTopologyServer topoServer;

  @BeforeAll
  public static void beforeAll() throws IOException {
    topoServer =
        MockTopologyServer.start(Paths.get("topologies/minimal/ASff00_0_111/topology.json"));
    InetSocketAddress topoAddr = topoServer.getAddress();
    DNSUtil.installNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
    controlServer = MockControlServer.start(31014); // TODO get port from topo
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
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_111);
      assertNotNull(paths);
      assertTrue(paths.isEmpty());
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(0, controlServer.getAndResetCallCount());
  }

  @Test
  void caseB_SameIsd_Up() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_110);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      Daemon.Path path = paths.get(0);
      //      Paths found: 1
      //      Path:  exp=seconds: 1704738253
      //      mtu=1472
      //      Path: first hop = 127.0.0.25:31016
      //      pathIf: 0: 111 561850441793809  1-ff00:0:111
      //      pathIf: 0: 2 561850441793808  1-ff00:0:110
      ByteBuffer rawBB = paths.get(0).getRaw().asReadOnlyByteBuffer();
      checkMetaHeader(rawBB, 2, 0, 0);
      checkInfo(rawBB, 18215);
      checkHopField(rawBB, 111, 0);
      checkHopField(rawBB, 0, 2);
      assertEquals(0, rawBB.remaining());

      assertEquals(1472, path.getMtu());
      assertEquals("127.0.0.25:31016", path.getInterface().getAddress().getAddress());
      assertEquals(111, path.getInterfaces(0).getId());
      assertEquals(ScionUtil.parseIA("1-ff00:0:111"), path.getInterfaces(0).getIsdAs());
      assertEquals(0, path.getInterfaces(1).getId());
      assertEquals(ScionUtil.parseIA("1-ff00:0:110"), path.getInterfaces(1).getIsdAs());
    }
    //    scion showpaths 1-ff00:0:110 --sciond 127.0.0.27:30255
    //    Available paths to 1-ff00:0:110
    //    2 Hops:
    //    [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110] MTU: 1472 NextHop: 127.0.0.25:31016 ...

    //  // INFO: 110--->111
    //  ASInfo found: 561850441793808 1-ff00:0:110  core=true  mtu=1472
    //  Interfaces found: 3
    //  Interface: 2 -> address: "127.0.0.18:31004"
    //  Interface: 3 -> address: "127.0.0.19:31006"
    //  Interface: 1 -> address: "127.0.0.17:31002"
    //  Services found: 1
    //  ListService: control
    //  Service: 127.0.0.20:31000
    //  Paths found: 1
    //  Path:  exp=seconds: 1704752044
    //  mtu=1472
    //  Path: interfaces = 127.0.0.18:31004
    //  Path: first hop = 127.0.0.18:31004
    //  pathIf: 0: 2 561850441793808  1-ff00:0:110
    //  pathIf: 1: 111 561850441793809  1-ff00:0:111
    //  linkType: 0 LINK_TYPE_UNSPECIFIED
    //  raw: [0x0, 0x0, 0x20, 0x0, 0x1, 0x0, 0x6, 0x19, 0x65, 0x9c, 0x1f, 0x6c, 0x0, 0x3f, 0x0, 0x0,
    // 0x0, 0x2, 0xf3, 0x59, 0x16, 0x7b, 0x88, 0x0, 0x0, 0x3f, 0x0, 0x6f, 0x0, 0x0, 0x10, 0xb7,
    // 0x87, 0x19, 0x6, 0x54]
    //  raw: {0, 0, 32, 0, 1, 0, 6, 25, 101, -100, 31, 108, 0, 63, 0, 0, 0, 2, -13, 89, 22, 123,
    // -120, 0, 0, 63, 0, 111, 0, 0, 16, -73, -121, 25, 6, 84}

    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(1, controlServer.getAndResetCallCount());
  }

  @Test
  void caseE_SameIsd_UpDown_OneCoreAS() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_112);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  Available paths to 1-ff00:0:112
      //  3 Hops:
      //  [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110 3>453 1-ff00:0:112] MTU: 1450
      //            NextHop: 127.0.0.25:31016 Status: alive LocalIP: 127.0.0.1

      //      byte[] rawExpected = {
      //        0, 0, 32, 0, 0, 0, -49, 44,
      //        101, -101, -14, 88, 0, 63, 0, 111,
      //        0, 0, -9, 121, -36, 67, -12, 18,
      //        0, 63, 0, 0, 0, 2, -91, 8,
      //        -124, -9, 92, 28
      //      };
      //      byte[] rawPath = paths.get(0).getRaw().toByteArray();
      //      System.out.println(ToStringUtil.toStringHex(rawExpected));
      //      System.out.println(ToStringUtil.toStringHex(rawPath));
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(2, controlServer.getAndResetCallCount());
  }

  @Test
  void caseE_SameIsd_UpDownTwoCoreAS() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_111, AS_121);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());

      //  Available paths to 1-ff00:0:121
      //  4 Hops:
      //  [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110 1>10 1-ff00:0:120 2>104 1-ff00:0:121]
      //             MTU: 1472 NextHop: 127.0.0.25:31016 Status: alive LocalIP: 127.0.0.1

      // For info: 121->111
      //  Available paths to 1-ff00:0:111
      //  4 Hops:
      //  [0] Hops: [1-ff00:0:121 104>2 1-ff00:0:120 10>1 1-ff00:0:110 2>111 1-ff00:0:111]
      //             MTU: 1472 NextHop: 127.0.0.49:31024 Status: alive LocalIP: 127.0.0.1

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
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      //  Available paths to 2-ff00:0:210
      //  4 Hops:
      //  [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110 1>10 1-ff00:0:120 1>105 2-ff00:0:210]
      //            MTU: 1280 NextHop: 127.0.0.25:31016 Status: alive LocalIP: 127.0.0.1

      // 111 ---> 210
      //  ASInfo found: 561850441793809 1-ff00:0:111  core=false  mtu=1472
      //  Interfaces found: 1
      //      Interface: 111 -> address: "127.0.0.25:31016"
      //  Services found: 1
      //  ListService: control
      //      Service: 127.0.0.26:31014
      //  Paths found: 1
      //  Path:  exp=seconds: 1704751940
      //    mtu=1280
      //  Path: interfaces = 127.0.0.25:31016
      //  Path: first hop = 127.0.0.25:31016
      //      pathIf: 0: 111 561850441793809  1-ff00:0:111
      //      pathIf: 1: 2 561850441793808  1-ff00:0:110
      //      pathIf: 2: 1 561850441793808  1-ff00:0:110
      //      pathIf: 3: 10 561850441793824  1-ff00:0:120
      //      pathIf: 4: 1 561850441793824  1-ff00:0:120
      //      pathIf: 5: 105 843325418504720  2-ff00:0:210
      //      hop: 6: 0
      //      hop: 6: 0
      //      linkType: 0 LINK_TYPE_UNSPECIFIED
      //      linkType: 0 LINK_TYPE_UNSPECIFIED
      //      linkType: 0 LINK_TYPE_UNSPECIFIED
      //      raw: [0x0, 0x0, 0x20, 0xc0, 0x0, 0x0, 0x4a, 0xfc, 0x65, 0x9c, 0x1f, 0x7, 0x0, 0x0,
      // 0x42, 0x97, 0x65, 0x9c, 0x1f, 0x4, 0x0, 0x3f, 0x0, 0x6f, 0x0, 0x0, 0xb9, 0xe9, 0x56, 0xd7,
      // 0x2c, 0x9c, 0x0, 0x3f, 0x0, 0x0, 0x0, 0x2, 0xe3, 0xac, 0xbd, 0x12, 0x3, 0xa6, 0x0, 0x3f,
      // 0x0, 0x1, 0x0, 0x0, 0x13, 0xf9, 0x11, 0xe8, 0xc0, 0xe7, 0x0, 0x3f, 0x0, 0x1, 0x0, 0xa,
      // 0x47, 0x1, 0x5, 0x2, 0xcd, 0xa6, 0x0, 0x3f, 0x0, 0x0, 0x0, 0x69, 0x70, 0x63, 0xcb, 0x19,
      // 0x70, 0xc8]
      //      raw: {0, 0, 32, -64, 0, 0, 74, -4, 101, -100, 31, 7, 0, 0, 66, -105, 101, -100, 31, 4,
      // 0, 63, 0, 111, 0, 0, -71, -23, 86, -41, 44, -100, 0, 63, 0, 0, 0, 2, -29, -84, -67, 18, 3,
      // -90, 0, 63, 0, 1, 0, 0, 19, -7, 17, -24, -64, -25, 0, 63, 0, 1, 0, 10, 71, 1, 5, 2, -51,
      // -90, 0, 63, 0, 0, 0, 105, 112, 99, -53, 25, 112, -56}

      // 210 ---> 111
      //        ASInfo found: 843325418504720 2-ff00:0:210  core=true  mtu=1280
      //  Interfaces found: 2
      //      Interface: 450 -> address: "127.0.0.58:31030"
      //      Interface: 105 -> address: "127.0.0.57:31028"
      //  Services found: 1
      //  ListService: control
      //      Service: 127.0.0.59:31026
      //  Paths found: 1
      //  Path:  exp=seconds: 1704751737
      //    mtu=1280
      //  Path: interfaces = 127.0.0.57:31028
      //  Path: first hop = 127.0.0.57:31028
      //      pathIf: 0: 105 843325418504720  2-ff00:0:210
      //      pathIf: 1: 1 561850441793824  1-ff00:0:120
      //      pathIf: 2: 10 561850441793824  1-ff00:0:120
      //      pathIf: 3: 1 561850441793808  1-ff00:0:110
      //      pathIf: 4: 2 561850441793808  1-ff00:0:110
      //      pathIf: 5: 111 561850441793809  1-ff00:0:111
      //      hop: 6: 0
      //      hop: 6: 0
      //      linkType: 0 LINK_TYPE_UNSPECIFIED
      //      linkType: 0 LINK_TYPE_UNSPECIFIED
      //      linkType: 0 LINK_TYPE_UNSPECIFIED
      //      raw: [0x0, 0x0, 0x30, 0x80, 0x0, 0x0, 0x65, 0x45, 0x65, 0x9c, 0x1e, 0x39, 0x1, 0x0,
      // 0xb2, 0x3a, 0x65, 0x9c, 0x1e, 0x3e, 0x0, 0x3f, 0x0, 0x69, 0x0, 0x0, 0x57, 0xa6, 0x89, 0x5f,
      // 0x6d, 0xc9, 0x0, 0x3f, 0x0, 0xa, 0x0, 0x1, 0x9, 0xe5, 0x70, 0xcc, 0x83, 0x12, 0x0, 0x3f,
      // 0x0, 0x0, 0x0, 0x1, 0xda, 0x8c, 0xc5, 0x61, 0xde, 0x25, 0x0, 0x3f, 0x0, 0x0, 0x0, 0x2,
      // 0x5f, 0xf0, 0x6, 0xd9, 0xf8, 0x5f, 0x0, 0x3f, 0x0, 0x6f, 0x0, 0x0, 0x86, 0x9d, 0xfa, 0xda,
      // 0x88, 0xaa]
      //      raw: {0, 0, 48, -128, 0, 0, 101, 69, 101, -100, 30, 57, 1, 0, -78, 58, 101, -100, 30,
      // 62, 0, 63, 0, 105, 0, 0, 87, -90, -119, 95, 109, -55, 0, 63, 0, 10, 0, 1, 9, -27, 112, -52,
      // -125, 18, 0, 63, 0, 0, 0, 1, -38, -116, -59, 97, -34, 37, 0, 63, 0, 0, 0, 2, 95, -16, 6,
      // -39, -8, 95, 0, 63, 0, 111, 0, 0, -122, -99, -6, -38, -120, -86}

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
      //  [0] Hops: [1-ff00:0:111 111>2 1-ff00:0:110 1>10 1-ff00:0:120 1>105 2-ff00:0:210 450>503
      //             2-ff00:0:211] MTU: 1280 NextHop: 127.0.0.25:31016
      //             Status: alive LocalIP: 127.0.0.1
    }
    assertEquals(1, topoServer.getAndResetCallCount());
    assertEquals(3, controlServer.getAndResetCallCount());
  }

  private static void checkMetaHeader(
      ByteBuffer rawBB, int hopCount0, int hopCount1, int hopCount2) {
    int bits = (((hopCount0 << 6) | hopCount1) << 6) | hopCount2;
    assertEquals(bits, rawBB.getInt()); // MetaHeader
  }

  private static void checkInfo(ByteBuffer rawBB, int segmentId) {
    assertEquals(0, rawBB.getShort()); // Info0 flags etc
    assertEquals(segmentId, rawBB.getShort()); // Info0 SegID
    assertNotEquals(0, rawBB.getInt()); // Info0 timestamp
  }

  private static void checkHopField(ByteBuffer rawBB, int ingress, int egress) {
    assertEquals(63, rawBB.getShort()); // Hop0 flags/expiry
    assertEquals(ingress, rawBB.getShort()); // Hop0 ingress
    assertEquals(egress, rawBB.getShort()); // Hop0 egress
    assertNotEquals(0, rawBB.getShort()); // Hop0 MAC
    assertNotEquals(0, rawBB.getInt()); // Hop0 MAC
  }
}
