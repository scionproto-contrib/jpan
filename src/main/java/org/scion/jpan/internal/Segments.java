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
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.control_plane.Seg;
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
   * @return list of available paths (unordered)
   */
  public static List<Daemon.Path> getPaths(
      SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub service,
      ScionBootstrapper bootstrapper,
      long srcIsdAs,
      long dstIsdAs) {
    LocalTopology localAS = bootstrapper.getLocalTopology();
    GlobalTopology world = bootstrapper.getGlobalTopology();

    // Cases:
    // A: src==dst
    // B: srcISD==dstISD; dst==core
    // C: srcISD==dstISD; src==core
    // D: srcISD==dstISD; src==core, dst==core
    // E: srcISD==dstISD;
    // F: srcISD!=dstISD; dst==core
    // G: srcISD!=dstISD; src==core
    // H: srcISD!=dstISD;
    long srcWildcard = ScionUtil.toWildcard(srcIsdAs);
    long dstWildcard = ScionUtil.toWildcard(dstIsdAs);
    int srcISD = ScionUtil.extractIsd(srcIsdAs);
    int dstISD = ScionUtil.extractIsd(dstIsdAs);

    if (srcIsdAs == dstIsdAs) {
      // case A: same AS, return empty path
      Daemon.Path.Builder path = Daemon.Path.newBuilder();
      path.setMtu(localAS.getLocalMtu());

      path.setExpiration(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build());
      return Collections.singletonList(path.build());
    }

    // TODO in future we can find out whether an AS is CORE by parsing the TRC files:
    //      https://docs.anapaya.net/en/latest/resources/isd-as-assignments/#as-assignments
    //     ./bazel-bin/scion-pki/cmd/scion-pki/scion-pki_/scion-pki trc inspect
    //         ../../Downloads/ISD64.bundle

    List<Seg.PathSegment> segmentsUp = null;
    List<Seg.PathSegment> segmentsCore = null;
    List<Seg.PathSegment> segmentsDown = null;

    List<List<Seg.PathSegment>> segments = new ArrayList<>();
    // First, if necessary, try to get UP segments
    if (!localAS.isLocalAsCore()) {
      // get UP segments
      // TODO find out if dstIsAs is core and directly ask for it.
      segmentsUp = getSegments(service, srcIsdAs, srcWildcard);
      boolean[] containsIsdAs = containsIsdAs(segmentsUp, srcIsdAs, dstIsdAs);
      if (containsIsdAs[1]) {
        // case B: DST is core (or on-path)
        return combineSegment(segmentsUp, localAS);
      }
      if (segmentsUp.isEmpty()) {
        return Collections.emptyList();
      }
      segments.add(segmentsUp);
    }

    // TODO skip this if core has only one AS
    // Next, we look for core segments.
    // Even if the DST is reachable without a CORE segment (e.g. it is directly a reachable leaf)
    // we still should look at core segments because they may offer additional paths.
    segmentsCore = getSegments(service, srcWildcard, dstWildcard);
    if (!segmentsCore.isEmpty()) {
      segments.add(segmentsCore);
      // TODO For CORE we should probably ensure that dstIsdAs is at the END of a segment, not
      //  somewhere in the middle
      boolean[] containsIsdAs = containsIsdAs(segmentsCore, srcIsdAs, dstIsdAs);
      if (containsIsdAs[1]) {
        // dst is CORE
        return combineSegments(segmentsUp, segmentsCore, segmentsDown, srcIsdAs, dstIsdAs, localAS);
      }
    }

    segmentsDown = getSegments(service, dstWildcard, dstIsdAs);
    segments.add(segmentsDown);
    return Segments.combineSegments(
        segmentsUp, segmentsCore, segmentsDown, srcIsdAs, dstIsdAs, localAS);
  }

  public static List<Daemon.Path> getPaths2(
      SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub,
      ScionBootstrapper bootstrapper,
      long srcIsdAs,
      long dstIsdAs) {
    LocalTopology localAS = bootstrapper.getLocalTopology();
    // Cases:
    // A: src==dst
    // B: srcISD==dstISD; dst==core
    // C: srcISD==dstISD; src==core
    // D: srcISD==dstISD; src==core, dst==core
    // E: srcISD==dstISD;
    // F: srcISD!=dstISD; dst==core
    // G: srcISD!=dstISD; src==core
    // H: srcISD!=dstISD;
    long srcWildcard = ScionUtil.toWildcard(srcIsdAs);
    long dstWildcard = ScionUtil.toWildcard(dstIsdAs);
    int srcISD = ScionUtil.extractIsd(srcIsdAs);
    int dstISD = ScionUtil.extractIsd(dstIsdAs);

    if (srcIsdAs == dstIsdAs) {
      // case A: same AS, return empty path
      Daemon.Path.Builder path = Daemon.Path.newBuilder();
      path.setMtu(localAS.getLocalMtu());
      path.setExpiration(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build());
      return Collections.singletonList(path.build());
    }

    // TODO in future we can find out whether an AS is CORE by parsing the TRC files:
    //      https://docs.anapaya.net/en/latest/resources/isd-as-assignments/#as-assignments
    //     ./bazel-bin/scion-pki/cmd/scion-pki/scion-pki_/scion-pki trc inspect
    //         ../../Downloads/ISD64.bundle

    long from = srcIsdAs;
    long to = dstIsdAs;
    List<List<Seg.PathSegment>> segments = new ArrayList<>();
    // First, if necessary, try to get UP segments
    if (!localAS.isLocalAsCore()) {
      // get UP segments
      // TODO find out if dstIsAs is core and directly ask for it.
      List<Seg.PathSegment> segmentsUp = getSegments(segmentStub, srcIsdAs, srcWildcard);
      boolean[] containsIsdAs = containsIsdAs(segmentsUp, srcIsdAs, dstIsdAs);
      if (containsIsdAs[1]) {
        // case B: DST is core
        return combineSegment(segmentsUp, localAS);
      }
      if (segmentsUp.isEmpty()) {
        return Collections.emptyList();
      }
      segments.add(segmentsUp);
      from = srcWildcard;
    }

    // Remote AS in local ISD?
    if (srcISD == dstISD) {
      // cases C, D, E
      // TODO this is an expensive way to find out whether DST is CORE
      List<Seg.PathSegment> segmentsCoreOrDown = getSegments(segmentStub, from, dstIsdAs);
      if (!segmentsCoreOrDown.isEmpty()) {
        // Okay, we found a direct route from src(Wildcard) to DST
        segments.add(segmentsCoreOrDown);
        List<Daemon.Path> paths = combineSegments(segments, srcIsdAs, dstIsdAs, localAS);
        if (!paths.isEmpty()) {
          return paths;
        }
        // okay, we need CORE!
        // TODO this is horrible.
        segments.remove(segments.size() - 1);
        List<Seg.PathSegment> segmentsCore = getSegments(segmentStub, from, dstWildcard);
        segments.add(segmentsCore);
        segments.add(segmentsCoreOrDown);
        return combineSegments(segments, srcIsdAs, dstIsdAs, localAS);
      }
      // Try again with wildcard (DST is neither core nor a child of FROM)
      List<Seg.PathSegment> segmentsCore = getSegments(segmentStub, from, dstWildcard);
      boolean[] coreHasIA = containsIsdAs(segmentsCore, from, dstIsdAs);
      segments.add(segmentsCore);
      if (coreHasIA[1]) {
        // case D: DST is core
        // TODO why is this ever used? See e.g. Test F0 111->120
        return combineSegments(segments, srcIsdAs, dstIsdAs, localAS);
      } else {
        from = dstWildcard;
        // case C: DST is not core
        // We have to query down segments because SRC may not have a segment connected to DST
        List<Seg.PathSegment> segmentsDown = getSegments(segmentStub, from, dstIsdAs);
        segments.add(segmentsDown);
        return Segments.combineSegments(segments, srcIsdAs, dstIsdAs, localAS);
      }
    }
    // remaining cases: F, G, H
    List<Seg.PathSegment> segmentsCore = getSegments(segmentStub, from, dstWildcard);
    if (segmentsCore.isEmpty()) {
      return Collections.emptyList();
    }
    boolean[] localCores = Segments.containsIsdAs(segmentsCore, 0, dstIsdAs);
    segments.add(segmentsCore);
    if (localCores[1]) {
      return Segments.combineSegments(segments, srcIsdAs, dstIsdAs, localAS);
    }

    List<Seg.PathSegment> segmentsDown = getSegments(segmentStub, dstWildcard, dstIsdAs);
    segments.add(segmentsDown);
    return Segments.combineSegments(segments, srcIsdAs, dstIsdAs, localAS);
  }

  private static List<Seg.PathSegment> getSegments(
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
      LOG.info(
          "CS request took {} ms. Segments found: {}",
          (t1 - t0) / 1_000_000,
          response.getSegmentsMap().size());
      if (response.getSegmentsMap().size() > 1) {
        // TODO fix! We need to be able to handle more than one segment collection (?)
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

  private static List<Seg.PathSegment> getPathSegments(Seg.SegmentsResponse response) {
    List<Seg.PathSegment> pathSegments = new ArrayList<>();
    for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> seg :
        response.getSegmentsMap().entrySet()) {
      pathSegments.addAll(seg.getValue().getSegmentsList());
      String key = Seg.SegmentType.forNumber(seg.getKey()).name();
      LOG.info("Segments found of type {}: {}", key, pathSegments.size());
    }
    return pathSegments;
  }

  private static List<Daemon.Path> combineSegments(
      List<Seg.PathSegment> segmentsUp,
      List<Seg.PathSegment> segmentsCore,
      List<Seg.PathSegment> segmentsDown,
      long srcIsdAs,
      long dstIsdAs,
      LocalTopology localAS) {
    int code = segmentsUp != null ? 4 : 0;
    code |= segmentsCore != null ? 2 : 0;
    code |= segmentsDown != null ? 1 : 0;
    final List<Daemon.Path> paths = new ArrayList<>();
    switch (code) {
      case 7:
        {
          paths.addAll(
              combineThreeSegments(
                  segmentsUp, segmentsCore, segmentsDown, srcIsdAs, dstIsdAs, localAS));
          if (ScionUtil.extractIsd(srcIsdAs) == ScionUtil.extractIsd(dstIsdAs)) {
            paths.addAll(combineTwoSegments(segmentsUp, segmentsDown, srcIsdAs, dstIsdAs, localAS));
          }
          break;
        }
      case 6:
        {
          paths.addAll(combineTwoSegments(segmentsUp, segmentsCore, srcIsdAs, dstIsdAs, localAS));
          paths.addAll(combineSegment(segmentsUp, localAS));
          break;
        }
      case 5:
        {
          paths.addAll(combineTwoSegments(segmentsUp, segmentsDown, srcIsdAs, dstIsdAs, localAS));
          break;
        }
      case 4:
        {
          paths.addAll(combineSegment(segmentsUp, localAS));
          break;
        }
      case 3:
        {
          paths.addAll(combineTwoSegments(segmentsCore, segmentsDown, srcIsdAs, dstIsdAs, localAS));
          paths.addAll(combineSegment(segmentsDown, localAS));
          break;
        }
      case 2:
        {
          paths.addAll(combineSegment(segmentsCore, localAS));
          break;
        }
      case 1:
        {
          paths.addAll(combineSegment(segmentsDown, localAS));
          break;
        }
      default:
        throw new UnsupportedOperationException();
    }
    return paths;
  }

  @Deprecated
  private static List<Daemon.Path> combineSegments(
      List<List<Seg.PathSegment>> segments, long srcIsdAs, long dstIsdAs, LocalTopology localAS) {
    if (segments.size() == 1) {
      return combineSegment(segments.get(0), localAS);
    } else if (segments.size() == 2) {
      return combineTwoSegments(segments.get(0), segments.get(1), srcIsdAs, dstIsdAs, localAS);
    }
    return combineThreeSegments(
        segments.get(0), segments.get(1), segments.get(2), srcIsdAs, dstIsdAs, localAS);
  }

  private static List<Daemon.Path> combineSegment(
      List<Seg.PathSegment> segments, LocalTopology localAS) {
    List<Daemon.Path> paths = new ArrayList<>();
    for (Seg.PathSegment pathSegment : segments) {
      buildPath(paths, localAS, pathSegment);
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
   * @param localAS border router lookup resource
   * @return Paths
   */
  private static List<Daemon.Path> combineTwoSegments(
      List<Seg.PathSegment> segments0,
      List<Seg.PathSegment> segments1,
      long srcIsdAs,
      long dstIsdAs,
      LocalTopology localAS) {
    // Map IsdAs to pathSegment
    MultiMap<Long, Seg.PathSegment> segmentsMap1 = createSegmentsMap(segments1, dstIsdAs);

    List<Daemon.Path> paths = new ArrayList<>();
    for (Seg.PathSegment pathSegment0 : segments0) {
      long middleIsdAs = getOtherIsdAs(srcIsdAs, pathSegment0);
      System.out.println(
          "src/dst/mid="
              + ScionUtil.toStringIA(srcIsdAs)
              + " "
              + ScionUtil.toStringIA(dstIsdAs)
              + " "
              + ScionUtil.toStringIA(middleIsdAs));
      for (Seg.PathSegment pathSegment1 : segmentsMap1.get(middleIsdAs)) {
        buildPath(paths, localAS, pathSegment0, pathSegment1);
      }
    }

    if (ScionUtil.extractIsd(srcIsdAs) == ScionUtil.extractIsd(dstIsdAs)) {
      System.out.println("Seg: same ISD! " + ScionUtil.toStringIA(srcIsdAs));
      // Same ISD? Search for shortcuts.
      int pos = 0;
      for (Daemon.Path path : paths) {
        byte[] before = path.getRaw().toByteArray();
        int segs = path.getRaw().asReadOnlyByteBuffer().getInt();
        System.out.println(
            "Segs: "
                + ((segs >> 12) & 0x3F)
                + " - "
                + ((segs >> 6) & 0x3F)
                + " - "
                + (segs & 0x3F));
        System.out.println("Path1: " + ToStringUtil.path(before));
        System.out.println("Path2: " + ToStringUtil.pathLong(before));
        HashMap<Long, Integer> map = new HashMap<>();
        int posUp = -1;
        int posDown = -1;
        for (int i = 0; i < path.getInterfacesCount(); i++) {
          long isdAs = path.getInterfacesList().get(i).getIsdAs();
          System.out.println("   Checking:  " + ScionUtil.toStringIA(isdAs) + " -> " + i);
          //          if (map.containsKey(isdAs)) {
          //            System.out.println("   Found....:  " + map.get(isdAs) + " -> " +
          // (map.get(isdAs) - i));
          //          }
          if (map.containsKey(isdAs) && i - map.get(isdAs) > 1) {
            System.out.println("   Found!!!!!:  " + ScionUtil.toStringIA(isdAs) + " -> " + i);
            posUp = map.get(isdAs);
            posDown = i;
            System.out.println("                " + posUp + " -> " + posDown);
          } else {
            //            System.out.println("   Adding:" + ScionUtil.toStringIA(isdAs) + " -> " +
            // i);
            map.putIfAbsent(isdAs, i);
          }
          // keep going, we want to find the LAST/LOWEST AS that occurs twice.
        }
        if (posUp >= 0) {
          System.out.println("Seg: Creating shortcut! " + posUp);
          paths.set(pos, createShortCut(path, posUp, posDown, localAS));
        } else {
          System.out.println("Seg: No shortcut! " + posUp);
        }
        pos++;
      }
    }

    return paths;
  }

  private static Daemon.Path createShortCut(
      Daemon.Path oldPath, int posUp, int posDown, LocalTopology brLookup) {
    System.out.println("Removing: " + posUp + " -> " + posDown);
    System.out.println("          " + ScionUtil.toStringPath(oldPath.getRaw().toByteArray()));

    Daemon.Path.Builder newPath = Daemon.Path.newBuilder();
    for (int i = 0; i < oldPath.getInterfacesCount(); i++) {
      System.out.print(ScionUtil.toStringIA(oldPath.getInterfacesList().get(i).getIsdAs()) + " ");
    }
    System.out.println();
    //    for (int i = 0; i < (posDown - posUp); i++) {
    //      newPath.getInterfacesList().remove(posUp);
    //    }
    int hopCount0 = 0;
    int hopCount1 = 0;
    for (int i = 0; i <= posUp; i++) {
      newPath.addInterfaces(oldPath.getInterfaces(i));
      System.out.println("Adding 1: " + i);
      hopCount0++;
    }
    for (int i = posDown; i < oldPath.getInterfacesCount(); i++) {
      newPath.addInterfaces(oldPath.getInterfaces(i));
      hopCount1++;
      System.out.println("Adding 2: " + i);
    }

    // raw path
    ByteBuffer raw = ByteBuffer.allocate(1000);
    ByteBuffer oldRaw = oldPath.getRaw().asReadOnlyByteBuffer();

    // path meta header
    int pathMetaHeader = (hopCount0 << 12) | (hopCount1 << 6);
    raw.putInt(pathMetaHeader);
    int oldOffset = 4; // move position

    // info fields
    //    int nSegments = (hopCount0 > 0 ? 1 : 0) + (hopCount1 > 0 ? 1 : 0);
    //    boolean[] reversed = new boolean[nSegments];
    //    long startIA = brLookup.getLocalIsdAs();
    //    final ByteUtil.MutLong endingIA = new ByteUtil.MutLong(-1);
    //    for (int i = 0; i < infos.length; i++) {
    //      reversed[i] = isReversed(segments[i], startIA, endingIA);
    //      writeInfoField(raw, infos[i], reversed[i]);
    //      startIA = endingIA.get();
    //    }
    if (hopCount0 > 0) {
      raw.putLong(oldRaw.getLong(oldOffset));
      oldOffset += 8;
      System.out.println("Moving 1: " + 1);
    }
    if (hopCount1 > 0) {
      raw.putLong(oldRaw.getLong(oldOffset));
      oldOffset += 8;
      System.out.println("Moving 2: " + 2);
    }

    // hop fields
    newPath.setMtu(brLookup.getLocalMtu());
    //    for (int i = 0; i < segments.length; i++) {
    //      // bytePosSegID: 6 = 4 bytes path head + 2 byte flag in first info field
    //      writeHopFields(path, raw, 6 + i * 8, segments[i], reversed[i], infos[i]);
    //    }
    for (int i = 0; i < hopCount0; i++) {
      for (int j = 0; j < 3; j++) {
        raw.putInt(oldRaw.getInt(oldOffset));
        oldOffset += 4;
      }
      System.out.println("Hopping 1: " + i);
    }
    oldOffset += (oldPath.getInterfacesCount() - 2 - hopCount0 - hopCount1) * 12;
    for (int i = 0; i < hopCount1; i++) {
      for (int j = 0; j < 3; j++) {
        raw.putInt(oldRaw.getInt(oldOffset));
        oldOffset += 4;
      }
      System.out.println("Hopping 2: " + i);
    }

    raw.flip();
    newPath.setRaw(ByteString.copyFrom(raw));

    // TODO where do we get these?
    //    segUp.getSegmentInfo();
    //    path.setLatency();
    //    path.setInternalHops();
    //    path.setNotes();
    // First hop
    String firstHop = brLookup.getBorderRouterAddress((int) newPath.getInterfaces(0).getId());
    Daemon.Underlay underlay = Daemon.Underlay.newBuilder().setAddress(firstHop).build();
    Daemon.Interface interfaceAddr = Daemon.Interface.newBuilder().setAddress(underlay).build();
    newPath.setInterface(interfaceAddr);

    newPath.setExpiration(oldPath.getExpiration());
    // newPath.setMtu(oldPath.getMtu()); // TODO adjust
    // newPath.setInterface(oldPath.getInterface()); // TODO this may change if the shortcut goes
    // DOWN

    return newPath.build();
  }

  private static List<Daemon.Path> combineThreeSegments(
      List<Seg.PathSegment> segmentsUp,
      List<Seg.PathSegment> segmentsCore,
      List<Seg.PathSegment> segmentsDown,
      long srcIsdAs,
      long dstIsdAs,
      LocalTopology localAS) {
    // Map IsdAs to pathSegment
    MultiMap<Long, Seg.PathSegment> upSegments = createSegmentsMap(segmentsUp, srcIsdAs);
    MultiMap<Long, Seg.PathSegment> downSegments = createSegmentsMap(segmentsDown, dstIsdAs);

    List<Daemon.Path> paths = new ArrayList<>();
    for (Seg.PathSegment pathSeg : segmentsCore) {
      long[] endIAs = getEndingIAs(pathSeg);
      if (upSegments.contains(endIAs[0]) && downSegments.contains(endIAs[1])) {
        buildPath(paths, upSegments.get(endIAs[0]), pathSeg, downSegments.get(endIAs[1]), localAS);
      } else if (upSegments.contains(endIAs[1]) && downSegments.contains(endIAs[0])) {
        buildPath(paths, upSegments.get(endIAs[1]), pathSeg, downSegments.get(endIAs[0]), localAS);
      }
    }
    return paths;
  }

  private static void buildPath(
      List<Daemon.Path> paths,
      List<Seg.PathSegment> segmentsUp,
      Seg.PathSegment segCore,
      List<Seg.PathSegment> segmentsDown,
      LocalTopology localAS) {
    for (Seg.PathSegment segUp : segmentsUp) {
      for (Seg.PathSegment segDown : segmentsDown) {
        buildPath(paths, localAS, segUp, segCore, segDown);
      }
    }
  }

  private static void buildPath(
      List<Daemon.Path> paths, LocalTopology localAS, Seg.PathSegment... segments) {
    Daemon.Path.Builder path = Daemon.Path.newBuilder();
    ByteBuffer raw = ByteBuffer.allocate(1000);

    Seg.SegmentInformation[] infos = new Seg.SegmentInformation[segments.length];
    boolean[] reversed = new boolean[segments.length];
    long startIA = localAS.getLocalIsdAs();
    final ByteUtil.MutLong endingIA = new ByteUtil.MutLong(-1);
    for (int i = 0; i < segments.length; i++) {
      infos[i] = getInfo(segments[i]);
      Optional<Boolean> isReversed = isReversed(segments[i], startIA, endingIA);
      if (!isReversed.isPresent()) {
        return;
      }
      reversed[i] = isReversed.get();
      startIA = endingIA.get();
    }

    int[][] segmentRanges = new int[segments.length][2];
    for (int i = 0; i < segments.length; i++) {
      segmentRanges[i][1] = segments[i].getAsEntriesCount();
    }

    // TODO for on-path: avoid 2nd request -> single segment only...
    //    unless the DST is a child of SRC, then we can detect it only on the DOWN path

    // Same ISD? search for on-path and shortcuts.
    if (ScionUtil.extractIsd(localAS.getLocalIsdAs()) == ScionUtil.extractIsd(endingIA.get())) {
      int[] shortcut = detectShortcut(segments, reversed);
      if (shortcut != null) {
        System.out.println("Found shortcut: " + Arrays.toString(shortcut));
        LOG.info("Found shortcut between hop {} and hop {}.", shortcut[0], shortcut[1]);
        // We know we have exactly two segments.
        segmentRanges[0][1] = shortcut[0] + 1; // segmentRanges uses "exclusive" counting
        segmentRanges[1][0] = shortcut[1];
      }
    }

    // path meta header
    int pathMetaHeader = 0;
    for (int i = 0; i < segments.length; i++) {
      int hopCount = segmentRanges[i][1] - segmentRanges[i][0]; // segments[i].getAsEntriesCount();
      pathMetaHeader |= hopCount << (6 * (2 - i));
    }
    raw.putInt(pathMetaHeader);

    // info fields
    for (int i = 0; i < infos.length; i++) {
      writeInfoField(raw, infos[i], reversed[i]);
    }

    // hop fields
    path.setMtu(localAS.getLocalMtu());
    for (int i = 0; i < segments.length; i++) {
      // bytePosSegID: 6 = 4 bytes path head + 2 byte flag in first info field
      writeHopFields(path, raw, 6 + i * 8, segments[i], segmentRanges[i], reversed[i], infos[i]);
    }

    raw.flip();
    path.setRaw(ByteString.copyFrom(raw));

    // TODO where do we get these?
    //    segUp.getSegmentInfo();
    //    path.setLatency();
    //    path.setInternalHops();
    //    path.setNotes();
    // First hop
    String firstHop = localAS.getBorderRouterAddress((int) path.getInterfaces(0).getId());
    Daemon.Underlay underlay = Daemon.Underlay.newBuilder().setAddress(firstHop).build();
    Daemon.Interface interfaceAddr = Daemon.Interface.newBuilder().setAddress(underlay).build();
    path.setInterface(interfaceAddr);

    paths.add(path.build());
  }

  private static Optional<Boolean> isReversed(
      Seg.PathSegment pathSegment, long startIA, ByteUtil.MutLong endIA) {
    Seg.ASEntrySignedBody body0 = getBody(pathSegment.getAsEntriesList().get(0));
    Seg.ASEntry asEntryN = pathSegment.getAsEntriesList().get(pathSegment.getAsEntriesCount() - 1);
    Seg.ASEntrySignedBody bodyN = getBody(asEntryN);
    System.out.println(
        "isReversed:: "
            + ScionUtil.toStringIA(body0.getIsdAs())
            + " "
            + ScionUtil.toStringIA(bodyN.getIsdAs())
            + " start="
            + ScionUtil.toStringIA(startIA)
            + " end="
            + ScionUtil.toStringIA(endIA.get()));
    if (body0.getIsdAs() == startIA) {
      endIA.set(bodyN.getIsdAs());
      return Optional.of(false);
    } else if (bodyN.getIsdAs() == startIA) {
      endIA.set(body0.getIsdAs());
      return Optional.of(true);
    }
    // TODO support short-cut and on-path IAs
    // throw new UnsupportedOperationException("Relevant IA is not an ending IA!");
    return Optional.empty();
  }

  private static long calcExpTime(long baseTime, int deltaTime) {
    return baseTime + (long) (1 + deltaTime) * 24 * 60 * 60 / 256;
  }

  private static void writeInfoField(
      ByteBuffer raw, Seg.SegmentInformation info, boolean reversed) {
    int inf0 = ((reversed ? 0 : 1) << 24) | info.getSegmentId();
    raw.putInt(inf0);
    raw.putInt(ByteUtil.toInt(info.getTimestamp()));
  }

  private static void writeHopFields(
      Daemon.Path.Builder path,
      ByteBuffer raw,
      int bytePosSegID,
      Seg.PathSegment pathSegment,
      int[] segmentRange,
      boolean reversed,
      Seg.SegmentInformation info) {
    int minExpiry = Integer.MAX_VALUE;
    int nTotalHops = pathSegment.getAsEntriesCount();
    for (int i = segmentRange[0]; i < segmentRange[1]; i++) {
      int pos = reversed ? (nTotalHops - i - 1) : i;
      Seg.ASEntrySignedBody body = getBody(pathSegment.getAsEntriesList().get(pos));
      Seg.HopField hopField = body.getHopEntry().getHopField();

      raw.put((byte) 0);
      raw.put(ByteUtil.toByte(hopField.getExpTime()));
      raw.putShort(ByteUtil.toShort(hopField.getIngress()));
      raw.putShort(ByteUtil.toShort(hopField.getEgress()));
      ByteString mac = hopField.getMac();
      for (int j = 0; j < 6; j++) {
        raw.put(mac.byteAt(j));
      }
      if (reversed && i > 0) {
        raw.put(bytePosSegID, ByteUtil.toByte(raw.get(bytePosSegID) ^ mac.byteAt(0)));
        raw.put(bytePosSegID + 1, ByteUtil.toByte(raw.get(bytePosSegID + 1) ^ mac.byteAt(1)));
      }
      minExpiry = Math.min(minExpiry, hopField.getExpTime());
      System.out.println("MTU: " + i + " " + path.getMtu() + " " + body.getMtu());
      path.setMtu(Math.min(path.getMtu(), body.getMtu()));

      boolean addInterfaces = (reversed && pos > 0) || (!reversed && pos < nTotalHops - 1);
      if (addInterfaces) {
        Daemon.PathInterface.Builder pib = Daemon.PathInterface.newBuilder();
        pib.setId(reversed ? hopField.getIngress() : hopField.getEgress());
        path.addInterfaces(pib.setIsdAs(body.getIsdAs()).build());

        Daemon.PathInterface.Builder pib2 = Daemon.PathInterface.newBuilder();
        int pos2 = reversed ? pos - 1 : pos + 1;
        Seg.ASEntrySignedBody body2 = getBody(pathSegment.getAsEntriesList().get(pos2));
        Seg.HopField hopField2 = body2.getHopEntry().getHopField();
        pib2.setId(reversed ? hopField2.getEgress() : hopField2.getIngress());
        path.addInterfaces(pib2.setIsdAs(body2.getIsdAs()).build());
      }
    }

    // expiration
    long time = calcExpTime(info.getTimestamp(), minExpiry);
    if (time < path.getExpiration().getSeconds()) {
      path.setExpiration(Timestamp.newBuilder().setSeconds(minExpiry).build());
    }
  }

  private static int[] detectOnPath(Seg.PathSegment[] segments, boolean[] reversed) {
    if (segments.length != 2) {
      return null;
    }
    //      System.out.println("DOP: Seg: same ISD! " + ScionUtil.toStringIA(srcIsdAs));
    //      System.out.print("DPO: Segment IDss: ");
    //      for (Seg.PathSegment seg : segments) {
    //        System.out.print(getInfo(seg).getSegmentId() + ", ");
    //      }
    //      System.out.println();
    HashMap<Long, Integer> map = new HashMap<>();
    int posUp = -1;
    int posDown = -1;
    int iTotal = 0;
    for (int s = 0; s < segments.length; s++) {
      Seg.PathSegment seg = segments[s];
      final int n = seg.getAsEntriesCount();
      for (int i = 0; i < seg.getAsEntriesCount(); i++, iTotal++) {
        int pos = reversed[s] ? (n - i - 1) : i;
        Seg.ASEntry asEntry = seg.getAsEntriesList().get(pos);
        Seg.ASEntrySignedBody body = getBody(asEntry.getSigned());
        long isdAs = body.getIsdAs();
        System.out.println("   DS: Checking:  " + ScionUtil.toStringIA(isdAs) + " -> " + iTotal);
        // if (map.containsKey(isdAs)) {
        //   System.out.println(
        //       "   Found....:  " + map.get(isdAs) + " -> " + (map.get(isdAs) - iTotal));
        // }
        if (map.containsKey(isdAs) && iTotal - map.get(isdAs) > 1) {
          // System.out.println(
          //     "   DS: Found!!!!!:  " + ScionUtil.toStringIA(isdAs) + " -> " + iTotal);
          posUp = map.get(isdAs);
          posDown = iTotal;
          System.out.println("                " + posUp + " -> " + posDown);
        } else {
          // System.out.println("   Adding:" + ScionUtil.toStringIA(isdAs) + " -> " + iTotal);
          map.putIfAbsent(isdAs, iTotal);
        }
        // keep going, we want to find the LAST/LOWEST AS that occurs twice.
      }
    }
    if (posUp >= 0) {
      System.out.println("DS: Seg: Creating shortcut! " + posUp);
      return new int[] {posUp, posDown};
    } else {
      System.out.println("DS: Seg: No shortcut! " + posUp);
    }
    return null;
  }

  private static int[] detectShortcut(Seg.PathSegment[] segments, boolean[] reversed) {
    if (segments.length != 2) {
      return null;
    }
    //      System.out.println("DS: Seg: same ISD! " + ScionUtil.toStringIA(srcIsdAs));
    //      System.out.print("DS: Segment IDss: ");
    //      for (Seg.PathSegment seg : segments) {
    //        System.out.print(getInfo(seg).getSegmentId() + ", ");
    //      }
    //      System.out.println();
    HashMap<Long, Integer> map = new HashMap<>();
    int posUp = -1;
    int posDown = -1;
    int iTotal = 0;
    for (int s = 0; s < segments.length; s++) {
      Seg.PathSegment seg = segments[s];
      final int n = seg.getAsEntriesCount();
      for (int i = 0; i < seg.getAsEntriesCount(); i++, iTotal++) {
        int pos = reversed[s] ? (n - i - 1) : i;
        Seg.ASEntry asEntry = seg.getAsEntriesList().get(pos);
        Seg.ASEntrySignedBody body = getBody(asEntry.getSigned());
        long isdAs = body.getIsdAs();
        System.out.println("   DS: Checking:  " + ScionUtil.toStringIA(isdAs) + " -> " + iTotal);
        // if (map.containsKey(isdAs)) {
        //   System.out.println(
        //       "   Found....:  " + map.get(isdAs) + " -> " + (map.get(isdAs) - iTotal));
        // }
        if (map.containsKey(isdAs) && iTotal - map.get(isdAs) > 1) {
          // System.out.println(
          //     "   DS: Found!!!!!:  " + ScionUtil.toStringIA(isdAs) + " -> " + iTotal);
          posUp = map.get(isdAs);
          posDown = i;
          System.out.println("                " + posUp + " -> " + posDown);
        } else {
          // System.out.println("   Adding:" + ScionUtil.toStringIA(isdAs) + " -> " + iTotal);
          map.putIfAbsent(isdAs, iTotal);
        }
        // keep going, we want to find the LAST/LOWEST AS that occurs twice.
      }
    }
    if (posUp >= 0) {
      System.out.println("DS: Seg: Creating shortcut! " + posUp);
      return new int[] {posUp, posDown};
    } else {
      System.out.println("DS: Seg: No shortcut! " + posUp);
    }
    return null;
  }

  private static MultiMap<Long, Seg.PathSegment> createSegmentsMap(
      List<Seg.PathSegment> pathSegments, long knownIsdAs) {
    MultiMap<Long, Seg.PathSegment> map = new MultiMap<>();
    for (Seg.PathSegment pathSeg : pathSegments) {
      long unknownIsdAs = getOtherIsdAs(knownIsdAs, pathSeg);
      if (unknownIsdAs != -1) {
        map.put(unknownIsdAs, pathSeg);
      }
    }
    return map;
  }

  private static long getOtherIsdAs(long isdAs, Seg.PathSegment seg) {
    long[] endings = getEndingIAs(seg);
    if (endings[0] == isdAs) {
      return endings[1];
    } else if (endings[1] == isdAs) {
      return endings[0];
    }
    return -1;
  }

  /**
   * @param seg path segment
   * @return first and last ISD/AS of the path segment
   */
  static long[] getEndingIAs(Seg.PathSegment seg) {
    Seg.ASEntry asEntryFirst = seg.getAsEntries(0);
    Seg.ASEntry asEntryLast = seg.getAsEntries(seg.getAsEntriesCount() - 1);
    if (!asEntryFirst.hasSigned() || !asEntryLast.hasSigned()) {
      throw new UnsupportedOperationException("Unsigned entries not (yet) supported"); // TODO
    }
    Seg.ASEntrySignedBody bodyFirst = getBody(asEntryFirst.getSigned());
    Seg.ASEntrySignedBody bodyLast = getBody(asEntryLast.getSigned());
    return new long[] {bodyFirst.getIsdAs(), bodyLast.getIsdAs()};
  }

  private static Seg.ASEntrySignedBody getBody(Signed.SignedMessage sm) {
    try {
      Signed.HeaderAndBodyInternal habi =
          Signed.HeaderAndBodyInternal.parseFrom(sm.getHeaderAndBody());
      return Seg.ASEntrySignedBody.parseFrom(habi.getBody());
    } catch (InvalidProtocolBufferException e) {
      throw new ScionRuntimeException(e);
    }
  }

  private static Seg.ASEntrySignedBody getBody(Seg.ASEntry asEntry) {
    // Let's assume they are all signed
    Signed.SignedMessage sm = asEntry.getSigned();
    return getBody(sm);
  }

  private static Seg.SegmentInformation getInfo(Seg.PathSegment pathSegment) {
    try {
      return Seg.SegmentInformation.parseFrom(pathSegment.getSegmentInfo());
    } catch (InvalidProtocolBufferException e) {
      throw new ScionRuntimeException(e);
    }
  }

  private static boolean[] containsIsdAs(
      List<Seg.PathSegment> segments, long srcIsdAs, long dstIsdAs) {
    boolean[] found = new boolean[] {false, false};
    for (Seg.PathSegment seg : segments) {
      Seg.ASEntry asEntryFirst = seg.getAsEntries(0);
      Seg.ASEntry asEntryLast = seg.getAsEntries(seg.getAsEntriesCount() - 1);
      if (!asEntryFirst.hasSigned() || !asEntryLast.hasSigned()) {
        throw new UnsupportedOperationException("Unsigned entries are not supported");
      }
      // TODO for shortcut/on-path add ALL instead of just ends
      long iaFirst = getBody(asEntryFirst.getSigned()).getIsdAs();
      long iaLast = getBody(asEntryLast.getSigned()).getIsdAs();
      found[0] |= (iaFirst == srcIsdAs) || (iaLast == srcIsdAs);
      found[1] |= (iaFirst == dstIsdAs) || (iaLast == dstIsdAs);
    }
    return found;
  }
}
