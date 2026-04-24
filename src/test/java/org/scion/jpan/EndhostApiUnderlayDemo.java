// Copyright 2026 ETH Zurich
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

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.scion.jpan.proto.endhost.Path;
import org.scion.jpan.proto.endhost.Underlays;
import org.scion.jpan.testutil.MockNetwork2;

/** Small demo that requests and prints segments requested from a control service. */
public class EndhostApiUnderlayDemo {

  private final OkHttpClient httpClient;
  private final String apiAddress;

  private static final String neaETH = "192.168.53.19:48080";

  public static void main(String[] args) throws ScionException {
    //    EndhostApiUnderlayDemo demo = new EndhostApiUnderlayDemo(neaETH);
    //    demo.getSegments();

    //        try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B,
    // "ASff00_0_112")) {
    //          EndhostApiUnderlayDemo demo2 = new EndhostApiUnderlayDemo("127.0.0.1:48080");
    //          demo2.getSegments();
    //        }
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.DEFAULT, "ASff00_0_112")) {
      EndhostApiUnderlayDemo demo2 = new EndhostApiUnderlayDemo("127.0.0.1:48080");
      demo2.getSegments();
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

  private Underlays.ListUnderlaysResponse sendRequest2() throws IOException {
    Underlays.ListUnderlaysRequest protoRequest =
        Underlays.ListUnderlaysRequest.newBuilder().build();
    RequestBody requestBody = RequestBody.create(protoRequest.toByteArray());

    Request request =
        new Request.Builder()
            .url("http://" + apiAddress + "/scion.endhost.v1.UnderlayService/ListUnderlays")
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
      return Underlays.ListUnderlaysResponse.newBuilder()
          .mergeFrom(response.body().bytes())
          .build();
    }
  }

  public EndhostApiUnderlayDemo(String apiAddress) {
    httpClient = new OkHttpClient();
    this.apiAddress = apiAddress;
  }

  private void getSegments() throws ScionException {
    System.out.println("Requesting underlay information from: " + apiAddress);

    Underlays.ListUnderlaysResponse response;
    try {
      response = sendRequest2();
    } catch (IOException e) {
      throw new ScionException("Error while getting Segment info: " + e.getMessage(), e);
    }
    print(response);
  }

  private static void print(Underlays.ListUnderlaysResponse r) {
    System.out.println("Response SNAP / UDP = " + r.hasSnap() + " / " + r.hasUdp());
    System.out.println("SNAP:");
    for (Underlays.Snap s : r.getSnap().getSnapsList()) {
      System.out.println("  SNAP address: " + s.getAddress());
    }
    System.out.println("UDP:");
    for (Underlays.Router u : r.getUdp().getRoutersList()) {
      System.out.println(
          "  Router: " + ScionUtil.toStringIA(u.getIsdAs()) + " / " + u.getAddress());
      for (Integer ifId : u.getInterfacesList()) {
        System.out.println("    Interface: " + ifId);
      }
    }
  }
}
