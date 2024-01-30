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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import org.scion.ScionUtil;
import org.scion.proto.control_plane.Seg;
import org.scion.proto.crypto.Signed;
import org.scion.proto.daemon.Daemon;
import org.scion.testutil.MockControlServer;

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
public class SegmentsMinimalTest {
  protected static final String AS_HOST = "my-as-host.org";
  protected static final long ZERO = ScionUtil.parseIA("0-0:0:0");

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

  /** ISD 1 - non-core AS */
  protected static final long AS_121 = ScionUtil.parseIA("1-ff00:0:121");

  /** ISD 2 - core AS */
  protected static final long AS_210 = ScionUtil.parseIA("2-ff00:0:210");

  /** ISD 2 - non-core AS */
  protected static final long AS_211 = ScionUtil.parseIA("2-ff00:0:211");

  protected static MockControlServer controlServer;

  protected static void checkMetaHeader(
      ByteBuffer rawBB, int hopCount0, int hopCount1, int hopCount2) {
    int bits = (((hopCount0 << 6) | hopCount1) << 6) | hopCount2;
    assertEquals(bits, rawBB.getInt()); // MetaHeader
  }

  protected static void checkInfo(ByteBuffer rawBB, int segmentId, int flags) {
    assertEquals(flags, rawBB.get()); // Info0 flags
    assertEquals(0, rawBB.get()); // Info0 etc
    // TODO fix -> XOR SegID!
    if (flags != 0) {
      assertEquals(segmentId, ByteUtil.toUnsigned(rawBB.getShort())); // Info0 SegID
    } else {
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

  private static Seg.HopField buildHopField(int expiry, int ingress, int egress) {
    ByteString mac = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
    return Seg.HopField.newBuilder()
        .setExpTime(expiry)
        .setIngress(ingress)
        .setEgress(egress)
        .setMac(mac)
        .build();
  }

  private static Seg.HopEntry buildHopEntry(int mtu, Seg.HopField hf) {
    return Seg.HopEntry.newBuilder().setIngressMtu(mtu).setHopField(hf).build();
  }

  private static Seg.ASEntry buildASEntry(long isdAs, long nextIA, int mtu, Seg.HopEntry he) {
    Signed.Header header =
        Signed.Header.newBuilder()
            .setSignatureAlgorithm(Signed.SignatureAlgorithm.SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256)
            .setTimestamp(now())
            .build();
    Seg.ASEntrySignedBody body =
        Seg.ASEntrySignedBody.newBuilder()
            .setIsdAs(isdAs)
            .setNextIsdAs(nextIA)
            .setMtu(mtu)
            .setHopEntry(he)
            .build();
    Signed.HeaderAndBodyInternal habi =
        Signed.HeaderAndBodyInternal.newBuilder()
            .setHeader(header.toByteString())
            .setBody(body.toByteString())
            .build();
    Signed.SignedMessage sm =
        Signed.SignedMessage.newBuilder().setHeaderAndBody(habi.toByteString()).build();
    return Seg.ASEntry.newBuilder().setSigned(sm).build();
  }

  private static Seg.PathSegment buildPath(int id, Seg.ASEntry... entries) {
    long now = Instant.now().getEpochSecond();
    Seg.SegmentInformation info =
        Seg.SegmentInformation.newBuilder().setSegmentId(id).setTimestamp(now).build();
    Seg.PathSegment.Builder builder =
        Seg.PathSegment.newBuilder().setSegmentInfo(info.toByteString());
    //    for (Seg.ASEntry entry : entries) {
    //      builder.addAsEntries(entry);
    //    }
    builder.addAllAsEntries(Arrays.asList(entries));
    return builder.build();
  }

  private static Seg.SegmentsResponse buildResponse(
      Seg.SegmentType type, Seg.PathSegment... paths) {
    Seg.SegmentsResponse.Builder replyBuilder = Seg.SegmentsResponse.newBuilder();
    Seg.SegmentsResponse.Segments segments =
        Seg.SegmentsResponse.Segments.newBuilder().addAllSegments(Arrays.asList(paths)).build();
    replyBuilder.putSegments(type.getNumber(), segments);
    return replyBuilder.build();
  }

  private static Timestamp now() {
    Instant now = Instant.now();
    // TODO correct? Set nanos?
    return Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
  }

  protected void addResponses() {
    addResponse110_1111();
    addResponse110_1112();
    addResponse110_1121();

    // TODO redo the following ones?
    // addResponse111_110();
    addResponse110_111();
    addResponse110_112();
    addResponse110_120();
    addResponse120_121();
    addResponse110_210();
    addResponse210_211();
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

    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 2));
    Seg.ASEntry ase00 = buildASEntry(AS_110, AS_111, 1472, he00);
    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 111, 0));
    Seg.ASEntry ase01 = buildASEntry(AS_111, ZERO, 1472, he01);
    Seg.PathSegment path0 = buildPath(18215, ase00, ase01);

    controlServer.addResponse(
        AS_111, false, AS_110, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_UP, path0));
    controlServer.addResponse(
        AS_110, true, AS_111, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
  }

  //  private void addResponse110_111() {
  //    //    Requesting segments: 1-ff00:0:110 -> 1-ff00:0:111
  //    //    SEG: key=SEGMENT_TYPE_DOWN -> n=1
  //    //    PathSeg: size=10
  //    //    SegInfo:  ts=2024-01-05T12:53:41Z  id=50986
  //    //      AS: signed=93   signature size=71
  //    //      AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256
  // time=2024-01-05T12:53:41.919523006Z
  //    //      AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:111  mtu=1472
  //    //        HopEntry: true mtu=0
  //    //          HopField: exp=63 ingress=0 egress=2
  //    //      AS: signed=89   signature size=71
  //    //      AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256
  // time=2024-01-05T12:53:43.935089190Z
  //    //      AS Body: IA=1-ff00:0:111 nextIA=0-0:0:0  mtu=1472
  //    //        HopEntry: true mtu=1472
  //    //          HopField: exp=63 ingress=111 egress=0
  //
  //    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 2));
  //    Seg.ASEntry ase00 = buildASEntry(AS_110, AS_111, 0, he00);
  //    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 111, 0));
  //    Seg.ASEntry ase01 = buildASEntry(AS_111, ZERO, 1472, he01);
  //    Seg.PathSegment path0 = buildPath(50986, ase00, ase01);
  //
  //    controlServer.addResponse(
  //        AS_110, true, AS_111, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
  //  }

  private void addResponse110_1111() {
    //  Requesting segments: 1-ff00:0:110 -> 1-ff00:0:1111
    //  SEG: key=SEGMENT_TYPE_DOWN -> n=1
    //  PathSeg: size=9
    //  SegInfo:  ts=2024-01-10T15:58:22Z  id=10619
    //    AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:111  mtu=1472
    //      HopEntry: true mtu=0
    //        HopField: exp=63 ingress=0 egress=2
    //    AS Body: IA=1-ff00:0:111 nextIA=1-ff00:0:1111  mtu=1472
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=111 egress=1111
    //    AS Body: IA=1-ff00:0:1111 nextIA=0-0:0:0  mtu=1472
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=123 egress=0

    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 2));
    Seg.ASEntry ase00 = buildASEntry(AS_110, AS_111, 1472, he00);
    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 111, 1111));
    Seg.ASEntry ase01 = buildASEntry(AS_111, AS_1111, 1472, he01);
    Seg.HopEntry he02 = buildHopEntry(1472, buildHopField(63, 123, 0));
    Seg.ASEntry ase02 = buildASEntry(AS_1111, ZERO, 1472, he02);
    Seg.PathSegment path0 = buildPath(10619, ase00, ase01, ase02);

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
    //      AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:111  mtu=1472
    //        HopEntry: true mtu=0
    //          HopField: exp=63 ingress=0 egress=2
    //      AS Body: IA=1-ff00:0:111 nextIA=1-ff00:0:1112  mtu=1472
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=111 egress=1112
    //      AS Body: IA=1-ff00:0:1112 nextIA=0-0:0:0  mtu=1472
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=234 egress=0

    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 2));
    Seg.ASEntry ase00 = buildASEntry(AS_110, AS_111, 1472, he00);
    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 111, 1112));
    Seg.ASEntry ase01 = buildASEntry(AS_111, AS_1112, 1472, he01);
    Seg.HopEntry he02 = buildHopEntry(1472, buildHopField(63, 234, 0));
    Seg.ASEntry ase02 = buildASEntry(AS_1112, ZERO, 1472, he02);
    Seg.PathSegment path0 = buildPath(25161, ase00, ase01, ase02);

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
    //      AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:112  mtu=1472
    //        HopEntry: true mtu=0
    //          HopField: exp=63 ingress=0 egress=3
    //      AS Body: IA=1-ff00:0:112 nextIA=1-ff00:0:1121  mtu=1450
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=453 egress=1121
    //      AS Body: IA=1-ff00:0:1121 nextIA=0-0:0:0  mtu=1472
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=345 egress=0

    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 3));
    Seg.ASEntry ase00 = buildASEntry(AS_110, AS_112, 1472, he00);
    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 453, 1121));
    Seg.ASEntry ase01 = buildASEntry(AS_112, AS_1121, 1450, he01);
    Seg.HopEntry he02 = buildHopEntry(1472, buildHopField(63, 345, 0));
    Seg.ASEntry ase02 = buildASEntry(AS_1121, ZERO, 1472, he02);
    Seg.PathSegment path0 = buildPath(2700, ase00, ase01, ase02);

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
    //      AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:112  mtu=1472
    //        HopEntry: true mtu=0
    //          HopField: exp=63 ingress=0 egress=3
    //      AS: signed=90   signature size=71
    //      AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2024-01-05T15:02:20.450271904Z
    //      AS Body: IA=1-ff00:0:112 nextIA=0-0:0:0  mtu=1450
    //        HopEntry: true mtu=1472
    //          HopField: exp=63 ingress=453 egress=0
    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 3));
    Seg.ASEntry ase00 = buildASEntry(AS_110, AS_112, 1472, he00);
    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 453, 0));
    Seg.ASEntry ase01 = buildASEntry(AS_112, ZERO, 1450, he01);
    Seg.PathSegment path0 = buildPath(5701, ase00, ase01);

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
    //    AS Body: IA=1-ff00:0:110 nextIA=0-0:0:0  mtu=1472
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=1 egress=0

    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 10));
    Seg.ASEntry ase00 = buildASEntry(AS_120, AS_110, 1472, he00);
    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 1, 0));
    Seg.ASEntry ase01 = buildASEntry(AS_110, ZERO, 1472, he01);
    Seg.PathSegment path0 = buildPath(26755, ase00, ase01);

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

    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 21));
    Seg.ASEntry ase00 = buildASEntry(AS_120, AS_121, 1472, he00);
    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 104, 0));
    Seg.ASEntry ase01 = buildASEntry(AS_121, ZERO, 1472, he01);
    Seg.PathSegment path0 = buildPath(48280, ase00, ase01);

    controlServer.addResponse(
        AS_120, true, AS_121, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
    controlServer.addResponse(
        AS_121, false, AS_120, true, buildResponse(Seg.SegmentType.SEGMENT_TYPE_UP, path0));
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
    //    AS Body: IA=1-ff00:0:110 nextIA=0-0:0:0  mtu=1472
    //      HopEntry: true mtu=1472
    //        HopField: exp=63 ingress=1 egress=0

    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 105));
    Seg.ASEntry ase00 = buildASEntry(AS_210, AS_120, 1280, he00);
    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 210, 10));
    Seg.ASEntry ase01 = buildASEntry(AS_120, AS_110, 1472, he01);
    Seg.HopEntry he02 = buildHopEntry(1472, buildHopField(63, 1, 0));
    Seg.ASEntry ase02 = buildASEntry(AS_110, ZERO, 1472, he02);
    Seg.PathSegment path0 = buildPath(15767, ase00, ase01, ase02);

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

    Seg.HopEntry he00 = buildHopEntry(0, buildHopField(63, 0, 450));
    Seg.ASEntry ase00 = buildASEntry(AS_210, AS_211, 1280, he00);
    Seg.HopEntry he01 = buildHopEntry(1472, buildHopField(63, 503, 0));
    Seg.ASEntry ase01 = buildASEntry(AS_211, ZERO, 1472, he01);
    Seg.PathSegment path0 = buildPath(15299, ase00, ase01);

    controlServer.addResponse(
        AS_210, true, AS_211, false, buildResponse(Seg.SegmentType.SEGMENT_TYPE_DOWN, path0));
  }
}
