// Copyright 2023 ETH Zurich
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

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import io.grpc.StatusRuntimeException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.scion.ScionException;
import org.scion.ScionUtil;
import org.scion.proto.control_plane.Seg;
import org.scion.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.proto.crypto.Signed;
import org.scion.proto.daemon.Daemon;

public class Segments {
  private static List<Daemon.Path> combineThreeSegments(
      List<Seg.PathSegment> segmentsUp,
      List<Seg.PathSegment> segmentsCore,
      List<Seg.PathSegment> segmentsDown,
      long srcIsdAs,
      long dstIsdAs,
      ScionBootstrapper brLookup)
      throws ScionException {
    // Map IsdAs to pathSegment
    MultiMap<Long, Seg.PathSegment> upSegments = createSegmentsMap(segmentsUp, srcIsdAs);
    MultiMap<Long, Seg.PathSegment> downSegments = createSegmentsMap(segmentsDown, dstIsdAs);

    List<Daemon.Path> paths = new ArrayList<>();
    for (Seg.PathSegment pathSeg : segmentsCore) {
      long[] IAs = getEndingIAs(pathSeg);

      //      if (upSegments.get(IAs[0]) == null || downSegments.get(IAs[1]) == null) {
      //        // This should not happen, we have a core segment that has no matching
      //        // up/down segments
      //        throw new IllegalStateException(); // TODO actually, this appears to be happening!
      //        // continue;
      //      }
      if (upSegments.get(IAs[0]) != null && downSegments.get(IAs[1]) != null) {
        buildPath(paths, upSegments.get(IAs[0]), pathSeg, downSegments.get(IAs[1]), brLookup);
      } else if (upSegments.get(IAs[1]) != null && downSegments.get(IAs[0]) != null) {
        buildPath(paths, upSegments.get(IAs[1]), pathSeg, downSegments.get(IAs[0]), brLookup);
      }
    }
    return paths;
  }

  /**
   * Creates path from two segments. E.g. Up+Core or Core+Down.
   *
   * @param segments0 Up or Core segments
   * @param segments1 Core or Down segments
   * @param srcIsdAs src ISD/AS
   * @param dstIsdAs src ISD/AS
   * @param brLookup border router lookup resource
   * @return Paths
   * @throws ScionException In case of deserialization problem
   */
  private static List<Daemon.Path> combineTwoSegments(
      List<Seg.PathSegment> segments0,
      List<Seg.PathSegment> segments1,
      long srcIsdAs,
      long dstIsdAs,
      ScionBootstrapper brLookup)
      throws ScionException {
    // Map IsdAs to pathSegment
    MultiMap<Long, Seg.PathSegment> segmentsMap1 = createSegmentsMap(segments1, dstIsdAs);

    List<Daemon.Path> paths = new ArrayList<>();
    for (Seg.PathSegment pathSegment0 : segments0) {
      long[] IAs = getEndingIAs(pathSegment0);
      if (IAs[0] != srcIsdAs && IAs[1] != srcIsdAs) {
        continue; // discard
      }
      long coreIsdAs = IAs[0] == srcIsdAs ? IAs[1] : IAs[0];
      if (segmentsMap1.get(coreIsdAs) == null) {
        // ignore, this should not happen.
        continue;
      }
      for (Seg.PathSegment pathSegment1 : segmentsMap1.get(coreIsdAs)) {
        paths.add(buildPath(pathSegment0, pathSegment1, null, brLookup));
      }
    }
    return paths;
  }

  private static List<Daemon.Path> combineSegment(
      List<Seg.PathSegment> segments, ScionBootstrapper brLookup) throws ScionException {
    List<Daemon.Path> paths = new ArrayList<>();
    for (Seg.PathSegment pathSegment : segments) {
      paths.add(buildPath(pathSegment, null, null, brLookup));
    }
    return paths;
  }

  private static List<Daemon.Path> combineSegments(
      List<List<Seg.PathSegment>> segments,
      long srcIsdAs,
      long dstIsdAs,
      ScionBootstrapper brLookup)
      throws ScionException {
    if (segments.size() == 1) {
      return combineSegment(segments.get(0), brLookup);
    } else if (segments.size() == 2) {
      return combineTwoSegments(segments.get(0), segments.get(1), srcIsdAs, dstIsdAs, brLookup);
    }
    return combineThreeSegments(
        segments.get(0), segments.get(1), segments.get(2), srcIsdAs, dstIsdAs, brLookup);
  }

  private static void buildPath(
      List<Daemon.Path> paths,
      List<Seg.PathSegment> segmentsUp,
      Seg.PathSegment segCore,
      List<Seg.PathSegment> segmentsDown,
      ScionBootstrapper brLookup)
      throws ScionException {
    for (Seg.PathSegment segUp : segmentsUp) {
      for (Seg.PathSegment segDown : segmentsDown) {
        paths.add(buildPath(segUp, segCore, segDown, brLookup));
      }
    }
  }

  private static Daemon.Path buildPath(
      Seg.PathSegment seg0, Seg.PathSegment seg1, Seg.PathSegment seg2, ScionBootstrapper brLookup)
      throws ScionException {
    Daemon.Path.Builder path = Daemon.Path.newBuilder();
    ByteBuffer raw = ByteBuffer.allocate(1000);

    Seg.SegmentInformation info0 = getInfo(seg0);
    Seg.SegmentInformation info1 = seg1 == null ? null : getInfo(seg1);
    Seg.SegmentInformation info2 = seg2 == null ? null : getInfo(seg2);
    Seg.SegmentInformation[] infos = new Seg.SegmentInformation[] {info0, info1, info2};

    // path meta header
    int hopCount0 = seg0.getAsEntriesCount();
    int hopCount1 = seg1 == null ? 0 : seg1.getAsEntriesCount();
    int hopCount2 = seg2 == null ? 0 : seg2.getAsEntriesCount();
    int i0 = (hopCount0 << 12) | (hopCount1 << 6) | hopCount2;
    raw.putInt(i0);

    // info fields
    if (hopCount0 == 0) {
      // TODO
      // This can probably happen if we start in a CORE AS!
      // E.g. only coreSegments or only downSegments or core+down
      throw new UnsupportedOperationException();
    }
    writeInfoField(raw, info0, true);
    if (info1 != null) {
      writeInfoField(raw, info1, false);
    }
    if (info2 != null) {
      writeInfoField(raw, info2, false);
    }

    // hop fields
    // TODO clean up: Create [] of seg/info and loop inside write() method
    ByteUtil.MutInt minMtu = new ByteUtil.MutInt(Integer.MAX_VALUE);
    ByteUtil.MutInt minExpirationDelta = new ByteUtil.MutInt(Byte.MAX_VALUE);
    writeHopFields(raw, seg0, true, minExpirationDelta, minMtu);
    long minExp = calcExpTime(info0.getTimestamp(), minExpirationDelta.v);
    if (seg1 != null) {
      minExpirationDelta.v = Byte.MAX_VALUE;
      writeHopFields(raw, seg1, false, minExpirationDelta, minMtu);
      minExp = calcExpTime(info0.getTimestamp(), minExpirationDelta.v);
    }
    if (seg2 != null) {
      minExpirationDelta.v = Byte.MAX_VALUE;
      writeHopFields(raw, seg2, false, minExpirationDelta, minMtu);
      minExp = calcExpTime(info0.getTimestamp(), minExpirationDelta.v);
    }

    raw.flip();
    path.setRaw(ByteString.copyFrom(raw));

    // Expiration
    path.setExpiration(Timestamp.newBuilder().setSeconds(minExp).build());
    // TODO assert != Integer.MAX_VALUE, same for expiration!
    path.setMtu(minMtu.v);

    // TODO implement this
    //    path.setInterface(Daemon.Interface.newBuilder().setAddress().build());
    //    path.addInterfaces(Daemon.PathInterface.newBuilder().setId().setIsdAs().build());
    //    segUp.getSegmentInfo();
    //    path.setLatency();
    //    path.setInternalHops();
    //    path.setNotes();
    // First hop

    return path.build();
  }

  private static long calcExpTime(long baseTime, int deltaTime) {
    return baseTime + (long) (1 + deltaTime) * 24 * 60 * 60 / 256;
  }

  private static void writeInfoField(
      ByteBuffer raw, Seg.SegmentInformation info, boolean reversed) {
    int inf0 = ((reversed ? 0 : 1) << 24) | info.getSegmentId();
    raw.putInt(inf0);
    // TODO in the daemon's path, all segments have the same timestamp....
    raw.putInt((int) info.getTimestamp()); // TODO does this work? casting to int?
  }

  private static void writeHopFields(
      ByteBuffer raw,
      Seg.PathSegment pathSegment,
      boolean reversed,
      ByteUtil.MutInt minExp,
      ByteUtil.MutInt minMtu)
      throws ScionException {
    final int n = pathSegment.getAsEntriesCount();
    for (int i = 0; i < n; i++) {
      Seg.ASEntry asEntry = pathSegment.getAsEntriesList().get(reversed ? (n - i - 1) : i);
      // Let's assumed they are all signed // TODO?
      Signed.SignedMessage sm = asEntry.getSigned();
      Seg.ASEntrySignedBody body = getBody(sm);
      Seg.HopEntry hopEntry = body.getHopEntry();
      Seg.HopField hopField = body.getHopEntry().getHopField();

      raw.put((byte) 0);
      raw.put((byte) hopField.getExpTime()); // TODO cast to byte,...?
      raw.putShort((short) hopField.getIngress());
      raw.putShort((short) hopField.getEgress());
      ByteString mac = hopField.getMac();
      for (int j = 0; j < 6; j++) {
        raw.put(mac.byteAt(j));
      }
      minExp.v = Math.min(minExp.v, hopField.getExpTime());
      minMtu.v = Math.min(minMtu.v, hopEntry.getIngressMtu());
    }
  }

  private static MultiMap<Long, Seg.PathSegment> createSegmentsMap(
      List<Seg.PathSegment> segments, long knownIsdAs) throws ScionException {
    MultiMap<Long, Seg.PathSegment> map = new MultiMap<>();
    for (Seg.PathSegment pathSeg : segments) {
      long unknownIsdAs = getOtherIsdAs(knownIsdAs, pathSeg);
      map.put(unknownIsdAs, pathSeg);
    }
    return map;
  }

  // TODO use results from getEndingIAs()!
  private static long getOtherIsdAs(long isdAs, Seg.PathSegment seg) throws ScionException {
    // Either the first or the last ISD/AS is the one we are looking for.
    if (seg.getAsEntriesCount() < 2) {
      throw new UnsupportedOperationException("Segment has < 2 hops.");
    }
    Seg.ASEntry asEntryFirst = seg.getAsEntries(0);
    Seg.ASEntry asEntryLast = seg.getAsEntries(seg.getAsEntriesCount() - 1);
    if (!asEntryFirst.hasSigned() || !asEntryLast.hasSigned()) {
      throw new UnsupportedOperationException("Unsigned entries not (yet) supported"); // TODO
    }
    Seg.ASEntrySignedBody bodyFirst = getBody(asEntryFirst.getSigned());
    if (bodyFirst.getIsdAs() != isdAs) {
      return bodyFirst.getIsdAs();
    }
    return getBody(asEntryLast.getSigned()).getIsdAs();
  }

  /**
   * @param seg path segment
   * @return first and last ISD/AS of the path segment
   * @throws ScionException in case of parsing error
   */
  static long[] getEndingIAs(Seg.PathSegment seg) throws ScionException {
    Seg.ASEntry asEntryFirst = seg.getAsEntries(0);
    Seg.ASEntry asEntryLast = seg.getAsEntries(seg.getAsEntriesCount() - 1);
    if (!asEntryFirst.hasSigned() || !asEntryLast.hasSigned()) {
      throw new UnsupportedOperationException("Unsigned entries not (yet) supported"); // TODO
    }
    Seg.ASEntrySignedBody bodyFirst = getBody(asEntryFirst.getSigned());
    Seg.ASEntrySignedBody bodyLast = getBody(asEntryLast.getSigned());
    return new long[] {bodyFirst.getIsdAs(), bodyLast.getIsdAs()};
  }

  public static long[] getEndingIAs(List<Seg.PathSegment> segments) throws ScionException {
    long[] IAs = new long[2 * segments.size()];
    int i = 0;
    for (Seg.PathSegment seg : segments) {
      Seg.ASEntry asEntryFirst = seg.getAsEntries(0);
      Seg.ASEntry asEntryLast = seg.getAsEntries(seg.getAsEntriesCount() - 1);
      if (!asEntryFirst.hasSigned() || !asEntryLast.hasSigned()) {
        throw new UnsupportedOperationException("Unsigned entries not (yet) supported"); // TODO
      }
      Seg.ASEntrySignedBody bodyFirst = getBody(asEntryFirst.getSigned());
      Seg.ASEntrySignedBody bodyLast = getBody(asEntryLast.getSigned());

      // TODO i++
      IAs[i] = bodyFirst.getIsdAs();
      IAs[i + 1] = bodyLast.getIsdAs();
      i += 2;
    }
    return IAs;
  }

  private static Seg.ASEntrySignedBody getBody(Signed.SignedMessage sm) throws ScionException {
    try {
      Signed.HeaderAndBodyInternal habi =
          Signed.HeaderAndBodyInternal.parseFrom(sm.getHeaderAndBody());
      // Signed.Header header = Signed.Header.parseFrom(habi.getHeader());
      // TODO body for signature verification?!?
      return Seg.ASEntrySignedBody.parseFrom(habi.getBody());
    } catch (InvalidProtocolBufferException e) {
      throw new ScionException(e);
    }
  }

  private static Seg.SegmentInformation getInfo(Seg.PathSegment pathSegment) throws ScionException {
    try {
      return Seg.SegmentInformation.parseFrom(pathSegment.getSegmentInfo());
    } catch (InvalidProtocolBufferException e) {
      throw new ScionException(e);
    }
  }

  private static boolean[] containsIsdAs(long[] IAs, long srcIsdAs, long dstIsdAs) {
    boolean[] found = new boolean[] {false, false};
    for (long ia : IAs) {
      found[0] |= ia == srcIsdAs;
      found[1] |= ia == dstIsdAs;
    }
    return found;
  }

  private static boolean[] containsIsdAs(
      List<Seg.PathSegment> segments, long srcIsdAs, long dstIsdAs) throws ScionException {
    return containsIsdAs(getEndingIAs(segments), srcIsdAs, dstIsdAs);
  }

  private static List<Seg.PathSegment> getSegments(
      SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub,
      long srcIsdAs,
      long dstIsdAs)
      throws ScionException {
    if (srcIsdAs == dstIsdAs && !isWildcard(srcIsdAs)) {
      return Collections.emptyList();
    }
    Seg.SegmentsRequest request =
        Seg.SegmentsRequest.newBuilder().setSrcIsdAs(srcIsdAs).setDstIsdAs(dstIsdAs).build();
    Seg.SegmentsResponse response;
    try {
      response = segmentStub.segments(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException("Error while getting Segment info: " + e.getMessage(), e);
    }

    List<Seg.PathSegment> pathSegments = new ArrayList<>();
    for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> seg :
        response.getSegmentsMap().entrySet()) {
      pathSegments.addAll(seg.getValue().getSegmentsList());
    }

    return pathSegments;
  }

  public static List<Daemon.Path> getPaths(
      SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub,
      ScionBootstrapper brLookup,
      long srcIsdAs,
      long dstIsdAs)
      throws ScionException {
    // Cases:
    // A: src==dst
    // B: srcISD==dstISD; dst==core
    // C: srcISD==dstISD; src==core
    // D: srcISD==dstISD; src==core, dst==core
    // E: srcISD==dstISD;
    // F: srcISD!=dstISD; dst==core
    // G: srcISD!=dstISD; src==core
    // H: srcISD!=dstISD;
    long srcWildcard = toWildcard(srcIsdAs);
    long dstWildcard = toWildcard(dstIsdAs);
    int srcISD = ScionUtil.extractIsd(srcIsdAs);
    int dstISD = ScionUtil.extractIsd(dstIsdAs);

    if (srcIsdAs == dstIsdAs) {
      // case A
      // TODO return *empty* path!
      return Collections.emptyList();
    }

    // TODO in future we can find out whether an AS is CORE by parsing the TRC files:
    //      https://docs.anapaya.net/en/latest/resources/isd-as-assignments/#as-assignments
    //     ./bazel-bin/scion-pki/cmd/scion-pki/scion-pki_/scion-pki trc inspect
    //         ../../Downloads/ISD64.bundle

    long from = srcIsdAs;
    long to = dstIsdAs;
    List<List<Seg.PathSegment>> segments = new ArrayList<>();
    if (!brLookup.isLocalAsCore()) {
      // get UP segments
      List<Seg.PathSegment> segmentsUp = getSegments(segmentStub, srcIsdAs, srcWildcard);
      boolean[] containsIsdAs = containsIsdAs(segmentsUp, srcIsdAs, dstIsdAs);
      if (containsIsdAs[1]) {
        // case B: DST is core
        return combineSegment(segmentsUp, brLookup);
      }
      segments.add(segmentsUp);
      from = srcWildcard;
    }

    if (srcISD == dstISD) {
      // cases C, D, E
      // TODO this is an expensive way to find out whether DST is CORE
      List<Seg.PathSegment> segmentsCoreOrDown = getSegments(segmentStub, from, dstIsdAs);
      if (!segmentsCoreOrDown.isEmpty()) {
        // Okay, we found a direct route from src(Wildcard) to DST
        segments.add(segmentsCoreOrDown);
        List<Daemon.Path> paths = combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
        if (!paths.isEmpty()) {
          return paths;
        }
        // okay, we need CORE!
        segments.remove(segments.size() - 1);
        List<Seg.PathSegment> segmentsCore = getSegments(segmentStub, from, dstWildcard);
        boolean[] coreHasIA = containsIsdAs(segmentsCore, from, dstIsdAs);
        segments.add(segmentsCore);
        segments.add(segmentsCoreOrDown);
        return combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
      }
      // Try again with wildcard (DST is neither core nor a child of FROM)
      List<Seg.PathSegment> segmentsCore = getSegments(segmentStub, from, dstWildcard);
      boolean[] coreHasIA = containsIsdAs(segmentsCore, from, dstIsdAs);
      segments.add(segmentsCore);
      if (coreHasIA[1]) {
        // case D: DST is core
        return combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
      } else {
        from = dstWildcard;
        // case C: DST is not core
        // We have to query down segments because SRC may not have a segment connected to DST
        List<Seg.PathSegment> segmentsDown = getSegments(segmentStub, from, dstIsdAs);
        segments.add(segmentsDown);
        //        boolean[] downHasIA = Segments.containsIsdAs(segmentsDown, srcIsdAs, dstIsdAs);
        //        if (downHasIA[0]) {
        //          return Segments.combineSegment(segmentsDown, brLookup);
        //        }
        //        return Segments.combineTwoSegments(
        //                segmentsCore, segmentsDown, srcIsdAs, dstIsdAs, brLookup);
        return Segments.combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
      }

      //      if (brLookup.isLocalAsCore()) {
      //        // cases C, D
      //        List<Seg.PathSegment> segmentsCore = getSegments(segmentStub, srcIsdAs,
      // srcWildcard);
      //        long[] coreIAs = Segments.getEndingIAs(segmentsCore);
      //        boolean[] coreHasIA = Segments.containsIsdAs(coreIAs, srcIsdAs, dstIsdAs);
      //        if (coreHasIA[1]) {
      //          // case D: DST is core
      //          return Segments.combineSegment(segmentsCore, brLookup);
      //        } else {
      //          // case C: DST is not core
      //          // We have to query down segments because SRC may not have a segment connected to
      // DST
      //          List<Seg.PathSegment> segmentsDown = getSegments(segmentStub, srcWildcard,
      // dstIsdAs);
      //          boolean[] downHasIA = Segments.containsIsdAs(segmentsDown, srcIsdAs, dstIsdAs);
      //          if (downHasIA[0]) {
      //            return Segments.combineSegment(segmentsDown, brLookup);
      //          }
      //          return Segments.combineTwoSegments(
      //              segmentsCore, segmentsDown, srcIsdAs, dstIsdAs, brLookup);
      //        }
      //      } else {
      //        // cases B, E
      //        // TODO we should first find out whether DST is core -> avoid using wildcard
      //        List<Seg.PathSegment> segmentsUp = getSegments(segmentStub, srcIsdAs, srcWildcard);
      //        boolean[] containsIsdAs = Segments.containsIsdAs(segmentsUp, srcIsdAs, dstIsdAs);
      //        if (containsIsdAs[1]) {
      //          // case B: DST is core
      //          return Segments.combineSegment(segmentsUp, brLookup);
      //        } else {
      //          // case E: DST is not core
      //          List<Seg.PathSegment> segmentsDown = getSegments(segmentStub, srcWildcard,
      // dstIsdAs);
      //          return Segments.combineTwoSegments(
      //              segmentsUp, segmentsDown, srcIsdAs, dstIsdAs, brLookup);
      //        }
      //      }
    }
    // remaining cases: F, G, H
    List<Seg.PathSegment> segmentsCore = getSegments(segmentStub, from, dstWildcard);
    boolean[] localCores = Segments.containsIsdAs(segmentsCore, srcIsdAs, dstIsdAs);
    segments.add(segmentsCore);
    if (localCores[1]) {
      return Segments.combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
    }

    List<Seg.PathSegment> segmentsDown = getSegments(segmentStub, dstWildcard, dstIsdAs);
    segments.add(segmentsDown);
    return Segments.combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
  }

  private static long toWildcard(long isdAs) {
    long maskISD = -1L << 48;
    return isdAs & maskISD;
  }

  private static boolean isWildcard(long isdAs) {
    return toWildcard(isdAs) == isdAs;
  }
}
