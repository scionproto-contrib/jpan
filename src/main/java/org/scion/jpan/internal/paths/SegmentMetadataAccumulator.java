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

package org.scion.jpan.internal.paths;

import static org.scion.jpan.PathMetadata.*;

import org.scion.jpan.PathMetadata;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegExtensions;

class SegmentMetadataAccumulator {

  private SegmentMetadataAccumulator() {}

  static void writeStaticInfoMetadata(
      PathMetadata.Builder path, Segments.PathSegment[] pathSegments, Segments.Range[] ranges) {
    // Stitching metadata is not trivial.
    // Some quirks:
    // - The segments contain internal bandwidth & latency metadata. However, they contain
    //   metadata for multiple internal combinations, even for interfaces that are not in the path.
    //   The reason is that we don't know the "other" interface before stitching.
    // - The internal metadata is stored as key value pairs, with the "other" interface as key.
    // - The internal metadata is provided only for UP and DOWN segments. CORE segments do not have
    //   it.

    long prevIsdAs = -1;
    for (int r = 0; r < ranges.length; r++) {
      Segments.Range range = ranges[r];
      for (int pos = range.begin(); pos != range.end(); pos += range.increment()) {
        Seg.ASEntrySignedBody body = pathSegments[r].getAsEntries(pos);

        boolean addIntraInfo;
        if (pathSegments.length == 1) {
          addIntraInfo = pos != range.first() && pos != range.last();
        } else if (pathSegments.length == 2) {
          if (r == 0) {
            addIntraInfo = pos != range.first();
          } else {
            // r == 1
            addIntraInfo = pos != range.first() && pos != range.last();
          }
        } else {
          // 3 segments
          if (r == 0) {
            addIntraInfo = pos != range.first();
          } else if (r == 1) {
            addIntraInfo = pos != range.first() && pos != range.last();
          } else {
            // r == 2
            addIntraInfo = pos != range.last();
          }
        }

        boolean addIsdAs = prevIsdAs != body.getIsdAs();
        prevIsdAs = body.getIsdAs();
        writeStaticInfoMetadata(path, body, range, addIsdAs, addIntraInfo);
      }
    }
  }

  private static void writeStaticInfoMetadata(
      PathMetadata.Builder path,
      Seg.ASEntrySignedBody body,
      Segments.Range range,
      boolean addIsdAs,
      boolean addIntraInfo) {
    SegExtensions.PathSegmentExtensions ext = body.getExtensions();
    boolean reversed = range.isReversed();
    Seg.HopField hopField = body.getHopEntry().getHopField();
    long id1 = hopField.getEgress();
    long id2 = hopField.getIngress();
    if (!ext.hasStaticInfo()) {
      if (id1 != 0) {
        path.addLatency(toMillis(null));
        path.addBandwidth(0);
        path.addGeo(toGeo(null));
      }
      if (id2 != 0) {
        path.addLinkType(LinkType.UNSPECIFIED);
        path.addGeo(toGeo(null));
      }
      if (addIntraInfo) {
        path.addLatency(toMillis(null));
        path.addBandwidth(0);
        path.addInternalHops(0);
      }
      if (addIsdAs) {
        path.addNotes("");
      }
      return;
    }

    SegExtensions.StaticInfoExtension sie = ext.getStaticInfo();
    // Don't add intra for first hop.
    if (reversed) {
      if (id1 != 0) {
        if (sie.getLatency().getInterMap().containsKey(id1)) {
          path.addLatency(toMillis(sie.getLatency().getInterMap().get(id1)));
        }
        Long bw = sie.getBandwidth().getInterMap().get(id1);
        path.addBandwidth(bw == null ? 0 : bw);
        path.addGeo(toGeo(sie.getGeoMap().get(id1)));
      }
      if (id2 != 0) {
        path.addGeo(toGeo(sie.getGeoMap().get(id2)));
      }
    }

    if (addIntraInfo) {
      if (!sie.getLatency().getIntraMap().isEmpty()) {
        path.addLatency(toMillis(sie.getLatency().getIntraMap().values().iterator().next()));
      } else {
        path.addLatency(toMillis(null));
      }
      if (!sie.getBandwidth().getIntraMap().isEmpty()) {
        path.addBandwidth(sie.getBandwidth().getIntraMap().values().iterator().next());
      } else {
        path.addBandwidth(0);
      }
      if (!sie.getInternalHopsMap().isEmpty()) {
        path.addInternalHops(sie.getInternalHopsMap().values().iterator().next());
      } else {
        path.addInternalHops(0);
      }
    }

    if (id2 != 0) {
      // path.addLinkType(toLinkType(sie.getLinkTypeMap().getOrDefault(id2, null)));
      if (!sie.getLinkTypeMap().isEmpty()) {
        path.addLinkType(toLinkType(sie.getLinkTypeMap().values().iterator().next()));
      } else {
        path.addLinkType(toLinkType(null));
      }
    }

    if (!reversed) {
      if (id2 != 0) {
        path.addGeo(toGeo(sie.getGeoMap().get(id2)));
      }
      if (id1 != 0) {
        int latency = toMillis(sie.getLatency().getInterMap().getOrDefault(id1, null));
        path.addLatency(latency);
        Long bw = sie.getBandwidth().getInterMap().get(id1);
        path.addBandwidth(bw == null ? 0 : bw);
        path.addGeo(toGeo(sie.getGeoMap().get(id1)));
      }
    }

    if (addIsdAs) {
      path.addNotes(sie.getNote());
    }
  }

  private static int toMillis(Integer micros) {
    if (micros == null) {
      return -1;
    }
    return micros / 1000;
  }

  private static GeoCoordinates toGeo(SegExtensions.GeoCoordinates geo) {
    if (geo == null) {
      return GeoCoordinates.create(0, 0, "");
    }
    return GeoCoordinates.create(geo.getLatitude(), geo.getLongitude(), geo.getAddress());
  }

  private static LinkType toLinkType(SegExtensions.LinkType lt) {
    if (lt == null) {
      return LinkType.UNSPECIFIED;
    }
    switch (lt) {
      case LINK_TYPE_UNSPECIFIED:
        return LinkType.UNSPECIFIED;
      case LINK_TYPE_DIRECT:
        return LinkType.DIRECT;
      case LINK_TYPE_MULTI_HOP:
        return LinkType.MULTI_HOP;
      case LINK_TYPE_OPEN_NET:
        return LinkType.OPEN_NET;
      case UNRECOGNIZED:
      default:
        return LinkType.UNSPECIFIED;
    }
  }
}
