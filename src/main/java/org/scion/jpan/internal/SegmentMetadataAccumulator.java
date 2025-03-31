// Copyright 2025 ETH Zurich
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

import com.google.protobuf.Duration;
import java.util.*;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegExtensions;
import org.scion.jpan.proto.daemon.Daemon;

class SegmentMetadataAccumulator {

  // [start (inclusive), end (exclusive), increment]
  static class Range {
    private final int startIncl;
    private final int endExcl;
    private final int increment;

    Range(int[] range) {
      startIncl = range[0];
      endExcl = range[1];
      increment = range[2];
    }

    int last() {
      return endExcl - increment;
    }

    boolean isReversed() {
      return increment == -1;
    }

    public int first() {
      return startIncl;
    }
  }

  static void writeMetadata(
      Daemon.Path.Builder path, Segments.PathSegment[] pathSegments, int[][] ranges) {
    // Stitching metadata is not trivial.
    // Some quirks:
    // - The segments contain internal bandwidth & latency metadata. However, they contain
    //   metadata for multiple internal combinations, even for interfaces that are not in the path.
    //   The reason is that we don't know the "other" interface before stitching.
    // - The internal metadata is stored as key value pairs, with the "other" interface as key.
    // - The internal metadata is provided only for UP and DOWN segments. CORE segments do not have
    //   it.

    int interfacePos = 0;
    long prevIsdAs = -1; // / TODO remove?
    for (int r = 0; r < ranges.length; r++) {
      Range range = new Range(ranges[r]);
      Segments.PathSegment pathSegment = pathSegments[r];
      for (int pos = range.startIncl; pos != range.endExcl; pos += range.increment) {
        boolean reversed = range.isReversed();
        Seg.ASEntrySignedBody body = pathSegment.getAsEntries(pos);

        // Do this for all except last.
        //        System.out.println(
        //            "wHF: "
        //                + pos
        //                + "   ->  "
        //                + ScionUtil.toStringPath(path.getRaw().toByteArray())
        //                + " "
        //                + ScionUtil.toStringIA(body.getIsdAs())
        //                + "  ext:"
        //                + body.getExtensions().hasStaticInfo()
        //                + "  reversed: "
        //                + reversed); // TODO
        long idX = -1;
        boolean addIsdAs = false;
        if (pos == 0 && reversed && r < ranges.length - 1) {
          // must be UP or CORE
          if (pathSegment.isCore()) {
            // CORE followed by DOWN
          } else {
            // UP followed by CORE or DOWN
            idX = path.getInterfaces(interfacePos).getId();
          }
        } else if (pos == 0 && !reversed && interfacePos > 0) {
          // must be DOWN
          idX = path.getInterfaces(interfacePos - 1).getId();
        } else {
          // any other case: just increment by 2
          interfacePos += 2;
        }

        boolean addIntraInfo;
        if (pathSegments.length == 1) {
          if (pos != range.first() && pos != range.last()) {
            addIntraInfo = true;
          } else {
            addIntraInfo = false;
          }
        } else if (pathSegments.length == 2) {
          if (r == 0) {
            if (pos != range.first()) { // TODO check: pos != begin!
              addIntraInfo = true;
            } else {
              addIntraInfo = false;
            }
          } else {
            // r == 1
            addIntraInfo = pos != range.first() && pos != range.last();
          }
        } else {
          // 3 segments
          if (r == 0) {
            if (pos != range.first()) {
              addIntraInfo = true;
            } else {
              addIntraInfo = false;
            }
          } else if (r == 1) {
            addIntraInfo = pos != range.first() && pos != range.last();
          } else {
            // r == 2
            addIntraInfo = pos != range.last();
          }
        }

        if (prevIsdAs != body.getIsdAs()) {
          addIsdAs = true;
        }
        // TODO remove idx
        prevIsdAs = body.getIsdAs();
        writeMetadata(path, body, range, idX, addIsdAs, addIntraInfo);
      }
    }
  }

  private static void writeMetadata(
      Daemon.Path.Builder path,
      Seg.ASEntrySignedBody body,
      Range range,
      long id3,
      boolean addIsdAs,
      boolean addIntraInfo) {
    SegExtensions.PathSegmentExtensions ext = body.getExtensions();
    boolean reversed = range.isReversed();
    Seg.HopField hopField = body.getHopEntry().getHopField();
    long id1 = hopField.getEgress();
    long id2 = hopField.getIngress();
    boolean addIntra = id3 != 0; // id1 > 0 && id2 > 0;
    if (!ext.hasStaticInfo()) {
      // TODO path.addNotes("");
      // TODO path.addGeo("");
      // TODO path.addHops("");
      if (id1 != 0) {
        path.addLatency(toDuration(null));
        path.addBandwidth(0);
      }
      path.addLatency(toDuration(null));
      path.addBandwidth(0);
      return;
    }
    SegExtensions.StaticInfoExtension sie = ext.getStaticInfo();
    // Don´t add intra for first hop.
    //    System.out.println("writeMetadata: id1=" + id1 + " id2=" + id2 + " " +
    // ScionUtil.toStringIA(body.getIsdAs())); // TODO
    //    if (pos != range[0]) {

    //    System.out.println(
    //            "   lat inter?: " + id1 + "  " + sie.getLatency().getInterMap().get(id1)); // TODO
    //    System.out.println(
    //            "   bw-inter?: " + id1 + "  " + sie.getBandwidth().getInterMap().get(id1)); //
    // TODO
    //    System.out.println(
    //            "   lat inter2?: " + id2 + "  " + sie.getLatency().getInterMap().get(id2)); //
    // TODO
    //    System.out.println(
    //            "   bw-inter2?: " + id2 + "  " + sie.getBandwidth().getInterMap().get(id2)); //
    // TODO
    if (reversed) {
      if (id1 != 0 && sie.getLatency().getInterMap().containsKey(id1)) {
        path.addLatency(toDuration(sie.getLatency().getInterMap().get(id1)));
      }
      if (id1 != 0) {
        Long bw = sie.getBandwidth().getInterMap().get(id1);
        path.addBandwidth(bw == null ? 0 : bw);
      }
    }

    if (addIntraInfo && !sie.getLatency().getIntraMap().isEmpty()) {
      path.addLatency(toDuration(sie.getLatency().getIntraMap().values().iterator().next()));
    }
    if (addIntraInfo && !sie.getBandwidth().getIntraMap().isEmpty()) {
      path.addBandwidth(sie.getBandwidth().getIntraMap().values().iterator().next());
    }
    if (addIntraInfo && !sie.getInternalHopsMap().isEmpty()) {
      path.addInternalHops(sie.getInternalHopsMap().values().iterator().next());
    }

    if (!sie.getLinkTypeMap().isEmpty()) {
      path.addLinkType(toLinkType(sie.getLinkTypeMap().values().iterator().next()));
    }

    if (!reversed) {
      if (id1 != 0 && sie.getLatency().getInterMap().containsKey(id1)) {
        path.addLatency(toDuration(sie.getLatency().getInterMap().get(id1)));
      }
      if (id1 != 0) {
        Long bw = sie.getBandwidth().getInterMap().get(id1);
        path.addBandwidth(bw == null ? 0 : bw);
      }
    }

    //    if (id2 != 0 && sie.getLatency().getIntraMap().containsKey(id2)) {
    //      path.addLatency(toDuration(sie.getLatency().getIntraMap().get(id2)));
    //    }
    //    if (id3 != -1) {
    //      System.out.println("id3 = " + id3); // TODO
    //    }
    //    System.out.print(
    //        "   lat-intra1? " + id1 + " -> " + sie.getLatency().getIntraMap().get(id1)); // TODO
    //    System.out.println(
    //        "   lat-intra2? " + id2 + " -> " + sie.getLatency().getIntraMap().get(id2)); // TODO
    //    if (!sie.getLatency().getIntraMap().isEmpty()) {
    //      System.out.print("   lat-intra: "); // TODO
    //      for (Map.Entry<Long, Integer> o : sie.getLatency().getIntraMap().entrySet()) {
    //        System.out.print(o.getKey() + "->" + o.getValue() + ";   "); // TODO
    //      }
    //      System.out.println(); // TODO
    //    }
    //    System.out.print(
    //        "   bw-intra1? " + id1 + " -> " + sie.getBandwidth().getIntraMap().get(id1)); // TODO
    //    System.out.println(
    //        "   bw-intra2? " + id2 + " -> " + sie.getBandwidth().getIntraMap().get(id2)); // TODO
    //    if (!sie.getBandwidth().getIntraMap().isEmpty()) {
    //      System.out.print("   bw-intra: "); // TODO
    //      for (Map.Entry<Long, Long> o : sie.getBandwidth().getIntraMap().entrySet()) {
    //        System.out.print(o.getKey() + "->" + o.getValue() + ";   "); // TODO
    //      }
    //      System.out.println(); // TODO
    //    }

    //      if (sie.getBandwidth().getIntraMap().containsKey(id2)) {
    //        path.addBandwidth(sie.getBandwidth().getIntraMap().get(id2));
    //      }
    // key: IF id of the "other" interface. TODO what is "other"?
    //            System.out.println("range: " + Arrays.toString(range) + " pos=" + pos + " " +
    //       sie.getInternalHopsMap()); // TODO
    //            for (Map.Entry<Long, Integer> o: sie.getInternalHopsMap().entrySet()) {
    //              System.out.println("   hop:: " + o.getKey() + "  "   + o.getValue()); // TODO
    //            }
    //    if (sie.getInternalHopsMap().containsKey(id2)) {
    //      path.addInternalHops(sie.getInternalHopsMap().get(id2));
    //    }
    if (id1 != 0) {
      path.addGeo(toGeo(sie.getGeoMap().get(id1)));
    }
    //    }

    if (id2 != 0) {
      path.addGeo(toGeo(sie.getGeoMap().get(id2)));
    }

    //    System.out.print("   geo1? " + id1 + " -> " + (sie.getGeoMap().get(id1) != null)); // TODO
    //    System.out.println("   geo2? " + id2 + " -> " + (sie.getGeoMap().get(id2) != null)); //
    // TODO
    //    System.out.print("   hops1? " + id1 + " -> " + (sie.getInternalHopsMap().get(id1))); //
    // TODO
    //    System.out.print("   hops2? " + id2 + " -> " + (sie.getInternalHopsMap().get(id2))); //
    // TODO
    //    for (Map.Entry<Long, Integer> o : sie.getInternalHopsMap().entrySet()) {
    //      System.out.print("   e: " + o.getKey() + "  " + o.getValue()); // TODO
    //    }
    //    System.out.println();

    //    System.out.println("   n1? -> " + sie.getNote()); // TODO
    //    System.out.print("   lt1? " + id1 + " -> " + sie.getLinkTypeMap().get(id1)); // TODO
    //    System.out.println("   lt2? " + id2 + " -> " + sie.getLinkTypeMap().get(id2)); // TODO
    if (addIsdAs) {
      path.addNotes(sie.getNote());
    }
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
}
