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
import org.junit.jupiter.api.Test;
import org.scion.PackageVisibilityHelper;
import org.scion.Scion;
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
      byte[] raw = {
        0, 0, 48, 0, 0, 0, -29, -115, 101, -98, -67, -19, 0, 63, 0, 123, 0, 0, -28, 18, 40, -128,
        23, -125, 0, 63, 0, 111, 4, 87, 119, 122, 17, -93, 63, -79, 0, 63, 0, 0, 0, 2, 12, 90, -19,
        -90, -67, 121
      };

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
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_1111, AS_1112);

      //      Daemon.Path path = paths.get(0);
      //      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      //      checkMetaHeader(rawBB, 2, 2, 0);
      //      checkInfo(rawBB, 18215, 0);
      //      checkInfo(rawBB, 5701, 1);
      //      checkHopField(rawBB, 111, 0);
      //      checkHopField(rawBB, 0, 2);
      //      checkHopField(rawBB, 0, 3);
      //      checkHopField(rawBB, 453, 0);
      //      assertEquals(0, rawBB.remaining());
      //
      //      // compare with recorded byte[]
      //      checkRaw(raw, path.getRaw().toByteArray());
      //
      //      assertEquals(1450, path.getMtu());
      //      assertEquals("127.0.0.25:31016", path.getInterface().getAddress().getAddress());
      //      checkInterface(path, 0, 111, "1-ff00:0:111");
      //      checkInterface(path, 1, 2, "1-ff00:0:110");
      //      checkInterface(path, 2, 3, "1-ff00:0:110");
      //      checkInterface(path, 3, 453, "1-ff00:0:112");
      //      assertEquals(4, path.getInterfacesCount());
    }
    //    assertEquals(1, topoServer.getAndResetCallCount());
    //    assertEquals(2, controlServer.getAndResetCallCount());
  }

  @Test
  void caseE_SameIsd_UpDown_OneCoreAS_b() throws IOException {
    addResponses();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_1111, AS_1121);

      //      Daemon.Path path = paths.get(0);
      //      ByteBuffer rawBB = path.getRaw().asReadOnlyByteBuffer();
      //      checkMetaHeader(rawBB, 2, 2, 0);
      //      checkInfo(rawBB, 18215, 0);
      //      checkInfo(rawBB, 5701, 1);
      //      checkHopField(rawBB, 111, 0);
      //      checkHopField(rawBB, 0, 2);
      //      checkHopField(rawBB, 0, 3);
      //      checkHopField(rawBB, 453, 0);
      //      assertEquals(0, rawBB.remaining());
      //
      //      // compare with recorded byte[]
      //      checkRaw(raw, path.getRaw().toByteArray());
      //
      //      assertEquals(1450, path.getMtu());
      //      assertEquals("127.0.0.25:31016", path.getInterface().getAddress().getAddress());
      //      checkInterface(path, 0, 111, "1-ff00:0:111");
      //      checkInterface(path, 1, 2, "1-ff00:0:110");
      //      checkInterface(path, 2, 3, "1-ff00:0:110");
      //      checkInterface(path, 3, 453, "1-ff00:0:112");
      //      assertEquals(4, path.getInterfacesCount());
    }
    //    assertEquals(1, topoServer.getAndResetCallCount());
    //    assertEquals(2, controlServer.getAndResetCallCount());
  }
}
