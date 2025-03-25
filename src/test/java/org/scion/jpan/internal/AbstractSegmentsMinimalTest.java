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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.testutil.MockControlServer;
import org.scion.jpan.testutil.MockNetwork2;
import org.scion.jpan.testutil.Scenario;

public abstract class AbstractSegmentsMinimalTest {
  protected static final String AS_HOST = MockNetwork2.AS_HOST;
  protected static final String CFG_MINIMAL = "topologies/minimal/";
  protected static final String CFG_DEFAULT = "topologies/default/";
  protected static final String CFG_TINY4 = "topologies/tiny4/";

  /** ISD 1 - core AS */
  protected static final long AS_110 = ScionUtil.parseIA("1-ff00:0:110");

  /** ISD 1 - non-core AS */
  protected static final long AS_111 = ScionUtil.parseIA("1-ff00:0:111");

  /** ISD 1 - non-core AS */
  protected static final long AS_1111 = ScionUtil.parseIA("1-ff00:0:1111");

  /** ISD 1 - non-core AS */
  protected static final long AS_1112 = ScionUtil.parseIA("1-ff00:0:1112");

  /** ISD 1 - non-core AS */
  protected static final long AS_112 = ScionUtil.parseIA("1-ff00:0:112");

  /** ISD 1 - non-core AS */
  protected static final long AS_1121 = ScionUtil.parseIA("1-ff00:0:1121");

  /** ISD 1 - core AS */
  protected static final long AS_120 = ScionUtil.parseIA("1-ff00:0:120");

  protected static final long AS_130 = ScionUtil.parseIA("1-ff00:0:130");
  protected static final long AS_131 = ScionUtil.parseIA("1-ff00:0:131");
  protected static final long AS_133 = ScionUtil.parseIA("1-ff00:0:133");

  /** ISD 1 - non-core AS */
  protected static final long AS_121 = ScionUtil.parseIA("1-ff00:0:121");

  /** ISD 2 - core AS */
  protected static final long AS_210 = ScionUtil.parseIA("2-ff00:0:210");
  protected static final long AS_220 = ScionUtil.parseIA("2-ff00:0:220");

  /** ISD 2 - non-core AS */
  protected static final long AS_211 = ScionUtil.parseIA("2-ff00:0:211");


  protected static MockControlServer controlServer;
  protected final Scenario scenario;

  protected AbstractSegmentsMinimalTest() {
    this(CFG_MINIMAL);
  }

  protected AbstractSegmentsMinimalTest(String cfg) {
    scenario = Scenario.readFrom(cfg);
  }

  protected static void checkMetaHeader(
      ByteBuffer rawBB, int hopCount0, int hopCount1, int hopCount2) {
    int bits = (((hopCount0 << 6) | hopCount1) << 6) | hopCount2;
    assertEquals(bits, rawBB.getInt()); // MetaHeader
  }

  protected static void checkInfo(ByteBuffer rawBB, int segmentId, int flags) {
    assertEquals(flags, rawBB.get()); // Info0 flags
    assertEquals(0, rawBB.get()); // Info0 etc
    if (flags != 0) {
      assertEquals(segmentId, ByteUtil.toUnsigned(rawBB.getShort())); // Info0 SegID
    } else {
      // TODO fix -> XOR SegID! -> assert!
      rawBB.getShort();
    }
    assertNotEquals(0, rawBB.getInt()); // Info0 timestamp
  }

  protected static void checkHopField(ByteBuffer rawBB, int ingress, int egress) {
    assertEquals(63, rawBB.getShort()); // Hop0 flags/expiry
    assertEquals(ingress, rawBB.getShort()); // Hop0 ingress
    assertEquals(egress, rawBB.getShort()); // Hop0 egress
    assertNotEquals(0, rawBB.getShort()); // Hop0 MAC
    assertNotEquals(0, rawBB.getInt()); // Hop0 MAC
  }

  protected static void checkInterface(Daemon.Path path, int i, int id, String isdAs) {
    assertEquals(id, path.getInterfaces(i).getId());
    assertEquals(ScionUtil.parseIA(isdAs), path.getInterfaces(i).getIsdAs());
  }

  /**
   * @param exp byte[] of a raw path returned by a SCION daemon
   * @param act byte[] of raw path generated by Java client from segments from SCION control service
   */
  protected static void checkRaw(byte[] exp, byte[] act) {
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
    ofs += 6; // skip segmentID & timestamp
    if (s1 > 0) {
      assertEquals(exp[ofs], act[ofs++]);
      assertEquals(exp[ofs], act[ofs++]);
      ofs += 6; // skip segmentID & timestamp
    }
    if (s2 > 0) {
      assertEquals(exp[ofs], act[ofs++]);
      assertEquals(exp[ofs], act[ofs++]);
      ofs += 6; // skip segmentID & timestamp
    }

    // hop fields
    for (int h = 0; h < s; h++) {
      for (int i = 0; i < 6; i++) {
        assertEquals(exp[ofs], act[ofs++]);
      }
      ofs += 6; // skip MAC
    }

    assertEquals(ofs, exp.length);
    assertEquals(ofs, act.length);
  }

  private static Seg.SegmentsResponse buildResponse(
      Seg.SegmentType type, Seg.PathSegment... paths) {
    Seg.SegmentsResponse.Builder replyBuilder = Seg.SegmentsResponse.newBuilder();
    Seg.SegmentsResponse.Segments segments =
        Seg.SegmentsResponse.Segments.newBuilder().addAllSegments(Arrays.asList(paths)).build();
    replyBuilder.putSegments(type.getNumber(), segments);
    return replyBuilder.build();
  }

  protected void addResponses() {
    addResponse110_1111();
    addResponse110_1112();
    addResponse110_1121();
    addResponse120_210();

    addResponse110_111();
    addResponse110_112();
    addResponse110_120();
    addResponse120_121();
    addResponse110_210();
    addResponse210_211();
  }

  protected void addResponsesScionprotoDefault() {
    addUpDown(AS_111, AS_130);
    addUpDown(AS_111, AS_120);

    addUpDown(AS_112, AS_130);
    addUpDown(AS_112, AS_120);

    addUpDown(AS_131, AS_130);
    addUpDown(AS_133, AS_130);

    // Add both directions
    addCore(AS_130, AS_120);
    addCore(AS_120, AS_130);

    addCore(AS_110, AS_120);
    addCore(AS_120, AS_110);

    addCore(AS_120, AS_220);
  }

  protected void addResponsesScionprotoTiny4() {
    addUpDown(AS_111, AS_110);
    addUpDown(AS_112, AS_110);
  }

  private void addCore(long local, long origin) {
    for (Seg.PathSegment path : scenario.getSegments(local, origin)) {
      controlServer.addResponse(
          local, true, origin, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_CORE, path));
    }
  }

  private void addUpDown(long leaf, long core) {
    for (Seg.PathSegment path : scenario.getSegments(leaf, core)) {
      controlServer.addResponse(
          leaf, false, core, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_UP, path));
      controlServer.addResponse(
          core, true, leaf, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path));
    }
  }

  private void addResponse110_111() {
    //    Requesting segments: 1-ff00:0:111 -> 1-0:0:0
    //    SEG: key=SEGMENT_TYPE_UP -> n=1
    //    PathSeg: size=10
    //    SegInfo:  ts=2024-01-05T12:47:21Z  id=18215
    //      AS: signed=93   signature size=71
    //      AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-05T12:47:21.916540395Z
    //      AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:111  mtu=1472
    //        HopEntry: true mtu=0
    //          HopField: exp=63 ingress=0 egress=2
    //      AS: signed=89   signature size=72
    //      AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-05T12:47:22.430108490Z
    //      AS Body: IA=1-ff00:0:111 nextIA=0-0:0:0  mtu=1472
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=111 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_111, AS_110).get(0);
    controlServer.addResponse(
        AS_111, false, AS_110, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_UP, path0));
    controlServer.addResponse(
        AS_110, true, AS_111, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
  }

  private void addResponse110_1111() {
    //  Requesting segments: 1-ff00:0:110 -> 1-ff00:0:1111
    //  SEG: key=SEGMENT_TYPE_DOWN -> n=1
    //  PathSeg: size=9
    //  SegInfo:  ts=2024-01-10T15:58:22Z  id=10619
    //    AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:111  mtu=1460
    //      HopEntry: true mtu=0
    //        HopField: exp=63 ingress=0 egress=2
    //    AS Body: IA=1-ff00:0:111 nextIA=1-ff00:0:1111  mtu=1472
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=111 egress=1111
    //    AS Body: IA=1-ff00:0:1111 nextIA=0-0:0:0  mtu=1472
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=123 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_1111, AS_110).get(0);
    controlServer.addResponse(
        AS_110, true, AS_1111, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
    controlServer.addResponse(
        AS_1111, false, AS_110, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_UP, path0));
  }

  private void addResponse110_1112() {
    //  Requesting segments: 1-ff00:0:110 -> 1-ff00:0:1112
    //  SEG: key=SEGMENT_TYPE_DOWN -> n=1
    //    PathSeg: size=10
    //      SegInfo:  ts=2024-01-10T17:11:53Z  id=25161
    //      AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:111  mtu=1460
    //        HopEntry: true mtu=0
    //          HopField: exp=63 ingress=0 egress=2
    //      AS Body: IA=1-ff00:0:111 nextIA=1-ff00:0:1112  mtu=1472
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=111 egress=1112
    //      AS Body: IA=1-ff00:0:1112 nextIA=0-0:0:0  mtu=1472
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=234 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_1112, AS_110).get(0);
    controlServer.addResponse(
        AS_110, true, AS_1112, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
    controlServer.addResponse(
        AS_1112, false, AS_110, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_UP, path0));
  }

  private void addResponse110_1121() {
    //  Requesting segments: 1-ff00:0:110 -> 1-ff00:0:1121
    //  SEG: key=SEGMENT_TYPE_DOWN -> n=1
    //    PathSeg: size=9
    //      SegInfo:  ts=2024-01-10T17:17:43Z  id=2700
    //      AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:112  mtu=1460
    //        HopEntry: true mtu=0
    //          HopField: exp=63 ingress=0 egress=3
    //      AS Body: IA=1-ff00:0:112 nextIA=1-ff00:0:1121  mtu=1450
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=453 egress=1121
    //      AS Body: IA=1-ff00:0:1121 nextIA=0-0:0:0  mtu=1472
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=345 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_1121, AS_110).get(0);
    controlServer.addResponse(
        AS_110, true, AS_1121, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
    controlServer.addResponse(
        AS_1121, false, AS_110, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_UP, path0));
  }

  private void addResponse110_112() {
    //    Requesting segments: 1-0:0:0 -> 1-ff00:0:112
    //    Requesting segments: 1-ff00:0:110 -> 1-ff00:0:112
    //    SEG: key=SEGMENT_TYPE_DOWN -> n=1
    //    PathSeg: size=9
    //    SegInfo:  ts=2024-01-05T15:02:17Z  id=5701
    //      AS: signed=93   signature size=71
    //      AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-05T15:02:17.455400479Z
    //      AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:112  mtu=1460
    //        HopEntry: true mtu=0
    //          HopField: exp=63 ingress=0 egress=3
    //      AS: signed=90   signature size=71
    //      AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-05T15:02:20.450271904Z
    //      AS Body: IA=1-ff00:0:112 nextIA=0-0:0:0  mtu=1450
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=453 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_112, AS_110).get(0);
    controlServer.addResponse(
        AS_110, true, AS_112, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
  }

  private void addResponse110_120() {
    //  Requesting segments: 1-ff00:0:110 -> 1-ff00:0:120
    //  SEG: key=SEGMENT_TYPE_CORE -> n=1
    //  PathSeg: size=9
    //  SegInfo:  ts=2024-01-10T12:48:16Z  id=12530
    //    AS: signed=93   signature size=70
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-10T12:48:16.681095815Z
    //    AS Body: IA=1-ff00:0:120 nextIA=1-ff00:0:110  mtu=1472
    //      HopEntry: true mtu=0
    //        HopField: exp=63 ingress=0 egress=10
    //    AS: signed=89   signature size=70
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-10T12:48:17.672710479Z
    //    AS Body: IA=1-ff00:0:110 nextIA=0-0:0:0  mtu=1460
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=1 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_110, AS_120).get(0);
    controlServer.addResponse(
        AS_110, true, AS_120, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_CORE, path0));
  }

  private void addResponse120_121() {
    //  Requesting segments: 1-ff00:0:120 -> 1-ff00:0:121
    //  SEG: key=SEGMENT_TYPE_DOWN -> n=1
    //  PathSeg: size=10
    //  SegInfo:  ts=2024-01-10T12:58:22Z  id=32941
    //    AS: signed=92   signature size=72
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-10T12:58:22.183393999Z
    //    AS Body: IA=1-ff00:0:120 nextIA=1-ff00:0:121  mtu=1472
    //      HopEntry: true mtu=0
    //        HopField: exp=63 ingress=0 egress=21
    //    AS: signed=89   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-10T12:58:23.693617219Z
    //    AS Body: IA=1-ff00:0:121 nextIA=0-0:0:0  mtu=1472
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=104 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_121, AS_120).get(0);
    controlServer.addResponse(
        AS_120, true, AS_121, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
    controlServer.addResponse(
        AS_121, false, AS_120, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_UP, path0));
  }

  private void addResponse120_210() {
    //  Requesting segments: 1-ff00:0:120 -> 2-ff00:0:210
    //  SEG: key=SEGMENT_TYPE_CORE -> n=1
    //  PathSeg: size=10
    //    SegInfo:  ts=2024-02-26T14:30:53Z  id=18204
    //    AS: signed=95   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-02-26T14:30:53.689893427Z
    // meta=0  data=10
    //    AS Body: IA=2-ff00:0:210 nextIA=1-ff00:0:120  mtu=1280
    //      HopEntry: true mtu=0
    //      HopField: exp=63 ingress=0 egress=105
    //    AS: signed=89   signature size=70
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-02-26T14:30:54.173096887Z
    // meta=0  data=176
    //    AS Body: IA=1-ff00:0:120 nextIA=0-0:0:0  mtu=1472
    //      HopEntry: true mtu=1472
    //      HopField: exp=63 ingress=210 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_120, AS_210).get(0);
    Seg.PathSegment path1 = scenario.getSegments(AS_210, AS_120).get(0);
    controlServer.addResponse(
        AS_120, true, AS_210, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_CORE, path0));
    controlServer.addResponse(
        AS_210, true, AS_120, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_CORE, path1));
  }

  private void addResponse110_210() {
    //  Requesting segments: 1-ff00:0:110 -> 2-ff00:0:210
    //  SEG: key=SEGMENT_TYPE_CORE -> n=1
    //  PathSeg: size=10
    //  SegInfo:  ts=2024-01-10T12:59:43Z  id=47499
    //    AS: signed=95   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-10T12:59:43.691626342Z
    //    AS Body: IA=2-ff00:0:210 nextIA=1-ff00:0:120  mtu=1280
    //      HopEntry: true mtu=0
    //        HopField: exp=63 ingress=0 egress=105
    //    AS: signed=99   signature size=72
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-10T12:59:47.186763262Z
    //    AS Body: IA=1-ff00:0:120 nextIA=1-ff00:0:110  mtu=1472
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=210 egress=10
    //    AS: signed=88   signature size=70
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-10T12:59:51.171109823Z
    //    AS Body: IA=1-ff00:0:110 nextIA=0-0:0:0  mtu=1460
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=1 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_110, AS_210).get(0);
    controlServer.addResponse(
        AS_110, true, AS_210, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_CORE, path0));
  }

  private void addResponse210_211() {
    //  Requesting segments: 2-ff00:0:210 -> 2-ff00:0:211
    //  SEG: key=SEGMENT_TYPE_DOWN -> n=1
    //  PathSeg: size=10
    //  SegInfo:  ts=2024-01-10T13:01:13Z  id=59077
    //    AS: signed=97   signature size=72
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-10T13:01:13.696099703Z
    //    AS Body: IA=2-ff00:0:210 nextIA=2-ff00:0:211  mtu=1280
    //      HopEntry: true mtu=0
    //        HopField: exp=63 ingress=0 egress=450
    //    AS: signed=92   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-10T13:01:18.697075400Z
    //    AS Body: IA=2-ff00:0:211 nextIA=0-0:0:0  mtu=1472
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=503 egress=0
    Seg.PathSegment path0 = scenario.getSegments(AS_211, AS_210).get(0);
    controlServer.addResponse(
        AS_210, true, AS_211, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
    controlServer.addResponse(
        AS_211, false, AS_210, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_UP, path0));
  }
}
