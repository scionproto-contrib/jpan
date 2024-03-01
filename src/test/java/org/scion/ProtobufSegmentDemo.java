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

package org.scion;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.*;
import org.scion.demo.DemoConstants;
import org.scion.proto.control_plane.Seg;
import org.scion.proto.control_plane.SegExtensions;
import org.scion.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.proto.control_plane.experimental.SegDetachedExtensions;
import org.scion.proto.crypto.Signed;

/** Small demo that requests and prints segments requested from a control service. */
public class ProtobufSegmentDemo {

  private final SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub;
  private final ManagedChannel channel;

  public static void main(String[] args) throws ScionException {
    // ProtobufSegmentDemo demo = new ProtobufSegmentDemo(csETH);
    // demo.getSegments(iaETH, iaETH_CORE);
    // demo.getSegments(toWildcard(iaETH), toWildcard(iaAnapayaHK));
    ProtobufSegmentDemo demo = new ProtobufSegmentDemo(DemoConstants.csAddr120_minimal);
    // demo.getSegments(ia110, ia121);
    demo.getSegments(DemoConstants.ia120, DemoConstants.ia210);
    // demo.getSegments(toWildcard(ia121), ia121);
    // demo.getSegments(toWildcard(ia120), toWildcard(ia210));
  }

  private static long toWildcard(long ia) {
    return (ia >>> 48) << 48;
  }

  public ProtobufSegmentDemo(String csAddress) {
    channel = Grpc.newChannelBuilder(csAddress, InsecureChannelCredentials.create()).build();
    segmentStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
  }

  private void getSegments(long srcIsdAs, long dstIsdAs) throws ScionException {
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

    try {
      for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> seg :
          response.getSegmentsMap().entrySet()) {
        System.out.println(
            "SEG: key="
                + Seg.SegmentType.forNumber(seg.getKey())
                + " -> n="
                + seg.getValue().getSegmentsCount());
        for (Seg.PathSegment pathSegment : seg.getValue().getSegmentsList()) {
          System.out.println("  PathSeg: size=" + pathSegment.getSegmentInfo().size());
          Seg.SegmentInformation segInf =
              Seg.SegmentInformation.parseFrom(pathSegment.getSegmentInfo());
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
              System.out.println(
                  "      HopEntry: " + he.hasHopField() + " mtu=" + he.getIngressMtu());

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
    } catch (InvalidProtocolBufferException e) {
      throw new ScionException(e);
    }
  }
}
