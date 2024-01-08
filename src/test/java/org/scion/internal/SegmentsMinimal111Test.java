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
