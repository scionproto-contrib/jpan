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

import static org.scion.jpan.demo.DemoConstants.*;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegExtensions;
import org.scion.jpan.proto.control_plane.experimental.SegDetachedExtensions;
import org.scion.jpan.proto.crypto.Signed;
import org.scion.jpan.proto.endhost.Path;
import org.scion.jpan.testutil.MockNetwork2;

/** Small demo that requests and prints segments requested from a control service. */
public class EndhostApiPathDemo {

  private final OkHttpClient httpClient;
  private final String apiAddress;

  private static final String neaETH = "192.168.53.19:48080";

  public static void main(String[] args) throws ScionException {
    //    EndhostApiPathDemo demo = new EndhostApiPathDemo(neaETH);
    //    demo.getSegments(iaETH, iaETH_CORE);

    // response.toString():
    // Response{protocol=http/1.1, code=200, message=OK,
    // url=http://192.168.53.19:48080/scion.endhost.v1.PathService/ListPaths}
    // Mock:
    // Response{protocol=http/1.1, code=200, message=OK,
    // url=http://127.0.0.1:48080/scion.endhost.v1.PathService/ListPaths}

    // demo.getSegments(ScionUtil.toWildcard(iaETH), ScionUtil.toWildcard(iaAnapayaHK));
    // EndhostApiPathDemo demo = new EndhostApiPathDemo(csAddr110_minimal);
    //    EndhostApiPathDemo demo = new EndhostApiPathDemo();
    //    demo.getSegments(ia220, ScionUtil.parseIA("2-ff00:0:222"));
    // demo.getSegments(DemoConstants.ia110, DemoConstants.ia1111);
    // demo.getSegments(toWildcard(ia121), ia121);
    // demo.getSegments(toWildcard(ia120), toWildcard(ia210));
    //    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
    //      EndhostApiPathDemo demo2 = new EndhostApiPathDemo("127.0.0.1:48080");
    //      demo2.getSegments(ia112, ia110);
    //    }
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.DEFAULT, "ASff00_0_112")) {
      EndhostApiPathDemo demo2 = new EndhostApiPathDemo("127.0.0.1:48080");
      demo2.getSegments(ia112, ia221);
    }
  }

  private Path.ListSegmentsResponse sendRequest(long srcIA, long dstIA) throws IOException {
    Path.ListSegmentsRequest protoRequest =
        Path.ListSegmentsRequest.newBuilder().setSrcIsdAs(srcIA).setDstIsdAs(dstIA).build();

    final String charset = "UTF-8";
    // Create the connection
    URI uri = URI.create("http://" + apiAddress + "/scion.endhost.v1.PathService/ListPaths");
    HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
    connection.setDoOutput(true);
    connection.setRequestProperty("Accept-Charset", charset);
    connection.setRequestProperty("Content-type", "application/proto");

    // Write to the connection
    System.out.println("Sending request: " + connection);
    System.out.println("             to: " + apiAddress);
    DataOutputStream output = new DataOutputStream(connection.getOutputStream());
    output.write(protoRequest.toByteArray());
    output.close();

    // Check the error stream first, if this is null then there have been no issues with the request
    InputStream inputStream = connection.getErrorStream();
    if (inputStream == null) {
      inputStream = connection.getInputStream();
    }

    // Read everything from our stream
    BufferedReader responseReader = new BufferedReader(new InputStreamReader(inputStream, charset));

    char[] ca = new char[100_000];
    int caLen = responseReader.read(ca);
    byte[] ba = new byte[caLen];
    for (int i = 0; i < caLen; i++) {
      // Character c = ca[i];
      // Integer.
      ba[i] = (byte) ca[i];
    }

    //    String inputLine;
    //    StringBuilder responseB = new StringBuilder();
    //    while ((inputLine = responseReader.readLine()) != null) {
    //      responseB.append(inputLine).append("\n");
    //    }
    responseReader.close();

    //    String response = responseB.toString();
    String response = new String(ba);
    System.out.println("Client received len: " + response.length());
    System.out.println("Client received msg: " + response);
    System.out.println("Client received str: " + response);
    //      if (!response.isSuccessful()) {
    //        throw new IOException("Unexpected code " + response);
    //      }
    return Path.ListSegmentsResponse.newBuilder().mergeFrom(ba).build();
  }

  private Path.ListSegmentsResponse sendRequest2(long srcIA, long dstIA) throws IOException {
    Path.ListSegmentsRequest protoRequest =
        Path.ListSegmentsRequest.newBuilder().setSrcIsdAs(srcIA).setDstIsdAs(dstIA).build();
    RequestBody requestBody = RequestBody.create(protoRequest.toByteArray());

    Request request =
        new Request.Builder()
            .url("http://" + apiAddress + "/scion.endhost.v1.PathService/ListPaths")
            .addHeader("Content-type", "application/proto")
            //            .addHeader("User-Agent", "OkHttp Bot")
            .post(requestBody)
            .build();

    System.out.println("Sending request: " + request);
    System.out.println("             to: " + apiAddress);
    try (Response response = httpClient.newCall(request).execute()) {
      //      String bodyStr = response.body().string();
      //      System.out.println("Client received len: " + bodyStr.length());
      //      System.out.println("Client received msg: " + bodyStr);
      System.out.println("Client received len: " + response.message().length());
      System.out.println("Client received msg: " + response.message());
      System.out.println("Client received str: " + response);
      if (!response.isSuccessful()) {
        throw new IOException("Unexpected code " + response);
      }
      return Path.ListSegmentsResponse.newBuilder().mergeFrom(response.body().bytes()).build();
    }
  }

  public EndhostApiPathDemo(String apiAddress) {
    httpClient = new OkHttpClient();
    this.apiAddress = apiAddress;
  }

  private void getSegments(long srcIsdAs, long dstIsdAs) throws ScionException {
    System.out.println(
        "Requesting segments: "
            + ScionUtil.toStringIA(srcIsdAs)
            + " -> "
            + ScionUtil.toStringIA(dstIsdAs));
    if (srcIsdAs == dstIsdAs) {
      return;
    }

    Path.ListSegmentsResponse response;
    try {
      response = sendRequest2(srcIsdAs, dstIsdAs);
    } catch (IOException e) {
      throw new ScionException("Error while getting Segment info: " + e.getMessage(), e);
    }
    print(response);
  }

  private static void print(Path.ListSegmentsResponse r) throws ScionException {
    System.out.println(
        "Response count(UP/CORE/DOWN) = "
            + r.getUpSegmentsList().size()
            + " / "
            + r.getCoreSegmentsList().size()
            + " / "
            + r.getDownSegmentsList().size());
    System.out.println("        nextPageToken=" + r.getNextPageToken());
    print("UP", r.getUpSegmentsList());
    print("CORE", r.getCoreSegmentsList());
    print("DOWN", r.getDownSegmentsList());
  }

  private static void print(String label, List<Seg.PathSegment> list) throws ScionException {
    try {
      System.out.println(label + ":");
      for (Seg.PathSegment pathSegment : list) {
        print(pathSegment);
      }
    } catch (InvalidProtocolBufferException e) {
      throw new ScionException(e);
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
          SegExtensions.PathSegmentExtensions ext = body.getExtensions();
          System.out.println(
              "      Extensions: "
                  + ext.hasStaticInfo()
                  + "/"
                  + ext.hasHiddenPath()
                  + "/"
                  + ext.hasDigests());
          if (ext.hasStaticInfo()) {
            print("        ", ext.getStaticInfo());
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

  private static void print(String prefix, SegExtensions.StaticInfoExtension sie) {
    System.out.println(
        prefix
            + "Static: latencies="
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
            + "  note='"
            + sie.getNote()
            + "'");
    for (Map.Entry<Long, Integer> e : sie.getLatency().getIntraMap().entrySet()) {
      System.out.println(
          prefix + "  latency intra: " + e.getKey() + " -> " + e.getValue() / 1000. + " ms");
    }
    for (Map.Entry<Long, Integer> e : sie.getLatency().getInterMap().entrySet()) {
      System.out.println(
          prefix + "  latency inter: " + e.getKey() + " -> " + e.getValue() / 1000. + " ms");
    }
    for (Map.Entry<Long, Long> e : sie.getBandwidth().getIntraMap().entrySet()) {
      System.out.println(prefix + "  bw intra: " + e.getKey() + " -> " + e.getValue());
    }
    for (Map.Entry<Long, Long> e : sie.getBandwidth().getInterMap().entrySet()) {
      System.out.println(prefix + "  bw inter: " + e.getKey() + " -> " + e.getValue());
    }
    for (Map.Entry<Long, SegExtensions.GeoCoordinates> e : sie.getGeoMap().entrySet()) {
      String pos = "lon: " + e.getValue().getLongitude();
      pos += "; lat: " + e.getValue().getLatitude();
      String addr = e.getValue().getAddress().replace("\n", ", ");
      System.out.println(prefix + "  geo: " + e.getKey() + " -> " + pos + "; addr: " + addr);
    }
    for (Map.Entry<Long, SegExtensions.LinkType> e : sie.getLinkTypeMap().entrySet()) {
      System.out.println(prefix + "  link types: " + e.getKey() + " -> " + e.getValue());
    }
    // Notes
    System.out.println(prefix + "  note: " + sie.getNote());
    for (Map.Entry<Long, Integer> e : sie.getInternalHopsMap().entrySet()) {
      System.out.println(prefix + "  internal hops: " + e.getKey() + " -> " + e.getValue());
    }
  }
}
