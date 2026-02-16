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

package org.scion.jpan.internal.paths;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.util.ByteUtil;
import org.scion.jpan.internal.util.MultiMap;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.crypto.Signed;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.proto.endhost.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class gets segment information from a path service and constructs paths.
 *
 * <p>Assumption: When requesting CORE segments (inter-ISD or intra-ISD), the path service will
 * return at least one segment for every AS in the src-ISD to connecting to every AS in the dst-ISD.
 * Intra-ISD this is ensured by mutual registration, inter-ISD this is ensured by sending PCB to all
 * other core-AS.<br>
 * Source: <a href="https://datatracker.ietf.org/doc/draft-dekater-scion-controlplane/">...</a>
 *
 * <p>3.2. Core Path-Segment Registration <br>
 * The core beaconing process creates path segments from core AS to core AS. These core-segments are
 * then added to the control service path database of the core AS that created the segment, so that
 * local and remote endpoints can obtain and use these core-segments. In contrast to the intra-ISD
 * registration procedure, there is no need to register core-segments with other core ASes (as each
 * core AS will receive PCBs originated from every other core AS).
 *
 * <p>
 */
public class Segments {
  private static final Logger LOG = LoggerFactory.getLogger(Segments.class.getName());

  private Segments() {}

  /**
   * Lookup segments, construct paths, and return paths.
   *
   * <p>Implementation notes. The sequence diagrams Fig. 43. and 4.4 in the book (edition 2022)
   * seems to suggest to always request UP, CORE and DOWN, even if paths without CORE are possible.
   * The reference implementation (scionproto) optimizes this a bit by not requesting CORE iff there
   * is only one CORE. Scionproto also uses prior knowledge of whether AS are CORE or not to
   * determine which segments to request.
   *
   * <p>Here, we do things a bit differently because we may not have prior knowledge whether the
   * destination is CORE or not.
   *
   * @param service Segment lookup service
   * @param localAS local AS info
   * @param srcIsdAs source ISD/AS
   * @param dstIsdAs destination ISD/AS
   * @param minimizeLookups If 'true', this attempts to reduce the number of segment request when
   *     constructing a paths. This comes at the cost of having less path variety to chose from,
   *     however, the shortest paths should always be available (unless there is a path with a CORE
   *     segment that is shorter than a pure UP and/or DOWN path). This is similar, but not equal
   *     to, depth-first search as proposed in the Scion book 2022, page 82.
   * @return list of available paths (unordered)
   */
  public static List<Daemon.Path> getPaths(
      ControlServiceGrpc service,
      LocalAS localAS,
      long srcIsdAs,
      long dstIsdAs,
      boolean minimizeLookups) {
    List<Daemon.Path> path =
        getPathsInternal(service, localAS, srcIsdAs, dstIsdAs, minimizeLookups);
    path.sort(Comparator.comparingInt(Daemon.Path::getInterfacesCount));
    return path;
  }

  private static List<Daemon.Path> getPathsInternal(
      ControlServiceGrpc service,
      LocalAS localAS,
      long srcIsdAs,
      long dstIsdAs,
      boolean minimizeLookups) {
    long srcWildcard = ScionUtil.toWildcard(srcIsdAs);
    long dstWildcard = ScionUtil.toWildcard(dstIsdAs);

    if (srcIsdAs == dstIsdAs) {
      // case A: same AS, return empty path
      Daemon.Path.Builder path = Daemon.Path.newBuilder();
      path.setMtu(localAS.getMtu());
      path.setExpiration(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE).build());
      return Collections.singletonList(path.build());
    }

    // First, if necessary, try to get UP segments
    List<PathSegment> segmentsUp = Collections.emptyList();
    if (!localAS.isCoreAs()) {
      // get UP segments
      segmentsUp = getSegments(service, srcIsdAs, srcWildcard);
      if (segmentsUp.isEmpty()) {
        return Collections.emptyList();
      }
      if (minimizeLookups) {
        List<PathSegment> directUp = filterForIsdAs(segmentsUp, dstIsdAs);
        if (!directUp.isEmpty()) {
          // DST is core or on-path
          PathDuplicationFilter paths = new PathDuplicationFilter();
          combineSegment(paths, directUp, localAS, srcIsdAs, dstIsdAs);
          return paths.getPaths();
        }
      }
    }

    // Next, we look for core segments.
    // Even if the DST is reachable without a CORE segment (e.g. it is directly a reachable leaf)
    // we still should look at core segments because they may offer additional paths.
    List<PathSegment> segmentsCore = getSegments(service, srcWildcard, dstWildcard);
    if (ScionUtil.extractIsd(srcIsdAs) != ScionUtil.extractIsd(dstIsdAs)
        && segmentsCore.isEmpty()) {
      return Collections.emptyList();
    }
    if (localAS.isCoreAs()) {
      // SRC is core, we can disregard all CORE segments that don't end with SRC
      segmentsCore = filterForEndIsdAs(segmentsCore, srcIsdAs);
    }
    // For CORE we ensure that dstIsdAs is at the END of a segment, not somewhere in the middle
    if (endsWithIsdAs(segmentsCore, dstIsdAs)) {
      // dst is CORE
      return combineSegments(
          segmentsUp, segmentsCore, Collections.emptyList(), srcIsdAs, dstIsdAs, localAS);
    }

    List<PathSegment> segmentsDown = getSegments(service, dstWildcard, dstIsdAs);
    return combineSegments(segmentsUp, segmentsCore, segmentsDown, srcIsdAs, dstIsdAs, localAS);
  }

  private static List<PathSegment> getSegments(
      ControlServiceGrpc segmentStub, long srcIsdAs, long dstIsdAs) {
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Requesting segments: {} {}",
          ScionUtil.toStringIA(srcIsdAs),
          ScionUtil.toStringIA(dstIsdAs));
    }
    Seg.SegmentsRequest request =
        Seg.SegmentsRequest.newBuilder().setSrcIsdAs(srcIsdAs).setDstIsdAs(dstIsdAs).build();
    long t0 = System.nanoTime();
    Seg.SegmentsResponse response = segmentStub.segments(request);
    long t1 = System.nanoTime();
    LOG.info("Segment request took {} ms.", (t1 - t0) / 1_000_000);
    if (response.getSegmentsMap().size() > 1) {
      throw new UnsupportedOperationException();
    }
    return getPathSegments(response);
  }

  private static List<PathSegment> getPathSegments(Seg.SegmentsResponse response) {
    List<PathSegment> pathSegments = new ArrayList<>();
    for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> seg :
        response.getSegmentsMap().entrySet()) {
      SegmentType type = SegmentType.from(Seg.SegmentType.forNumber(seg.getKey()));
      seg.getValue()
          .getSegmentsList()
          .forEach(path -> pathSegments.add(new PathSegment(path, type)));
      LOG.info("Segments found of type {}: {}", type.name(), seg.getValue().getSegmentsCount());
    }
    return pathSegments;
  }

  /**
   * See {@link #getPaths(ControlServiceGrpc, LocalAS, long, long, boolean)}.
   *
   * @param service PathService
   * @param localAS localAS number // TODO remove!!
   * @param srcIsdAs source ISD/AS
   * @param dstIsdAs destination ISD/AS
   * @return list of paths
   */
  public static List<Daemon.Path> getPaths(
      PathServiceRpc service, LocalAS localAS, long srcIsdAs, long dstIsdAs) {
    List<Daemon.Path> path = getPathsInternal(service, localAS, srcIsdAs, dstIsdAs);
    // TODO sort?
    // path.sort(Comparator.comparingInt(Daemon.Path::getInterfacesCount));
    return path;
  }

  private static List<Daemon.Path> getPathsInternal(
      PathServiceRpc service, LocalAS localAS, long srcIsdAs, long dstIsdAs) {
    if (srcIsdAs == dstIsdAs) {
      // same AS, return empty path
      Daemon.Path.Builder path = Daemon.Path.newBuilder();
      path.setMtu(localAS.getMtu());
      path.setExpiration(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE).build());
      return Collections.singletonList(path.build());
    }

    List<PathSegment>[] segments = getSegments(service, srcIsdAs, dstIsdAs);
    return combineSegments(segments[0], segments[1], segments[2], srcIsdAs, dstIsdAs, localAS);
  }

  private static List<PathSegment>[] getSegments(
      PathServiceRpc segmentStub, long srcIsdAs, long dstIsdAs) {
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Requesting segments: {} {}",
          ScionUtil.toStringIA(srcIsdAs),
          ScionUtil.toStringIA(dstIsdAs));
    }
    long t0 = System.nanoTime();
    Path.ListSegmentsResponse response = segmentStub.segments(srcIsdAs, dstIsdAs);
    long t1 = System.nanoTime();
    LOG.info("Segment request took {} ms.", (t1 - t0) / 1_000_000);

    List<PathSegment>[] segments =
        new List[] {new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
    response
        .getUpSegmentsList()
        .forEach(path -> segments[0].add(new PathSegment(path, SegmentType.UP)));
    response
        .getCoreSegmentsList()
        .forEach(path -> segments[1].add(new PathSegment(path, SegmentType.CORE)));
    response
        .getDownSegmentsList()
        .forEach(path -> segments[2].add(new PathSegment(path, SegmentType.DOWN)));
    return segments;
  }

  private static List<Daemon.Path> combineSegments(
      List<PathSegment> segmentsUp,
      List<PathSegment> segmentsCore,
      List<PathSegment> segmentsDown,
      long srcIsdAs,
      long dstIsdAs,
      LocalAS localAS) {
    int code = !segmentsUp.isEmpty() ? 4 : 0;
    code |= !segmentsCore.isEmpty() ? 2 : 0;
    code |= !segmentsDown.isEmpty() ? 1 : 0;
    PathDuplicationFilter paths = new PathDuplicationFilter();
    switch (code) {
      case 7:
        combineThreeSegments(
            paths, segmentsUp, segmentsCore, segmentsDown, srcIsdAs, dstIsdAs, localAS);
        if (ScionUtil.extractIsd(srcIsdAs) == ScionUtil.extractIsd(dstIsdAs)) {
          combineTwoSegments(paths, segmentsUp, segmentsDown, srcIsdAs, dstIsdAs, localAS);
        }
        break;
      case 6:
        combineTwoSegments(paths, segmentsUp, segmentsCore, srcIsdAs, dstIsdAs, localAS);
        combineSegment(paths, filterForIsdAs(segmentsUp, dstIsdAs), localAS, srcIsdAs, dstIsdAs);
        break;
      case 5:
        combineTwoSegments(paths, segmentsUp, segmentsDown, srcIsdAs, dstIsdAs, localAS);
        break;
      case 4:
        combineSegment(paths, segmentsUp, localAS, srcIsdAs, dstIsdAs);
        break;
      case 3:
        combineTwoSegments(paths, segmentsCore, segmentsDown, srcIsdAs, dstIsdAs, localAS);
        combineSegment(paths, filterForIsdAs(segmentsDown, srcIsdAs), localAS, srcIsdAs, dstIsdAs);
        break;
      case 2:
        combineSegment(paths, segmentsCore, localAS, srcIsdAs, dstIsdAs);
        break;
      case 1:
        combineSegment(paths, segmentsDown, localAS, srcIsdAs, dstIsdAs);
        break;
      default:
        // We found segments, but they don't form a path. This can happen, for example,
        // when we query for a non-existing AS
        return paths.getPaths();
    }
    return paths.getPaths();
  }

  private static void combineSegment(
      PathDuplicationFilter paths,
      List<PathSegment> segments,
      LocalAS localAS,
      long srcIsdAs,
      long dstIsdAs) {
    for (PathSegment pathSegment : segments) {
      if (containsIsdAses(pathSegment, srcIsdAs, dstIsdAs)) {
        buildPath(paths, localAS, dstIsdAs, pathSegment);
      }
    }
  }

  /**
   * Creates path from two segments. E.g. Up+Core or Core+Down.
   *
   * @param segments0 Up or Core segments
   * @param segments1 Core or Down segments
   * @param srcIsdAs src ISD/AS
   * @param dstIsdAs src ISD/AS
   * @param localAS border router lookup resource
   */
  private static void combineTwoSegments(
      PathDuplicationFilter paths,
      List<PathSegment> segments0,
      List<PathSegment> segments1,
      long srcIsdAs,
      long dstIsdAs,
      LocalAS localAS) {
    // Map IsdAs to pathSegment
    MultiMap<Long, PathSegment> segmentsMap1 = createSegmentsMap(segments1, dstIsdAs);

    for (PathSegment pathSegment0 : segments0) {
      long middleIsdAs = getOtherIsdAs(srcIsdAs, pathSegment0);
      for (PathSegment pathSegment1 : segmentsMap1.get(middleIsdAs)) {
        buildPath(paths, localAS, dstIsdAs, pathSegment0, pathSegment1);
      }
    }
  }

  private static void combineThreeSegments(
      PathDuplicationFilter paths,
      List<PathSegment> segmentsUp,
      List<PathSegment> segmentsCore,
      List<PathSegment> segmentsDown,
      long srcIsdAs,
      long dstIsdAs,
      LocalAS localAS) {
    // Map IsdAs to pathSegment
    MultiMap<Long, PathSegment> upSegments = createSegmentsMap(segmentsUp, srcIsdAs);
    MultiMap<Long, PathSegment> downSegments = createSegmentsMap(segmentsDown, dstIsdAs);

    for (PathSegment pathSeg : segmentsCore) {
      long[] endIAs = getEndingIAs(pathSeg);
      if (upSegments.contains(endIAs[0]) && downSegments.contains(endIAs[1])) {
        buildPath(
            paths,
            upSegments.get(endIAs[0]),
            pathSeg,
            downSegments.get(endIAs[1]),
            localAS,
            dstIsdAs);
      } else if (upSegments.contains(endIAs[1]) && downSegments.contains(endIAs[0])) {
        buildPath(
            paths,
            upSegments.get(endIAs[1]),
            pathSeg,
            downSegments.get(endIAs[0]),
            localAS,
            dstIsdAs);
      }
    }
  }

  private static void buildPath(
      PathDuplicationFilter paths,
      List<PathSegment> segmentsUp,
      PathSegment segCore,
      List<PathSegment> segmentsDown,
      LocalAS localAS,
      long dstIA) {
    for (PathSegment segUp : segmentsUp) {
      for (PathSegment segDown : segmentsDown) {
        buildPath(paths, localAS, dstIA, segUp, segCore, segDown);
      }
    }
  }

  private static void buildPath(
      PathDuplicationFilter paths, LocalAS localAS, long dstIsdAs, PathSegment... segments) {
    Daemon.Path.Builder path = Daemon.Path.newBuilder();
    ByteBuffer raw = ByteBuffer.allocate(1000);

    Range[] ranges = new Range[segments.length]; // [start (inclusive), end (exclusive), increment]
    long startIA = localAS.getIsdAs();
    final ByteUtil.MutLong endingIA = new ByteUtil.MutLong(-1);
    for (int i = 0; i < segments.length; i++) {
      ranges[i] = createRange(segments[i], startIA, endingIA);
      startIA = endingIA.get();
    }

    // Search for on-path and shortcuts.
    if (detectOnPathUp(segments, dstIsdAs, ranges)) {
      segments = new PathSegment[] {segments[0]};
      ranges = new Range[] {ranges[0]};
      LOG.debug("Found on-path AS on UP segment.");
    } else if (detectOnPathDown(segments, localAS.getIsdAs(), ranges)) {
      segments = new PathSegment[] {segments[segments.length - 1]};
      ranges = new Range[] {ranges[ranges.length - 1]};
      LOG.debug("Found on-path AS on DOWN segment.");
    } else if (detectShortcut(segments, ranges)) {
      // The following is a no-op if there is no CORE segment
      segments = new PathSegment[] {segments[0], segments[segments.length - 1]};
      ranges = new Range[] {ranges[0], ranges[ranges.length - 1]};
      LOG.debug("Found shortcut at hop {}:", ranges[0].end());
    }

    // path meta header
    int pathMetaHeader = 0;
    for (int i = 0; i < segments.length; i++) {
      int hopCount = ranges[i].size();
      pathMetaHeader |= hopCount << (6 * (2 - i));
    }
    raw.putInt(pathMetaHeader);

    // info fields
    for (int i = 0; i < segments.length; i++) {
      writeInfoField(raw, segments[i].info, ranges[i].increment());
      calcBetaCorrection(raw, 6 + i * 8, segments[i], ranges[i]);
    }

    // hop fields
    path.setMtu(localAS.getMtu());
    for (int i = 0; i < segments.length; i++) {
      // bytePosSegID: 6 = 4 bytes path head + 2 byte flag in first info field
      writeHopFields(path, raw, 6 + i * 8, segments[i], ranges[i]);
    }

    raw.flip();
    path.setRaw(ByteString.copyFrom(raw));

    // First hop
    String firstHop = localAS.getBorderRouterAddressString((int) path.getInterfaces(0).getId());
    Daemon.Underlay underlay = Daemon.Underlay.newBuilder().setAddress(firstHop).build();
    Daemon.Interface interfaceAddr = Daemon.Interface.newBuilder().setAddress(underlay).build();
    path.setInterface(interfaceAddr);

    // Metadata
    SegmentMetadataAccumulator.writeStaticInfoMetadata(path, segments, ranges);

    paths.checkDuplicatePaths(path);
  }

  private static void calcBetaCorrection(
      ByteBuffer raw, int bytePosSegID, PathSegment segment, Range range) {
    // When we create a shortcut or on-path, we need to remove the MACs from the segID / beta.
    byte[] fix = new byte[2];

    // We remove all MACs from start of the segment to start of the range that is actually used.
    int startRange = range.isReversed() ? range.last() : range.first();
    for (int pos = 0; pos < startRange; pos++) {
      ByteString mac = segment.getAsEntriesList().get(pos).getHopEntry().getHopField().getMac();
      fix[0] ^= mac.byteAt(0);
      fix[1] ^= mac.byteAt(1);
    }

    raw.put(bytePosSegID, ByteUtil.toByte(raw.get(bytePosSegID) ^ fix[0]));
    raw.put(bytePosSegID + 1, ByteUtil.toByte(raw.get(bytePosSegID + 1) ^ fix[1]));
  }

  private static Range createRange(PathSegment pathSegment, long startIA, ByteUtil.MutLong endIA) {
    Seg.ASEntrySignedBody body0 = pathSegment.getAsEntriesFirst();
    Seg.ASEntrySignedBody bodyN = pathSegment.getAsEntriesLast();
    if (body0.getIsdAs() == startIA) {
      endIA.set(bodyN.getIsdAs());
      return new Range(0, pathSegment.getAsEntriesCount(), +1);
    } else if (bodyN.getIsdAs() == startIA) {
      endIA.set(body0.getIsdAs());
      return new Range(pathSegment.getAsEntriesCount() - 1, -1, -1);
    }
    throw new UnsupportedOperationException("Relevant IA is not an ending IA!");
  }

  private static long calcExpTime(long baseTime, int deltaTime) {
    return baseTime + (long) (1 + deltaTime) * 24 * 60 * 60 / 256;
  }

  private static void writeInfoField(ByteBuffer raw, Seg.SegmentInformation info, int direction) {
    // construction direction flag
    int inf0 = ((direction == -1 ? 0 : 1) << 24) | info.getSegmentId();
    raw.putInt(inf0);
    raw.putInt(ByteUtil.toInt(info.getTimestamp()));
  }

  private static void writeHopFields(
      Daemon.Path.Builder path,
      ByteBuffer raw,
      int bytePosSegID,
      PathSegment pathSegment,
      Range range) {
    int minExpiry = Integer.MAX_VALUE;
    path.setExpiration(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE).build());
    for (int pos = range.begin(), total = 0;
        pos != range.end();
        pos += range.increment(), total++) {
      boolean reversed = range.isReversed();
      Seg.ASEntrySignedBody body = pathSegment.getAsEntries(pos);
      Seg.HopEntry hopEntry = body.getHopEntry();
      Seg.HopField hopField = hopEntry.getHopField();

      raw.put((byte) 0);
      raw.put(ByteUtil.toByte(hopField.getExpTime()));
      raw.putShort(ByteUtil.toShort(hopField.getIngress()));
      raw.putShort(ByteUtil.toShort(hopField.getEgress()));
      ByteString mac = hopField.getMac();
      for (int j = 0; j < 6; j++) {
        raw.put(mac.byteAt(j));
      }
      if (reversed && total > 0) {
        raw.put(bytePosSegID, ByteUtil.toByte(raw.get(bytePosSegID) ^ mac.byteAt(0)));
        raw.put(bytePosSegID + 1, ByteUtil.toByte(raw.get(bytePosSegID + 1) ^ mac.byteAt(1)));
      }
      minExpiry = Math.min(minExpiry, hopField.getExpTime());
      path.setMtu(Math.min(path.getMtu(), body.getMtu()));
      if (hopEntry.getIngressMtu() > 0) {
        path.setMtu(Math.min(path.getMtu(), hopEntry.getIngressMtu()));
      }

      // Do this for all except last.
      boolean addInterfaces = pos + range.increment() != range.end();
      if (addInterfaces) {
        Daemon.PathInterface.Builder pib = Daemon.PathInterface.newBuilder();
        pib.setId(reversed ? hopField.getIngress() : hopField.getEgress());
        path.addInterfaces(pib.setIsdAs(body.getIsdAs()).build());

        Daemon.PathInterface.Builder pib2 = Daemon.PathInterface.newBuilder();
        int pos2 = pos + range.increment();
        Seg.ASEntrySignedBody body2 = pathSegment.getAsEntries(pos2);
        Seg.HopField hopField2 = body2.getHopEntry().getHopField();
        pib2.setId(reversed ? hopField2.getEgress() : hopField2.getIngress());
        path.addInterfaces(pib2.setIsdAs(body2.getIsdAs()).build());
      }
    }

    // expiration
    long time = calcExpTime(pathSegment.info.getTimestamp(), minExpiry);
    if (time < path.getExpiration().getSeconds()) {
      path.setExpiration(Timestamp.newBuilder().setSeconds(time).build());
    }
  }

  private static boolean detectShortcut(PathSegment[] segments, Range[] iterators) {
    // Shortcut: the up-segment and down-segment intersect at a non-core AS. In this case, a shorter
    // forwarding path can be created by removing the extraneous part of the path.
    // Also: "the up- and down-segments intersect at a non-core AS. This is the case of a shortcut
    // where an up-segment and a down-segment meet below the ISD core. In this case, a shorter path
    // is made possible by removing the extraneous part of the path to the core."
    if (segments.length < 2) {
      return false;
    }
    int idUp = 0;
    int idDown = segments.length - 1;
    // skip if this starts or ends with a CORE segment
    if (segments[idUp].isCore() || segments[idDown].isCore()) {
      return false;
    }
    HashMap<Long, Integer> map = new HashMap<>();

    int posUp = -1;
    Range iterUp = iterators[idUp];
    for (int pos = iterUp.begin(); pos != iterUp.end(); pos += iterUp.increment()) {
      Seg.ASEntrySignedBody body = segments[idUp].getAsEntries(pos);
      map.putIfAbsent(body.getIsdAs(), pos);
    }

    int posDown = -1;
    Range iterDown = iterators[idDown];
    for (int pos = iterDown.begin(); pos != iterDown.end(); pos += iterDown.increment()) {
      Seg.ASEntrySignedBody body = segments[idDown].getAsEntries(pos);
      long isdAs = body.getIsdAs();
      if (map.containsKey(isdAs)) {
        posUp = map.get(isdAs);
        posDown = pos;
      }
      // keep going, we want to find the LAST/LOWEST AS that occurs twice.
    }
    if (posUp >= 0) {
      iterators[0].setEnd(posUp + iterators[0].increment()); // the range maximum is _exclusive_.
      iterators[1].setBegin(posDown);
      return true;
    }
    return false;
  }

  private static boolean detectOnPathUp(PathSegment[] segments, long dstIsdAs, Range[] ranges) {
    // On-Path (SCION Book 2022, p 106): "In the case where the source’s up-segment contains the
    // destination AS, or the destination's down-segment contains the source AS, a single segment is
    // sufficient to construct the forwarding path. Again, no core AS is on the final path."
    int idUp = 0;
    // skip if this starts with a CORE segment
    if (segments[idUp].isCore()) {
      return false;
    }
    Range iterUp = ranges[idUp];
    for (int pos = iterUp.begin(); pos != iterUp.end(); pos += iterUp.increment()) {
      Seg.ASEntrySignedBody body = segments[idUp].getAsEntries(pos);
      if (body.getIsdAs() == dstIsdAs) {
        iterUp.setEnd(pos + iterUp.increment());
        return true;
      }
    }
    return false;
  }

  private static boolean detectOnPathDown(PathSegment[] segments, long srcIA, Range[] ranges) {
    // On-Path (SCION Book 2022, p 106): "In the case where the source’s up-segment contains the
    // destination AS, or the destination's down-segment contains the source AS, a single segment is
    // sufficient to construct the forwarding path. Again, no core AS is on the final path."
    int idDown = segments.length - 1;
    // skip if this ends with a CORE segment
    if (segments.length < 2 || segments[idDown].isCore()) {
      return false;
    }

    Range iterDown = ranges[idDown];
    for (int pos = iterDown.begin(); pos != iterDown.end(); pos += iterDown.increment) {
      Seg.ASEntrySignedBody body = segments[idDown].getAsEntries(pos);
      if (body.getIsdAs() == srcIA) {
        iterDown.setBegin(pos);
        return true;
      }
    }
    return false;
  }

  private static MultiMap<Long, PathSegment> createSegmentsMap(
      List<PathSegment> pathSegments, long knownIsdAs) {
    MultiMap<Long, PathSegment> map = new MultiMap<>();
    for (PathSegment pathSeg : pathSegments) {
      long unknownIsdAs = getOtherIsdAs(knownIsdAs, pathSeg);
      if (unknownIsdAs != -1) {
        map.put(unknownIsdAs, pathSeg);
      }
    }
    return map;
  }

  private static long getOtherIsdAs(long isdAs, PathSegment seg) {
    long[] endings = getEndingIAs(seg);
    if (endings[0] == isdAs) {
      return endings[1];
    } else if (endings[1] == isdAs) {
      return endings[0];
    }
    return -1;
  }

  private static boolean containsIsdAses(PathSegment seg, long isdAs1, long isdAs2) {
    return seg.getAsEntriesList().stream().anyMatch(asEntry -> asEntry.getIsdAs() == isdAs1)
        && seg.getAsEntriesList().stream().anyMatch(asEntry -> asEntry.getIsdAs() == isdAs2);
  }

  /**
   * @param seg path segment
   * @return first and last ISD/AS of the path segment
   */
  static long[] getEndingIAs(PathSegment seg) {
    Seg.ASEntrySignedBody bodyFirst = seg.getAsEntriesFirst();
    Seg.ASEntrySignedBody bodyLast = seg.getAsEntriesLast();
    return new long[] {bodyFirst.getIsdAs(), bodyLast.getIsdAs()};
  }

  private static Seg.ASEntrySignedBody getBody(Seg.ASEntry asEntry) {
    if (!asEntry.hasSigned()) {
      throw new UnsupportedOperationException("Unsigned entries are not supported");
    }
    Signed.SignedMessage sm = asEntry.getSigned();
    try {
      Signed.HeaderAndBodyInternal habi =
          Signed.HeaderAndBodyInternal.parseFrom(sm.getHeaderAndBody());
      return Seg.ASEntrySignedBody.parseFrom(habi.getBody());
    } catch (InvalidProtocolBufferException e) {
      throw new ScionRuntimeException(e);
    }
  }

  private static Seg.SegmentInformation getInfo(Seg.PathSegment pathSegment) {
    try {
      return Seg.SegmentInformation.parseFrom(pathSegment.getSegmentInfo());
    } catch (InvalidProtocolBufferException e) {
      throw new ScionRuntimeException(e);
    }
  }

  private static List<PathSegment> filterForIsdAs(List<PathSegment> segments, final long isdAs) {
    // Return all segments that go through the given ISD/AS
    return segments.stream()
        .filter(
            pathSegment ->
                pathSegment.getAsEntriesList().stream()
                    .anyMatch(asEntry -> asEntry.getIsdAs() == isdAs))
        .collect(Collectors.toList());
  }

  private static List<PathSegment> filterForEndIsdAs(List<PathSegment> segments, final long isdAs) {
    // Return all segments that end with the given ISD/AS
    return segments.stream()
        .filter(pathSegment -> pathSegment.getAsEntriesLast().getIsdAs() == isdAs)
        .collect(Collectors.toList());
  }

  private static boolean endsWithIsdAs(List<PathSegment> segments, long dstIsdAs) {
    for (PathSegment seg : segments) {
      long iaFirst = seg.getAsEntriesFirst().getIsdAs();
      long iaLast = seg.getAsEntriesLast().getIsdAs();
      if ((iaFirst == dstIsdAs) || (iaLast == dstIsdAs)) {
        return true;
      }
    }
    return false;
  }

  // [start (inclusive), end (exclusive), increment]
  static class Range {
    private int startIncl;
    private int endExcl;
    private final int increment;

    Range(int startIncl, int endExcl, int increment) {
      this.startIncl = startIncl;
      this.endExcl = endExcl;
      this.increment = increment;
    }

    boolean isReversed() {
      return increment == -1;
    }

    public int first() {
      return startIncl;
    }

    int last() {
      return endExcl - increment;
    }

    public int begin() {
      return first();
    }

    public int end() {
      return endExcl;
    }

    public int increment() {
      return increment;
    }

    public void setBegin(int begin) {
      this.startIncl = begin;
    }

    public void setEnd(int end) {
      this.endExcl = end;
    }

    public int size() {
      return Math.abs(endExcl - startIncl);
    }
  }

  private enum SegmentType {
    UP,
    CORE,
    DOWN;

    public static SegmentType from(Seg.SegmentType segmentType) {
      switch (segmentType) {
        case SEGMENT_TYPE_UP:
          return UP;
        case SEGMENT_TYPE_DOWN:
          return DOWN;
        case SEGMENT_TYPE_CORE:
          return CORE;
        default:
          throw new IllegalArgumentException("type=" + segmentType);
      }
    }
  }

  static class PathSegment {
    final Seg.PathSegment segment;
    final List<Seg.ASEntrySignedBody> bodies;
    final Seg.SegmentInformation info;
    final SegmentType type; //

    PathSegment(Seg.PathSegment segment, SegmentType type) {
      this.segment = segment;
      this.bodies =
          Collections.unmodifiableList(
              segment.getAsEntriesList().stream()
                  .map(Segments::getBody)
                  .collect(Collectors.toList()));
      this.info = getInfo(segment);
      this.type = type;
    }

    public Seg.ASEntrySignedBody getAsEntriesFirst() {
      return bodies.get(0);
    }

    public Seg.ASEntrySignedBody getAsEntriesLast() {
      return bodies.get(bodies.size() - 1);
    }

    public List<Seg.ASEntrySignedBody> getAsEntriesList() {
      return bodies;
    }

    public Seg.ASEntrySignedBody getAsEntries(int i) {
      return bodies.get(i);
    }

    public int getAsEntriesCount() {
      return bodies.size();
    }

    public boolean isCore() {
      return type == SegmentType.CORE;
    }
  }
}
