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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.scion.ScionException;
import org.scion.proto.control_plane.Seg;
import org.scion.proto.crypto.Signed;
import org.scion.proto.daemon.Daemon;

public class Segments {
  public static List<Daemon.Path> getPathMultiISD(
      Set<Map.Entry<Integer, Seg.SegmentsResponse.Segments>> segmentsUp,
      Set<Map.Entry<Integer, Seg.SegmentsResponse.Segments>> segmentsCore,
      Set<Map.Entry<Integer, Seg.SegmentsResponse.Segments>> segmentsDown,
      long srcIsdAs,
      long dstIsdAs)
      throws ScionException, InvalidProtocolBufferException {
    // Map IsdAs to pathSegment
    MultiMap<Long, Seg.PathSegment> upSegments = createSegmentsMap(segmentsUp, srcIsdAs);
    MultiMap<Long, Seg.PathSegment> downSegments = createSegmentsMap(segmentsDown, dstIsdAs);

    List<Daemon.Path> paths = new ArrayList<>();
    for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> seg : segmentsCore) {
      for (Seg.PathSegment pathSeg : seg.getValue().getSegmentsList()) {
        long[] IAs = getEndingIAs(pathSeg);

        if (upSegments.get(IAs[0]) == null || downSegments.get(IAs[1]) == null) {
          // This should not happen, we have a core segment that has no matching
          // up/down segments
          throw new IllegalStateException(); // TODO actually, this appears to be happening!
          // continue;
        }
        buildPath(paths, upSegments.get(IAs[0]), pathSeg, downSegments.get(IAs[1]));
      }
    }
    return paths;
  }

  public static List<Daemon.Path> getPathSingleISD(
      Set<Map.Entry<Integer, Seg.SegmentsResponse.Segments>> segmentsUp,
      Set<Map.Entry<Integer, Seg.SegmentsResponse.Segments>> segmentsDown,
      long srcIsdAs,
      long dstIsdAs)
      throws ScionException, InvalidProtocolBufferException {
    // Map IsdAs to pathSegment
    MultiMap<Long, Seg.PathSegment> downSegments = createSegmentsMap(segmentsDown, dstIsdAs);

    List<Daemon.Path> paths = new ArrayList<>();
    for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> segUp : segmentsUp) {
      for (Seg.PathSegment pathSegUp : segUp.getValue().getSegmentsList()) {
        long coreIsdAs = getOtherIsdAs(srcIsdAs, pathSegUp);
        if (downSegments.get(coreIsdAs) == null) {
          // ignore, this should not happen.
          continue;
        }
        for (Seg.PathSegment segDown : downSegments.get(coreIsdAs)) {
          paths.add(buildPath(pathSegUp, null, segDown));
        }
      }
    }
    return paths;
  }

  private static void buildPath(
      List<Daemon.Path> paths,
      List<Seg.PathSegment> segmentsUp,
      Seg.PathSegment segCore,
      List<Seg.PathSegment> segmentsDown)
      throws InvalidProtocolBufferException {
    for (Seg.PathSegment segUp : segmentsUp) {
      for (Seg.PathSegment segDown : segmentsDown) {
        paths.add(buildPath(segUp, segCore, segDown));
      }
    }
  }

  private static Daemon.Path buildPath(
      Seg.PathSegment segUp, Seg.PathSegment segCore, Seg.PathSegment segDown)
      throws InvalidProtocolBufferException {
    Daemon.Path.Builder path = Daemon.Path.newBuilder();
    ByteBuffer raw = ByteBuffer.allocate(1000);

    // path meta header
    int hopCount0 = segUp.getAsEntriesCount();
    int hopCount1 = segCore == null ? 0 : segCore.getAsEntriesCount();
    int hopCount2 = segDown.getAsEntriesCount();
    int i0;
    if (hopCount1 > 0) {
      i0 = (hopCount0 << 12) | (hopCount1 << 6) | hopCount2;
    } else {
      i0 = (hopCount0 << 12) | (hopCount2 << 6);
    }
    raw.putInt(i0);

    // info fields
    if (hopCount0 == 0) {
      // TODO
      // This can probably happen if we start in a CORE AS!
      // E.g. only coreSegments or only downSegments or core+down
      throw new UnsupportedOperationException();
    }
    writeInfoField(raw, segUp, true);
    if (hopCount1 > 0) {
      writeInfoField(raw, segCore, false);
    }
    if (hopCount2 > 0) {
      writeInfoField(raw, segDown, false);
    }

    // hop fields
    writeHopFields(raw, segUp, true);
    if (hopCount1 > 0) {
      writeHopFields(raw, segCore, false);
    }
    if (hopCount2 > 0) {
      writeHopFields(raw, segDown, false);
    }

    raw.flip();
    path.setRaw(ByteString.copyFrom(raw));

    // TODO implement this
    //    path.setInterface(Daemon.Interface.newBuilder().setAddress().build());
    //    path.addInterfaces(Daemon.PathInterface.newBuilder().setId().setIsdAs().build());
    //    segUp.getSegmentInfo();
    //    path.setExpiration();
    //    path.setMtu();
    //    path.setLatency();

    return path.build();
  }

  private static void writeInfoField(ByteBuffer raw, Seg.PathSegment pathSegment, boolean reversed)
      throws InvalidProtocolBufferException {
    Seg.SegmentInformation infoUp = Seg.SegmentInformation.parseFrom(pathSegment.getSegmentInfo());
    int inf0 = ((reversed ? 0 : 1) << 24) | infoUp.getSegmentId();
    raw.putInt(inf0);
    // TODO in the daemon's path, all segments have the same timestamp....
    raw.putInt((int) infoUp.getTimestamp()); // TODO does this work? casting to int?
  }

  private static void writeHopFields(ByteBuffer raw, Seg.PathSegment pathSegment, boolean reversed)
      throws InvalidProtocolBufferException {
    final int n = pathSegment.getAsEntriesCount();
    for (int i = 0; i < n; i++) {
      Seg.ASEntry asEntry = pathSegment.getAsEntriesList().get(reversed ? (n - i - 1) : i);
      // Let's assumed they are all signed // TODO?
      Signed.SignedMessage sm = asEntry.getSigned();
      Signed.HeaderAndBodyInternal habi =
          Signed.HeaderAndBodyInternal.parseFrom(sm.getHeaderAndBody());
      // Signed.Header header = Signed.Header.parseFrom(habi.getHeader());
      // TODO body for signature verification?!?
      Seg.ASEntrySignedBody body = Seg.ASEntrySignedBody.parseFrom(habi.getBody());
      // Seg.HopEntry hopEntry = body.getHopEntry();
      Seg.HopField hopField = body.getHopEntry().getHopField();

      raw.put((byte) 0);
      raw.put((byte) hopField.getExpTime()); // TODO cast to byte,...?
      raw.putShort((short) hopField.getIngress());
      raw.putShort((short) hopField.getEgress());
      ByteString mac = hopField.getMac();
      for (int j = 0; j < 6; j++) {
        raw.put(mac.byteAt(j));
      }
    }
  }

  //    private static class PathSegmentMeta {
  //        final long firstIsdAs;
  //        final long lastIsdAs;
  //        final boolean reversed;
  //        final Seg.PathSegment pathSegment;
  //        final List<Seg.ASEntrySignedBody> signedBodies;
  //        final Seg.SegmentInformation segmentInformation;
  //
  //
  //        static PathSegmentMeta create(long srcIsdAs, Seg.PathSegment protoSegment) throws
  // ScionException {
  //            List<Seg.ASEntrySignedBody> signedBodies = new
  // ArrayList<>(protoSegment.getAsEntriesCount());
  //            for (Seg.ASEntry asEntry : protoSegment.getAsEntriesList()) {
  //                // Let's assumed they are all signed // TODO?
  //                Signed.SignedMessage sm = asEntry.getSigned();
  //                Signed.HeaderAndBodyInternal habi =
  //                        Signed.HeaderAndBodyInternal.parseFrom(sm.getHeaderAndBody());
  //                // Signed.Header header = Signed.Header.parseFrom(habi.getHeader());
  //                // TODO body for signature verification?!?
  //                Seg.ASEntrySignedBody body = Seg.ASEntrySignedBody.parseFrom(habi.getBody());
  //                signedBodies.add(body);
  //            }
  //            if (signedBodies.isEmpty()) {
  //                throw new ScionException("Invalid path found, contains no Hops.");
  //            }
  //
  //            Seg.ASEntrySignedBody firstBody = signedBodies.get(0);
  //            long firstIsdAs = signedBodies.get(0).getIsdAs();
  //            long lastIsdAs = signedBodies.get(signedBodies.size() - 1).getIsdAs();
  //            boolean reversed = firstBody.getIsdAs() != srcIsdAs;
  //            if (firstIsdAs != srcIsdAs && lastIsdAs != srcIsdAs) {
  //                throw new ScionException("Invalid path found, does not have required ISD/AS.");
  //            }
  //
  //
  //
  //            Seg.ASEntry asEntryFirst = protoSegment.getAsEntries(0);
  //            Seg.ASEntry asEntryLast = protoSegment.getAsEntries(protoSegment.getAsEntriesCount()
  // - 1);
  //            if (!asEntryFirst.hasSigned() || !asEntryLast.hasSigned()) {
  //                throw new UnsupportedOperationException("Unsigned entries not (yet) supported");
  // // TODO
  //            }
  //            Seg.ASEntrySignedBody bodyFirst = getBody(asEntryFirst.getSigned());
  //            Seg.ASEntrySignedBody bodyLast = getBody(asEntryFirst.getSigned());
  //
  //
  //            PathSegmentMeta psm = new PathSegmentMeta();
  //        }
  //
  //        private PathSegmentMeta(long firstIsdAs, long lastIsdAs, boolean reversed,
  // Seg.PathSegment pathSegment, Seg.ASEntrySignedBody signedBody, Seg.SegmentInformation
  // segmentInformation) {
  //            this.firstIsdAs = firstIsdAs;
  //            this.lastIsdAs = lastIsdAs;
  //            this.reversed = reversed;
  //            this.pathSegment = pathSegment;
  //            this.signedBodies = signedBody;
  //            this.segmentInformation = segmentInformation;
  //        }
  //    }

  private static MultiMap<Long, Seg.PathSegment> createSegmentsMap(
      Set<Map.Entry<Integer, Seg.SegmentsResponse.Segments>> segments, long knownIsdAs)
      throws ScionException {
    MultiMap<Long, Seg.PathSegment> map = new MultiMap<>();
    for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> seg : segments) {
      for (Seg.PathSegment pathSeg : seg.getValue().getSegmentsList()) {
        long unknownIsdAs = getOtherIsdAs(knownIsdAs, pathSeg);
        map.put(unknownIsdAs, pathSeg);
      }
    }
    return map;
  }

  private static long getOtherIsdAs(long isdAs, Seg.PathSegment seg) throws ScionException {
    // Either the first or the last ISD/AS is the one we are looking for.
    if (seg.getAsEntriesCount() < 2) {
      throw new ScionException("Segment has < 2 hops.");
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
  private static long[] getEndingIAs(Seg.PathSegment seg) throws ScionException {
    Seg.ASEntry asEntryFirst = seg.getAsEntries(0);
    Seg.ASEntry asEntryLast = seg.getAsEntries(seg.getAsEntriesCount() - 1);
    if (!asEntryFirst.hasSigned() || !asEntryLast.hasSigned()) {
      throw new UnsupportedOperationException("Unsigned entries not (yet) supported"); // TODO
    }
    Seg.ASEntrySignedBody bodyFirst = getBody(asEntryFirst.getSigned());
    Seg.ASEntrySignedBody bodyLast = getBody(asEntryFirst.getSigned());
    return new long[] {bodyFirst.getIsdAs(), bodyLast.getIsdAs()};
  }

  private static Seg.ASEntrySignedBody getBody(Signed.SignedMessage sm) throws ScionException {
    try {
      return Seg.ASEntrySignedBody.parseFrom(sm.getHeaderAndBody());
    } catch (InvalidProtocolBufferException e) {
      throw new ScionException(e);
    }
  }
}
