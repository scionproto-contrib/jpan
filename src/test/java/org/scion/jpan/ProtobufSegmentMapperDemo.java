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

package org.scion.jpan;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegExtensions;
import org.scion.jpan.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.jpan.proto.control_plane.experimental.SegDetachedExtensions;
import org.scion.jpan.proto.crypto.Signed;

/**
 * Small demo that requests and prints segments requested from a control service. The segments are
 * analyzed to create a topology file.
 */
public class ProtobufSegmentMapperDemo {

  private final SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub;
  private final ManagedChannel channel;

  public static void main(String[] args) throws IOException {
    //    // ProtobufSegmentDemo demo = new ProtobufSegmentDemo(csETH);
    //    // demo.getSegments(iaETH, iaETH_CORE);
    //    // demo.getSegments(toWildcard(iaETH), toWildcard(iaAnapayaHK));
    //    ProtobufSegmentDemo demo = new ProtobufSegmentDemo(DemoConstants.csAddr110_minimal);
    //    // demo.getSegments(ia110, ia121);
    //    demo.getSegments(DemoConstants.ia110, DemoConstants.ia1111);
    //    // demo.getSegments(toWildcard(ia121), ia121);
    //    // demo.getSegments(toWildcard(ia120), toWildcard(ia210));

        ProtobufSegmentMapperDemo demoLab = new ProtobufSegmentMapperDemo("[fd00:f00d:cafe::7f00:14]:31000");
        demoLab.getSegments(ScionUtil.parseIA("1-ff00:0:110"), ScionUtil.parseIA("2-ff00:0:210"));

    // ProtobufSegmentMapperDemo demoLab = new ProtobufSegmentMapperDemo("127.0.0.71:31000");
    // demoLab.getSegments(ScionUtil.parseIA("1-ff00:0:1001"), ScionUtil.parseIA("1-ff00:0:1007"));
  }

  public ProtobufSegmentMapperDemo(String csAddress) {
    channel = Grpc.newChannelBuilder(csAddress, InsecureChannelCredentials.create()).build();
    segmentStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
  }

  private void getSegments(long srcIsdAs, long dstIsdAs) throws IOException {
    // LOG.info("*** GetASInfo ***");
    System.out.println(
        "Requesting segments: "
            + ScionUtil.toStringIA(srcIsdAs)
            + " -> "
            + ScionUtil.toStringIA(dstIsdAs));
    if (srcIsdAs == dstIsdAs) {
      return;
    }
    Seg.SegmentsRequest request =
        Seg.SegmentsRequest.newBuilder().setSrcIsdAs(srcIsdAs).setDstIsdAs(dstIsdAs).build();
    Seg.SegmentsResponse response;
    try {
      response = segmentStub.segments(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException("Error while getting Segment info: " + e.getMessage(), e);
    }
    print(response);
  }

  private static void print(Seg.SegmentsResponse response) throws IOException {
    analyze(response);
    //    try {
    //      for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> seg :
    //          response.getSegmentsMap().entrySet()) {
    //        System.out.println(
    //            "SEG: key="
    //                + Seg.SegmentType.forNumber(seg.getKey())
    //                + " -> n="
    //                + seg.getValue().getSegmentsCount());
    //        for (Seg.PathSegment pathSegment : seg.getValue().getSegmentsList()) {
    //          print(pathSegment);
    //        }
    //      }
    //    } catch (InvalidProtocolBufferException e) {
    //      throw new ScionException(e);
    //    }
  }

  private static class AsInfo {
    final long isdAs;
    final Map<Long, AsLink> links = new HashMap<>();

    AsInfo(long isdAs) {
      this.isdAs = isdAs;
    }

    public void addLink(long id, AsLink link) {
      links.put(id, link);
    }
  }

  private static class AsLink {
    private final long as0;
    private final long as1;
    private final long id0;
    private final long id1;

    public AsLink(long isdAs, long nextIsdAs, long id0, long id1) {
      this.as0 = isdAs;
      this.as1 = nextIsdAs;
      this.id0 = id0;
      this.id1 = id1;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof AsLink)) return false;
      AsLink asLink = (AsLink) o;
      return as0 == asLink.as0 && as1 == asLink.as1 && id0 == asLink.id0 && id1 == asLink.id1;
    }

    @Override
    public int hashCode() {
      return Objects.hash(as0, as1, id0, id1);
    }
  }

  private static void analyze(Seg.SegmentsResponse response) throws IOException {
    List<PathSegment> segments = getPathSegments(response);

    Map<Long, AsInfo> asMap = new HashMap<>();
    Set<AsLink> asLinks = new HashSet<>();
    for (PathSegment s : segments) {
      AsInfo asPrev = null;
      long idPrev = -1;
      for (int i = 0; i < s.getAsEntriesCount(); i++) {
        Seg.ASEntrySignedBody body = s.getAsEntries(i);
        Seg.HopEntry hopEntry = body.getHopEntry();
        Seg.HopField hopField = hopEntry.getHopField();

        long isdAs = body.getIsdAs();
        long nextIsdAs = body.getNextIsdAs();
        AsInfo as0 = asMap.computeIfAbsent(isdAs, l -> l > 0 ? new AsInfo(l) : null);
        AsInfo as1 = asMap.computeIfAbsent(nextIsdAs, l -> l > 0 ? new AsInfo(l) : null);
        long id0 = hopField.getEgress();
        long id1 = hopField.getIngress();

        if (i >= 1) {
          // This is a bit weird, but it works.
          AsLink link = new AsLink(asPrev.isdAs, isdAs, idPrev, id1);
          asLinks.add(link);
          //          as0.addLink(id0, link);
          //          as1.addLink(id1, link);
        }
        asPrev = as0;
        idPrev = id0;
      }
    }

    write(asMap, asLinks);
  }

  private static void write(Map<Long, AsInfo> asMap, Set<AsLink> asLinks) throws IOException {
    // write
    FileWriter writer = new FileWriter("mytopo.topo");

    String NL = System.lineSeparator();
    writer.append("--- # My Topology").append(NL);
    writer.append("ASes:").append(NL);
    for (AsInfo as : asMap.values()) {
      writer.append("  \"").append(ScionUtil.toStringIA(as.isdAs)).append("\":").append(NL);
      writer.append("    core: true").append(NL);
      writer.append("    voting: true").append(NL);
      writer.append("    authoritative: true").append(NL);
      writer.append("    issuing: true").append(NL);
    }
    writer.append("links:").append(NL);
    //   - {a: "1-ff00:0:1001#21", b: "1-ff00:0:1002#11",  linkAtoB: CORE}
    for (AsLink l : asLinks) {
      writer
          .append("  - {a: \"")
          .append(ScionUtil.toStringIA(l.as0))
          .append("#")
          .append(String.valueOf(l.id0));
      writer
          .append("\", b: \"")
          .append(ScionUtil.toStringIA(l.as1))
          .append("#")
          .append(String.valueOf(l.id1));
      writer.append("\",  linkAtoB: CORE}").append(NL);
    }

    writer.flush();
    writer.close();
  }

  private static List<PathSegment> getPathSegments(Seg.SegmentsResponse response) {
    List<PathSegment> pathSegments = new ArrayList<>();
    for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> seg :
        response.getSegmentsMap().entrySet()) {
      SegmentType type = SegmentType.from(Seg.SegmentType.forNumber(seg.getKey()));
      seg.getValue()
          .getSegmentsList()
          .forEach(path -> pathSegments.add(new PathSegment(path, type)));
      System.out.println(
          "Segments found of type " + type.name() + ": " + seg.getValue().getSegmentsCount());
    }
    return pathSegments;
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
                  .map(ProtobufSegmentMapperDemo::getBody)
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

    public ByteString getSegmentInfo() {
      return segment.getSegmentInfo();
    }

    public boolean isCore() {
      return type == SegmentType.CORE;
    }
  }

  public static void print(Seg.PathSegment pathSegment) throws InvalidProtocolBufferException {
    System.out.println("  PathSeg: size=" + pathSegment.getSegmentInfo().size());
    Seg.SegmentInformation segInf = Seg.SegmentInformation.parseFrom(pathSegment.getSegmentInfo());
    System.out.println(
        "    SegInfo:  ts="
            + Instant.ofEpochSecond(segInf.getTimestamp())
            + "  id="
            + segInf.getSegmentId());
    for (Seg.ASEntry asEntry : pathSegment.getAsEntriesList()) {
      if (asEntry.hasSigned()) {
        Signed.SignedMessage sm = asEntry.getSigned();
        System.out.println(
            "    AS: signed="
                + sm.getHeaderAndBody().size()
                + "   signature size="
                + sm.getSignature().size());
        // System.out.println(
        //     "    Header/Body=" + Arrays.toString(sm.getHeaderAndBody().toByteArray()));
        // System.out.println(
        //     "    Signature  =" + Arrays.toString(sm.getSignature().toByteArray()));

        Signed.HeaderAndBodyInternal habi =
            Signed.HeaderAndBodyInternal.parseFrom(sm.getHeaderAndBody());
        //              System.out.println(
        //                  "      habi: " + habi.getHeader().size() + " " +
        // habi.getBody().size());
        Signed.Header header = Signed.Header.parseFrom(habi.getHeader());
        // TODO body for signature verification?!?
        Timestamp ts = header.getTimestamp();
        System.out.println(
            "    AS header: "
                + header.getSignatureAlgorithm()
                + "  time="
                + Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos())
                + "  meta="
                + header.getMetadata().size()
                + "  data="
                + header.getAssociatedDataLength());

        Seg.ASEntrySignedBody body = Seg.ASEntrySignedBody.parseFrom(habi.getBody());
        System.out.println(
            "    AS Body: IA="
                + ScionUtil.toStringIA(body.getIsdAs())
                + " nextIA="
                + ScionUtil.toStringIA(body.getNextIsdAs())
                //                      + " nPeers="
                //                      + body.getPeerEntriesCount()
                + "  mtu="
                + body.getMtu());
        Seg.HopEntry he = body.getHopEntry();
        System.out.println("      HopEntry: " + he.hasHopField() + " mtu=" + he.getIngressMtu());

        if (he.hasHopField()) {
          Seg.HopField hf = he.getHopField();
          System.out.println(
              "        HopField: exp="
                  + hf.getExpTime()
                  + " ingress="
                  + hf.getIngress()
                  + " egress="
                  + hf.getEgress());
        }
        if (body.hasExtensions()) {
          SegExtensions.PathSegmentExtensions pse = body.getExtensions();
          if (pse.hasStaticInfo()) {
            SegExtensions.StaticInfoExtension sie = pse.getStaticInfo();
            System.out.println(
                "    Static: latencies="
                    + sie.getLatency().getIntraCount()
                    + "/"
                    + sie.getLatency().getInterCount()
                    + "  bandwidth="
                    + sie.getBandwidth().getIntraCount()
                    + "/"
                    + sie.getBandwidth().getInterCount()
                    + "  geo="
                    + sie.getGeoCount()
                    + "  interfaces="
                    + sie.getLinkTypeCount()
                    + "  note="
                    + sie.getNote());
          }
        }
      }
      if (asEntry.hasUnsigned()) {
        SegExtensions.PathSegmentUnsignedExtensions psue = asEntry.getUnsigned();
        System.out.println("    AS: hasEpic=" + psue.hasEpic());
        if (psue.hasEpic()) {
          SegDetachedExtensions.EPICDetachedExtension epic = psue.getEpic();
          System.out.println("      EPIC: " + epic.getAuthHopEntry().size() + "    ...");
        }
      }
    }
  }
}
