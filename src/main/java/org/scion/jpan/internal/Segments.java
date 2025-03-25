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

package org.scion.jpan.internal;

import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegExtensions;
import org.scion.jpan.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.jpan.proto.crypto.Signed;
import org.scion.jpan.proto.daemon.Daemon;
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
   * @param bootstrapper Bootstrapper
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
      SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub service,
      ScionBootstrapper bootstrapper,
      long srcIsdAs,
      long dstIsdAs,
      boolean minimizeLookups) {
    LocalTopology localAS = bootstrapper.getLocalTopology();
    List<Daemon.Path> path = getPaths(service, localAS, srcIsdAs, dstIsdAs, minimizeLookups);
    path.sort(Comparator.comparingInt(Daemon.Path::getInterfacesCount));
    return path;
  }

  private static List<Daemon.Path> getPaths(
      SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub service,
      LocalTopology localAS,
      long srcIsdAs,
      long dstIsdAs,
      boolean minimizeLookups) {
    long srcWildcard = ScionUtil.toWildcard(srcIsdAs);
    long dstWildcard = ScionUtil.toWildcard(dstIsdAs);

    if (srcIsdAs == dstIsdAs) {
      // case A: same AS, return empty path
      Daemon.Path.Builder path = Daemon.Path.newBuilder();
      path.setMtu(localAS.getMtu());
      path.setExpiration(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build());
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
    if (localAS.isCoreAs()) {
      // SRC is core, we can disregard all CORE segments that don't start with SRC
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
      SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub,
      long srcIsdAs,
      long dstIsdAs) {
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Requesting segments: {} {}",
          ScionUtil.toStringIA(srcIsdAs),
          ScionUtil.toStringIA(dstIsdAs));
    }
    Seg.SegmentsRequest request =
        Seg.SegmentsRequest.newBuilder().setSrcIsdAs(srcIsdAs).setDstIsdAs(dstIsdAs).build();
    try {
      long t0 = System.nanoTime();
      Seg.SegmentsResponse response = segmentStub.segments(request);
      long t1 = System.nanoTime();
      LOG.info("Segment request took {} ms.", (t1 - t0) / 1_000_000);
      if (response.getSegmentsMap().size() > 1) {
        throw new UnsupportedOperationException();
      }
      return getPathSegments(response);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.UNKNOWN)) {
        if (e.getMessage().contains("TRC not found")) {
          String msg = ScionUtil.toStringIA(srcIsdAs) + " / " + ScionUtil.toStringIA(dstIsdAs);
          throw new ScionRuntimeException(
              "Error while getting Segments: unknown src/dst ISD-AS: " + msg, e);
        }
        if (e.getMessage().contains("invalid request")) {
          // AS not found
          LOG.info(
              "Requesting segments: {} {} failed (AS unreachable?): {}",
              ScionUtil.toStringIA(srcIsdAs),
              ScionUtil.toStringIA(dstIsdAs),
              e.getMessage());
          return Collections.emptyList();
        }
      }
      if (e.getStatus().getCode().equals(Status.Code.UNAVAILABLE)) {
        throw new ScionRuntimeException(
            "Error while getting Segments: cannot connect to SCION network", e);
      }
      throw new ScionRuntimeException("Error while getting Segment info: " + e.getMessage(), e);
    }
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

  private static List<Daemon.Path> combineSegments(
      List<PathSegment> segmentsUp,
      List<PathSegment> segmentsCore,
      List<PathSegment> segmentsDown,
      long srcIsdAs,
      long dstIsdAs,
      LocalTopology localAS) {
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
      LocalTopology localAS,
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
      LocalTopology localAS) {
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
      LocalTopology localAS) {
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
      LocalTopology localAS,
      long dstIA) {
    for (PathSegment segUp : segmentsUp) {
      for (PathSegment segDown : segmentsDown) {
        buildPath(paths, localAS, dstIA, segUp, segCore, segDown);
      }
    }
  }

  private static void buildPath(
      PathDuplicationFilter paths, LocalTopology localAS, long dstIsdAs, PathSegment... segments) {
    Daemon.Path.Builder path = Daemon.Path.newBuilder();
    ByteBuffer raw = ByteBuffer.allocate(1000);

    int[][] ranges = new int[segments.length][]; // [start (inclusive), end (exclusive), increment]
    long startIA = localAS.getIsdAs();
    final ByteUtil.MutLong endingIA = new ByteUtil.MutLong(-1);
    for (int i = 0; i < segments.length; i++) {
      ranges[i] = createRange(segments[i], startIA, endingIA);
      startIA = endingIA.get();
    }

    // Search for on-path and shortcuts.
    if (detectOnPathUp(segments, dstIsdAs, ranges)) {
      segments = new PathSegment[] {segments[0]};
      ranges = new int[][] {ranges[0]};
      LOG.debug("Found on-path AS on UP segment.");
    } else if (detectOnPathDown(segments, localAS.getIsdAs(), ranges)) {
      segments = new PathSegment[] {segments[segments.length - 1]};
      ranges = new int[][] {ranges[ranges.length - 1]};
      LOG.debug("Found on-path AS on DOWN segment.");
    } else if (detectShortcut(segments, ranges)) {
      // The following is a no-op if there is no CORE segment
      segments = new PathSegment[] {segments[0], segments[segments.length - 1]};
      ranges = new int[][] {ranges[0], ranges[ranges.length - 1]};
      LOG.debug("Found shortcut at hop {}:", ranges[0][1]);
    }

    // path meta header
    int pathMetaHeader = 0;
    for (int i = 0; i < segments.length; i++) {
      int hopCount = Math.abs(ranges[i][1] - ranges[i][0]);
      pathMetaHeader |= hopCount << (6 * (2 - i));
    }
    raw.putInt(pathMetaHeader);

    // info fields
    for (int i = 0; i < segments.length; i++) {
      writeInfoField(raw, segments[i].info, ranges[i][2]);
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

    paths.checkDuplicatePaths(path);
  }

  private static void calcBetaCorrection(
      ByteBuffer raw, int bytePosSegID, PathSegment segment, int[] range) {
    // When we create a shortcut or on-path, we need to remove the MACs from the segID / beta.
    byte[] fix = new byte[2];

    // We remove all MACs from start of the segment to start of the range that is actually used.
    int startRange = range[2] == 1 ? range[0] : range[1] + 1;
    for (int pos = 0; pos < startRange; pos++) {
      ByteString mac = segment.getAsEntriesList().get(pos).getHopEntry().getHopField().getMac();
      fix[0] ^= mac.byteAt(0);
      fix[1] ^= mac.byteAt(1);
    }

    raw.put(bytePosSegID, ByteUtil.toByte(raw.get(bytePosSegID) ^ fix[0]));
    raw.put(bytePosSegID + 1, ByteUtil.toByte(raw.get(bytePosSegID + 1) ^ fix[1]));
  }

  private static int[] createRange(PathSegment pathSegment, long startIA, ByteUtil.MutLong endIA) {
    Seg.ASEntrySignedBody body0 = pathSegment.getAsEntriesFirst();
    Seg.ASEntrySignedBody bodyN = pathSegment.getAsEntriesLast();
    if (body0.getIsdAs() == startIA) {
      endIA.set(bodyN.getIsdAs());
      return new int[] {0, pathSegment.getAsEntriesCount(), +1};
    } else if (bodyN.getIsdAs() == startIA) {
      endIA.set(body0.getIsdAs());
      return new int[] {pathSegment.getAsEntriesCount() - 1, -1, -1};
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
      int[] range) {
    int minExpiry = Integer.MAX_VALUE;
    path.setExpiration(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE).build());
    for (int pos = range[0], total = 0; pos != range[1]; pos += range[2], total++) {
      boolean reversed = range[2] == -1;
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
      boolean addInterfaces = pos + range[2] != range[1];
      System.out.println("wHF: " + pos + "  aIF=" + addInterfaces + "   ->  " + ScionUtil.toStringPath(raw.array()) + " " + ScionUtil.toStringIA(body.getIsdAs())); // TODO
      if (addInterfaces) {
        Daemon.PathInterface.Builder pib = Daemon.PathInterface.newBuilder();
        pib.setId(reversed ? hopField.getIngress() : hopField.getEgress());
        path.addInterfaces(pib.setIsdAs(body.getIsdAs()).build());

        Daemon.PathInterface.Builder pib2 = Daemon.PathInterface.newBuilder();
        int pos2 = pos + range[2];
        Seg.ASEntrySignedBody body2 = pathSegment.getAsEntries(pos2);
        Seg.HopField hopField2 = body2.getHopEntry().getHopField();
        pib2.setId(reversed ? hopField2.getEgress() : hopField2.getIngress());
        path.addInterfaces(pib2.setIsdAs(body2.getIsdAs()).build());
      }
      writeMetadata(path, body, range);
    }

    // expiration
    long time = calcExpTime(pathSegment.info.getTimestamp(), minExpiry);
    if (time < path.getExpiration().getSeconds()) {
      path.setExpiration(Timestamp.newBuilder().setSeconds(time).build());
    }
  }

  private static void writeMetadata(
      Daemon.Path.Builder path,
      Seg.ASEntrySignedBody body,
      int[] range) {
    SegExtensions.PathSegmentExtensions ext = body.getExtensions();
    if (!ext.hasStaticInfo()) {
      return;
    }
    SegExtensions.StaticInfoExtension sie = ext.getStaticInfo();
    Seg.HopField hopField = body.getHopEntry().getHopField();
    boolean reversed = range[2] == -1;
    long id1 = reversed ? hopField.getEgress() : hopField.getIngress();
    long id2 = reversed ? hopField.getIngress() : hopField.getEgress();
    // Don´t add intra for first hop.
    //    System.out.println("writeMetadata: id1=" + id1 + " id2=" + id2 + " " +
    // ScionUtil.toStringIA(body.getIsdAs())); // TODO
    //    if (pos != range[0]) {

    if (!sie.getLatency().getIntraMap().isEmpty()) {
      path.addLatency(toDuration(sie.getLatency().getIntraMap().values().iterator().next()));
    }
    if (!sie.getBandwidth().getIntraMap().isEmpty()) {
      path.addBandwidth(sie.getBandwidth().getIntraMap().values().iterator().next());
    }

//    if (id2 != 0 && sie.getLatency().getIntraMap().containsKey(id2)) {
//      path.addLatency(toDuration(sie.getLatency().getIntraMap().get(id2)));
//    }
////      System.out.println("   lat-intra? " + id2 + " -> " + sie.getLatency().getIntraMap().get(id2)); // TODO
//      if (sie.getBandwidth().getIntraMap().containsKey(id2)) {
//        path.addBandwidth(sie.getBandwidth().getIntraMap().get(id2));
//      }
      // key: IF id of the "other" interface. TODO what is "other"?
//            System.out.println("range: " + Arrays.toString(range) + " pos=" + pos + " " +
//       sie.getInternalHopsMap()); // TODO
//            for (Map.Entry<Long, Integer> o: sie.getInternalHopsMap().entrySet()) {
//              System.out.println("   hop:: " + o.getKey() + "  "   + o.getValue()); // TODO
//            }
      if (sie.getInternalHopsMap().containsKey(id2)) {
        path.addInternalHops(sie.getInternalHopsMap().get(id2));
      }
    if (id1 != 0) {
      path.addGeo(toGeo(sie.getGeoMap().get(id1)));
    }
    //    }
    if (id1 != 0 && sie.getLatency().getInterMap().containsKey(id1)) {
      path.addLatency(toDuration(sie.getLatency().getInterMap().get(id1)));
    }
    System.out.println("   lat inter?: " + id1 + "  " + sie.getLatency().getInterMap().get(id1)); // TODO
    System.out.println("   bw-inter?: " + id1 + "  " + sie.getBandwidth().getInterMap().get(id1)); // TODO
    System.out.println("   lat inter2?: " + id2 + "  " + sie.getLatency().getInterMap().get(id2)); // TODO
    System.out.println("   bw-inter2?: " + id2 + "  " + sie.getBandwidth().getInterMap().get(id2)); // TODO
    if (id1 != 0) {
      Long bw = sie.getBandwidth().getInterMap().get(id1);
      path.addBandwidth(bw == null ? 0 : bw);
    }
    if (id2 != 0) {
      path.addGeo(toGeo(sie.getGeoMap().get(id2)));
    }

    path.addLinkType(toLinkType(sie.getLinkTypeMap().get(id1)));
    path.addNotes(sie.getNote());
  }

  private static Duration toDuration(Integer micros) {
    if (micros == null) {
      return Duration.newBuilder().setSeconds(-1).setNanos(-1).build();
    }
    int secs = micros / 1_000_000;
    int nanos = (micros % 1_000_000) * 1_000;
    return Duration.newBuilder().setSeconds(secs).setNanos(nanos).build();
  }

  private static Daemon.GeoCoordinates toGeo(SegExtensions.GeoCoordinates geo) {
    if (geo == null) {
      return Daemon.GeoCoordinates.newBuilder().build();
    }
    return Daemon.GeoCoordinates.newBuilder()
        .setLatitude(geo.getLatitude())
        .setLongitude(geo.getLongitude())
        .setAddress(geo.getAddress())
        .build();
  }

  private static Daemon.LinkType toLinkType(SegExtensions.LinkType lt) {
    if (lt == null) {
      return Daemon.LinkType.LINK_TYPE_UNSPECIFIED;
    }
    switch (lt) {
      case LINK_TYPE_UNSPECIFIED:
        return Daemon.LinkType.LINK_TYPE_UNSPECIFIED;
      case LINK_TYPE_DIRECT:
        return Daemon.LinkType.LINK_TYPE_DIRECT;
      case LINK_TYPE_MULTI_HOP:
        return Daemon.LinkType.LINK_TYPE_MULTI_HOP;
      case LINK_TYPE_OPEN_NET:
        return Daemon.LinkType.LINK_TYPE_OPEN_NET;
      case UNRECOGNIZED:
      default:
        return Daemon.LinkType.UNRECOGNIZED;
    }
  }

  private static boolean detectShortcut(PathSegment[] segments, int[][] iterators) {
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
    int[] iterUp = iterators[idUp];
    for (int pos = iterUp[0]; pos != iterUp[1]; pos += iterUp[2]) {
      Seg.ASEntrySignedBody body = segments[idUp].getAsEntries(pos);
      map.putIfAbsent(body.getIsdAs(), pos);
    }

    int posDown = -1;
    int[] iterDown = iterators[idDown];
    for (int pos = iterDown[0]; pos != iterDown[1]; pos += iterDown[2]) {
      Seg.ASEntrySignedBody body = segments[idDown].getAsEntries(pos);
      long isdAs = body.getIsdAs();
      if (map.containsKey(isdAs)) {
        posUp = map.get(isdAs);
        posDown = pos;
      }
      // keep going, we want to find the LAST/LOWEST AS that occurs twice.
    }
    if (posUp >= 0) {
      iterators[0][1] = posUp + iterators[0][2]; // the range maximum is _exclusive_.
      iterators[1][0] = posDown;
      return true;
    }
    return false;
  }

  private static boolean detectOnPathUp(PathSegment[] segments, long dstIsdAs, int[][] ranges) {
    // On-Path (SCION Book 2022, p 106): "In the case where the source’s up-segment contains the
    // destination AS, or the destination's down-segment contains the source AS, a single segment is
    // sufficient to construct the forwarding path. Again, no core AS is on the final path."
    int idUp = 0;
    // skip if this starts with a CORE segment
    if (segments[idUp].isCore()) {
      return false;
    }
    int[] iterUp = ranges[idUp];
    for (int pos = iterUp[0]; pos != iterUp[1]; pos += iterUp[2]) {
      Seg.ASEntrySignedBody body = segments[idUp].getAsEntries(pos);
      if (body.getIsdAs() == dstIsdAs) {
        ranges[idUp][1] = pos + ranges[idUp][2];
        return true;
      }
    }
    return false;
  }

  private static boolean detectOnPathDown(PathSegment[] segments, long srcIA, int[][] ranges) {
    // On-Path (SCION Book 2022, p 106): "In the case where the source’s up-segment contains the
    // destination AS, or the destination's down-segment contains the source AS, a single segment is
    // sufficient to construct the forwarding path. Again, no core AS is on the final path."
    int idDown = segments.length - 1;
    // skip if this ends with a CORE segment
    if (segments.length < 2 || segments[idDown].isCore()) {
      return false;
    }

    int[] iterDown = ranges[idDown];
    for (int pos = iterDown[0]; pos != iterDown[1]; pos += iterDown[2]) {
      Seg.ASEntrySignedBody body = segments[idDown].getAsEntries(pos);
      if (body.getIsdAs() == srcIA) {
        ranges[idDown][0] = pos;
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
        .filter(
            pathSegment ->
                pathSegment.getAsEntriesFirst().getIsdAs() == isdAs
                    || pathSegment.getAsEntriesLast().getIsdAs() == isdAs)
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

  private static class PathSegment {
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
