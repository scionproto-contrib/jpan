// Copyright 2026 ETH Zurich
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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.crypto.Signed;
import org.scion.jpan.testutil.MockNetwork2;
import org.scion.jpan.testutil.MockPathService;

class ScionServiceMultiIsdTest {

  private static final long AS_111 = ScionUtil.parseIA("1-ff00:0:111");
  private static final long AS_110 = ScionUtil.parseIA("1-ff00:0:110");
  private static final long AS_211 = ScionUtil.parseIA("2-ff00:0:211");
  private static final long AS_210 = ScionUtil.parseIA("2-ff00:0:210");
  private static final long AS_120 = ScionUtil.parseIA("1-ff00:0:120");
  private static final long AS_121 = ScionUtil.parseIA("1-ff00:0:121");

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  @Test
  void getPaths_pathServiceHandlesMultipleLocalIsdNumbers() {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.MINIMAL, "ASff00_0_111")) {
      nw.getPathServices().forEach(MockPathService::clearSegments);
      nw.getPathServices().forEach(ps -> ps.setCustomIsdAses(Arrays.asList(AS_111, AS_211)));

      long now = Instant.now().getEpochSecond();
      // Source IA #1: 1-ff00:0:111 -> 1-ff00:0:110 -> 1-ff00:0:120 -> 1-ff00:0:121
      addUpSegment(nw, AS_111, AS_110, 2, 111, now);
      addCoreSegment(nw, AS_110, AS_120, 1, 2, now);

      // Source IA #2: 2-ff00:0:211 -> 2-ff00:0:210 -> 1-ff00:0:120 -> 1-ff00:0:121
      addUpSegment(nw, AS_211, AS_210, 2, 1111, now);
      addCoreSegment(nw, AS_210, AS_120, 1, 2, now);

      // Shared destination down segment.
      addDownSegment(nw, AS_120, AS_121, 121, 41, now);

      ScionService service = Scion.defaultService();
      List<Path> paths =
          service.getPaths(AS_121, new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));

      assertEquals(2, paths.size());
      Set<Integer> sourceIsds =
          paths.stream()
              .map(path -> ScionUtil.extractIsd(path.getMetadata().getSrcIdsAs()))
              .collect(Collectors.toSet());
      assertTrue(sourceIsds.contains(ScionUtil.extractIsd(AS_111)));
      assertTrue(sourceIsds.contains(ScionUtil.extractIsd(AS_211)));
    }
  }

  private static Seg.SegmentsResponse buildResponse(
      Seg.SegmentType type, Seg.PathSegment... segments) {
    Seg.SegmentsResponse.Builder replyBuilder = Seg.SegmentsResponse.newBuilder();
    Seg.SegmentsResponse.Segments entries =
        Seg.SegmentsResponse.Segments.newBuilder().addAllSegments(Arrays.asList(segments)).build();
    replyBuilder.putSegments(type.getNumber(), entries);
    return replyBuilder.build();
  }

  private static Seg.PathSegment buildSegment(
      long srcIA, long dstIA, int dstIngress, int srcEgress, long timestamp) {
    Seg.ASEntry srcAsEntry = buildAsEntry(srcIA, 3, srcEgress, 4567, 2345);
    Seg.ASEntry dstAsEntry = buildAsEntry(dstIA, dstIngress, 0, 3456, 1234);
    Seg.SegmentInformation info =
        Seg.SegmentInformation.newBuilder().setTimestamp(timestamp).build();
    return Seg.PathSegment.newBuilder()
        .addAsEntries(srcAsEntry)
        .addAsEntries(dstAsEntry)
        .setSegmentInfo(info.toByteString())
        .build();
  }

  private static void addUpSegment(
      MockNetwork2 nw, long srcIA, long dstIA, int dstIngress, int srcEgress, long timestamp) {
    nw.addResponse(
        srcIA,
        false,
        dstIA,
        true,
        buildResponse(
            Seg.SegmentType.SEGMENT_TYPE_UP,
            buildSegment(srcIA, dstIA, dstIngress, srcEgress, timestamp)));
  }

  private static void addCoreSegment(
      MockNetwork2 nw, long srcIA, long dstIA, int dstIngress, int srcEgress, long timestamp) {
    nw.addResponse(
        srcIA,
        true,
        dstIA,
        true,
        buildResponse(
            Seg.SegmentType.SEGMENT_TYPE_CORE,
            buildSegment(srcIA, dstIA, dstIngress, srcEgress, timestamp)));
  }

  private static void addDownSegment(
      MockNetwork2 nw, long srcIA, long dstIA, int dstIngress, int srcEgress, long timestamp) {
    nw.addResponse(
        srcIA,
        true,
        dstIA,
        false,
        buildResponse(
            Seg.SegmentType.SEGMENT_TYPE_DOWN,
            buildSegment(srcIA, dstIA, dstIngress, srcEgress, timestamp)));
  }

  private static Seg.ASEntry buildAsEntry(
      long isdAs, int ingress, int egress, int mtu, int ingressMtu) {
    ByteString mac = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
    Seg.HopField hopField =
        Seg.HopField.newBuilder()
            .setMac(mac)
            .setIngress(ingress)
            .setEgress(egress)
            .setExpTime(64)
            .build();
    Seg.HopEntry hopEntry =
        Seg.HopEntry.newBuilder().setHopField(hopField).setIngressMtu(ingressMtu).build();
    Seg.ASEntrySignedBody signedBody =
        Seg.ASEntrySignedBody.newBuilder()
            .setIsdAs(isdAs)
            .setHopEntry(hopEntry)
            .setMtu(mtu)
            .build();
    Signed.HeaderAndBodyInternal habi =
        Signed.HeaderAndBodyInternal.newBuilder().setBody(signedBody.toByteString()).build();
    Signed.SignedMessage signedMessage =
        Signed.SignedMessage.newBuilder().setHeaderAndBody(habi.toByteString()).build();
    return Seg.ASEntry.newBuilder().setSigned(signedMessage).build();
  }
}
