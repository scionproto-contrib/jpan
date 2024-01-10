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
import java.time.Instant;
import java.util.*;
import org.scion.ScionException;
import org.scion.ScionUtil;
import org.scion.proto.control_plane.Seg;
import org.scion.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.proto.crypto.Signed;
import org.scion.proto.daemon.Daemon;

public class Segments {
  private static List<Daemon.Path> combineThreeSegments(
      Seg.SegmentsResponse segmentsUp,
      Seg.SegmentsResponse segmentsCore,
      Seg.SegmentsResponse segmentsDown,
      long srcIsdAs,
      long dstIsdAs,
      ScionBootstrapper brLookup)
      throws ScionException {
    // Map IsdAs to pathSegment
    MultiMap<Long, Seg.PathSegment> upSegments = createSegmentsMap(segmentsUp, srcIsdAs);
    MultiMap<Long, Seg.PathSegment> downSegments = createSegmentsMap(segmentsDown, dstIsdAs);

    List<Daemon.Path> paths = new ArrayList<>();
    for (Seg.PathSegment pathSeg : get(segmentsCore)) {
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
      Seg.SegmentsResponse segments0,
      Seg.SegmentsResponse segments1,
      long srcIsdAs,
      long dstIsdAs,
      ScionBootstrapper brLookup)
      throws ScionException {
    // Map IsdAs to pathSegment
    MultiMap<Long, Seg.PathSegment> segmentsMap1 = createSegmentsMap(segments1, dstIsdAs);

    List<Daemon.Path> paths = new ArrayList<>();
    for (Seg.PathSegment pathSegment0 : get(segments0)) {
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
      Seg.SegmentsResponse segments, ScionBootstrapper brLookup) throws ScionException {
    List<Daemon.Path> paths = new ArrayList<>();
    for (Seg.PathSegment pathSegment : get(segments)) {
      paths.add(buildPath(pathSegment, null, null, brLookup));
    }
    return paths;
  }

  private static List<Daemon.Path> combineSegments(
      List<Seg.SegmentsResponse> segments, long srcIsdAs, long dstIsdAs, ScionBootstrapper brLookup)
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
    long[] endingASes = new long[2];
    boolean reversed0 = isReversed(seg0, brLookup.getLocalIsdAs(), endingASes);
    writeInfoField(raw, info0, reversed0);
    boolean reversed1 = false;
    if (info1 != null) {
      long ending0 = reversed0 ? endingASes[0] : endingASes[1];
      reversed1 = isReversed(seg1, ending0, endingASes);
      writeInfoField(raw, info1, reversed1);
    }
    if (info2 != null) {
      writeInfoField(raw, info2, false);
    }

    // hop fields
    // TODO clean up: Create [] of seg/info and loop inside write() method
    ByteUtil.MutInt minMtu = new ByteUtil.MutInt(brLookup.getLocalMtu());
    ByteUtil.MutInt minExpirationDelta = new ByteUtil.MutInt(Byte.MAX_VALUE);
    writeHopFields(path, raw, seg0, reversed0, minExpirationDelta, minMtu);
    long minExp = calcExpTime(info0.getTimestamp(), minExpirationDelta.v);
    if (seg1 != null) {
      minExpirationDelta.v = Byte.MAX_VALUE;
      writeHopFields(path, raw, seg1, reversed1, minExpirationDelta, minMtu);
      minExp = calcExpTime(info0.getTimestamp(), minExpirationDelta.v);
    }
    if (seg2 != null) {
      minExpirationDelta.v = Byte.MAX_VALUE;
      writeHopFields(path, raw, seg2, false, minExpirationDelta, minMtu);
      minExp = calcExpTime(info0.getTimestamp(), minExpirationDelta.v);
    }

    raw.flip();
    path.setRaw(ByteString.copyFrom(raw));

    // Expiration
    path.setExpiration(Timestamp.newBuilder().setSeconds(minExp).build());
    path.setMtu(minMtu.v);

    // TODO implement this
    //    segUp.getSegmentInfo();
    //    path.setLatency();
    //    path.setInternalHops();
    //    path.setNotes();
    // First hop
    String firstHop = brLookup.getBorderRouterAddress((int) path.getInterfaces(0).getId());
    Daemon.Underlay underlay = Daemon.Underlay.newBuilder().setAddress(firstHop).build();
    Daemon.Interface interfaceAddr = Daemon.Interface.newBuilder().setAddress(underlay).build();
    path.setInterface(interfaceAddr);

    return path.build();
  }

  private static boolean isReversed(Seg.PathSegment pathSegment, long startIA, long[] isdAs)
      throws ScionException {
    // Let's assumed they are all signed // TODO?
    Seg.ASEntry asEntry0 = pathSegment.getAsEntriesList().get(0);
    Seg.ASEntrySignedBody body0 = getBody(asEntry0.getSigned());
    Seg.ASEntry asEntryN = pathSegment.getAsEntriesList().get(pathSegment.getAsEntriesCount() - 1);
    Seg.ASEntrySignedBody bodyN = getBody(asEntryN.getSigned());
    isdAs[0] = body0.getIsdAs();
    isdAs[1] = bodyN.getIsdAs();
    if (body0.getIsdAs() == startIA) {
      return false;
    } else if (bodyN.getIsdAs() == startIA) {
      return true;
    }
    // TODO support "middle" IAs
    throw new UnsupportedOperationException("Relevant IA is not an ending IA!");
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
      Daemon.Path.Builder path,
      ByteBuffer raw,
      Seg.PathSegment pathSegment,
      boolean reversed,
      ByteUtil.MutInt minExp,
      ByteUtil.MutInt minMtu)
      throws ScionException {
    final int n = pathSegment.getAsEntriesCount();
    for (int i = 0; i < n; i++) {
      int pos = reversed ? (n - i - 1) : i;
      Seg.ASEntry asEntry = pathSegment.getAsEntriesList().get(pos);
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
      // TODO implement for "reversed"?
      // if (i < n - 1) {  // TODO correct? The last one always appear to be 0
      //   minMtu.v = Math.min(minMtu.v, hopEntry.getIngressMtu());
      // }
      minMtu.v = Math.min(minMtu.v, body.getMtu());

      boolean addInterfaces = (reversed && pos > 0) || (!reversed && pos < n - 1);
      if (addInterfaces) {
        Daemon.PathInterface.Builder pib = Daemon.PathInterface.newBuilder();
        //      if (i % 2 == 0) {
        //        System.out.println("IF-0: " + hopField.getIngress() + " / " +
        // hopField.getEgress());
        //        pib.setId(reversed ? hopField.getIngress() : hopField.getEgress());
        //      } else {
        //        System.out.println("IF-1: " + hopField.getIngress() + " / " +
        // hopField.getEgress());
        //        pib.setId(reversed ? hopField.getEgress() : hopField.getIngress());
        //      }
        //      if (i % 2 == 0) {
        pib.setId(reversed ? hopField.getIngress() : hopField.getEgress());
        //      } else {
        //        System.out.println("IF-1: " + hopField.getIngress() + " / " +
        // hopField.getEgress());
        //        pib.setId(reversed ? hopField.getEgress() : hopField.getIngress());
        //      }
        path.addInterfaces(pib.setIsdAs(body.getIsdAs()).build());
        System.out.println(
            "IF-0: "
                + hopField.getIngress()
                + " / "
                + hopField.getEgress()
                + " --> "
                + path.getInterfaces(path.getInterfacesCount() - 1).getId());
      }
      if (reversed && pos > 0) {
        Daemon.PathInterface.Builder pib2 = Daemon.PathInterface.newBuilder();
        Seg.ASEntry asEntry2 = pathSegment.getAsEntriesList().get(pos - 1);
        // Let's assumed they are all signed // TODO?
        Signed.SignedMessage sm2 = asEntry2.getSigned();
        Seg.ASEntrySignedBody body2 = getBody(sm2);
        Seg.HopField hopField2 = body2.getHopEntry().getHopField();
        pib2.setId(reversed ? hopField2.getEgress() : hopField2.getIngress());
        path.addInterfaces(pib2.setIsdAs(body2.getIsdAs()).build());
        System.out.println(
            "IF-20: "
                + hopField2.getIngress()
                + " / "
                + hopField2.getEgress()
                + " --> "
                + path.getInterfaces(path.getInterfacesCount() - 1).getId());
      }
      if (!reversed && pos < n - 1) {
        Daemon.PathInterface.Builder pib2 = Daemon.PathInterface.newBuilder();
        Seg.ASEntry asEntry2 = pathSegment.getAsEntriesList().get(pos + 1);
        // Let's assumed they are all signed // TODO?
        Signed.SignedMessage sm2 = asEntry2.getSigned();
        Seg.ASEntrySignedBody body2 = getBody(sm2);
        Seg.HopField hopField2 = body2.getHopEntry().getHopField();
        pib2.setId(reversed ? hopField2.getEgress() : hopField2.getIngress());
        path.addInterfaces(pib2.setIsdAs(body2.getIsdAs()).build());
        System.out.println(
            "IF-21: "
                + hopField2.getIngress()
                + " / "
                + hopField2.getEgress()
                + " --> "
                + path.getInterfaces(path.getInterfacesCount() - 1).getId());
      }
    }
  }

  private static MultiMap<Long, Seg.PathSegment> createSegmentsMap(
      Seg.SegmentsResponse response, long knownIsdAs) throws ScionException {
    MultiMap<Long, Seg.PathSegment> map = new MultiMap<>();
    for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> segmentsEntry :
        response.getSegmentsMap().entrySet()) {
      for (Seg.PathSegment pathSeg : segmentsEntry.getValue().getSegmentsList()) {
        long unknownIsdAs = getOtherIsdAs(knownIsdAs, pathSeg);
        map.put(unknownIsdAs, pathSeg);
      }
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

  // TODO use primitive set!?!
  public static Set<Long> getAllEndingIAs(Seg.SegmentsResponse segments) throws ScionException {
    Set<Long> IAs = new HashSet<>();
    for (Seg.PathSegment seg : get(segments)) {
      Seg.ASEntry asEntryFirst = seg.getAsEntries(0);
      Seg.ASEntry asEntryLast = seg.getAsEntries(seg.getAsEntriesCount() - 1);
      if (!asEntryFirst.hasSigned() || !asEntryLast.hasSigned()) {
        throw new UnsupportedOperationException("Unsigned entries not (yet) supported"); // TODO
      }
      Seg.ASEntrySignedBody bodyFirst = getBody(asEntryFirst.getSigned());
      Seg.ASEntrySignedBody bodyLast = getBody(asEntryLast.getSigned());

      // TODO add ALL instead of just ends
      IAs.add(bodyFirst.getIsdAs());
      IAs.add(bodyLast.getIsdAs());
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

  private static boolean[] containsIsdAs(Set<Long> IAs, long srcIsdAs, long dstIsdAs) {
    boolean[] found = new boolean[] {false, false};
    for (long ia : IAs) {
      found[0] |= ia == srcIsdAs;
      found[1] |= ia == dstIsdAs;
    }
    return found;
  }

  private static boolean[] containsIsdAs(
      Seg.SegmentsResponse segments, long srcIsdAs, long dstIsdAs) throws ScionException {
    return containsIsdAs(getAllEndingIAs(segments), srcIsdAs, dstIsdAs);
  }

  private static Seg.SegmentsResponse getSegments(
      SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub,
      long srcIsdAs,
      long dstIsdAs)
      throws ScionException {
    if (srcIsdAs == dstIsdAs && !isWildcard(srcIsdAs)) {
      return null;
    }
    Seg.SegmentsRequest request =
        Seg.SegmentsRequest.newBuilder().setSrcIsdAs(srcIsdAs).setDstIsdAs(dstIsdAs).build();
    try {
      Seg.SegmentsResponse response = segmentStub.segments(request);
      if (response.getSegmentsMap().size() > 1) {
        // TODO fix! there are many places where we use the horrible get()
        throw new UnsupportedOperationException();
      }
      return response;
    } catch (StatusRuntimeException e) {
      throw new ScionException("Error while getting Segment info: " + e.getMessage(), e);
    }
  }

  private static List<Seg.PathSegment> get(Seg.SegmentsResponse response) { // TODO remove
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
      // return empty path
      List<Daemon.Path> paths = new ArrayList<>();
      Daemon.Path.Builder path = Daemon.Path.newBuilder();
      path.setMtu(brLookup.getLocalMtu());
      Instant now = Instant.now();
      path.setExpiration(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build());
      paths.add(path.build());
      return paths;
    }

    // TODO in future we can find out whether an AS is CORE by parsing the TRC files:
    //      https://docs.anapaya.net/en/latest/resources/isd-as-assignments/#as-assignments
    //     ./bazel-bin/scion-pki/cmd/scion-pki/scion-pki_/scion-pki trc inspect
    //         ../../Downloads/ISD64.bundle
    //     Or we use logic. If SRC is not CORE then DST must be CORE.

    long from = srcIsdAs;
    long to = dstIsdAs;
    List<Seg.SegmentsResponse> segments = new ArrayList<>();
    if (!brLookup.isLocalAsCore()) {
      // get UP segments
      Seg.SegmentsResponse segmentsUp = getSegments(segmentStub, srcIsdAs, srcWildcard);
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
      Seg.SegmentsResponse segmentsCoreOrDown = getSegments(segmentStub, from, dstIsdAs);
      // TODO this 'if' is horrible. Also, the !=null should NOT be done in getSegments()
      if (segmentsCoreOrDown != null && !get(segmentsCoreOrDown).isEmpty()) {
        // Okay, we found a direct route from src(Wildcard) to DST
        segments.add(segmentsCoreOrDown);
        List<Daemon.Path> paths = combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
        if (!paths.isEmpty()) {
          return paths;
        }
        // okay, we need CORE!
        segments.remove(segments.size() - 1);
        Seg.SegmentsResponse segmentsCore = getSegments(segmentStub, from, dstWildcard);
        segments.add(segmentsCore);
        segments.add(segmentsCoreOrDown);
        return combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
      }
      // Try again with wildcard (DST is neither core nor a child of FROM)
      Seg.SegmentsResponse segmentsCore = getSegments(segmentStub, from, dstWildcard);
      boolean[] coreHasIA = containsIsdAs(segmentsCore, from, dstIsdAs);
      segments.add(segmentsCore);
      if (coreHasIA[1]) {
        // case D: DST is core
        return combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
      } else {
        from = dstWildcard;
        // case C: DST is not core
        // We have to query down segments because SRC may not have a segment connected to DST
        Seg.SegmentsResponse segmentsDown = getSegments(segmentStub, from, dstIsdAs);
        segments.add(segmentsDown);
        return Segments.combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
      }
    }
    // remaining cases: F, G, H
    Seg.SegmentsResponse segmentsCore = getSegments(segmentStub, from, dstWildcard);
    boolean[] localCores = Segments.containsIsdAs(segmentsCore, srcIsdAs, dstIsdAs);
    segments.add(segmentsCore);
    if (localCores[1]) {
      return Segments.combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
    }

    Seg.SegmentsResponse segmentsDown = getSegments(segmentStub, dstWildcard, dstIsdAs);
    segments.add(segmentsDown);
    return Segments.combineSegments(segments, srcIsdAs, dstIsdAs, brLookup);
  }

  private static long toWildcard(long isdAs) {
    return (isdAs >>> 48) << 48;
  }

  private static boolean isWildcard(long isdAs) {
    return toWildcard(isdAs) == isdAs;
  }
}
