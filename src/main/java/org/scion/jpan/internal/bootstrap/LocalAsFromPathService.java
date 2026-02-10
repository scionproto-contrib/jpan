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

package org.scion.jpan.internal.bootstrap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.scion.jpan.proto.endhost.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** Get topology info from a path service. */
public class LocalAsFromPathService {

  private static final Logger LOG = LoggerFactory.getLogger(LocalAsFromPathService.class.getName());

  public static LocalAS create(String pathService, TrcStore trcStore) {
    //    Daemon.ASResponse as = readASInfo(daemonService);
    //    this.localIsdAs = as.getIsdAs();
    //    this.localMtu = as.getMtu();
    //    this.isCoreAs = as.getCore();
    //    this.portRange = readLocalPortRange(daemonService);
    //    this.borderRouters.addAll(readBorderRouterAddresses(daemonService));
    return null;
  }

//  private void query() {
//    OkHttpClient httpClient = new OkHttpClient();
//    String apiAddress;
//
//
//
//    Path.ListSegmentsRequest protoRequest =
//            Path.ListSegmentsRequest.newBuilder().setSrcIsdAs(srcIA).setDstIsdAs(dstIA).build();
//    RequestBody requestBody = RequestBody.create(protoRequest.toByteArray());
//
//    Request request =
//            new Request.Builder()
//                    .url("http://" + apiAddress + "/scion.endhost.v1.PathService/ListPaths")
//                    .addHeader("Content-type", "application/proto")
//                    //            .addHeader("User-Agent", "OkHttp Bot")
//                    .post(requestBody)
//                    .build();
//
//    System.out.println("Sending request: " + request);
//    System.out.println("             to: " + apiAddress);
//    try (Response response = httpClient.newCall(request).execute()) {
//      //      String bodyStr = response.body().string();
//      //      System.out.println("Client received len: " + bodyStr.length());
//      //      System.out.println("Client received msg: " + bodyStr);
//      System.out.println("Client received len: " + response.message().length());
//      System.out.println("Client received msg: " + response.message());
//      System.out.println("Client received str: " + response);
//      if (!response.isSuccessful()) {
//        throw new IOException("Unexpected code " + response);
//      }
//      return Path.ListSegmentsResponse.newBuilder().mergeFrom(response.body().bytes()).build();
//    }
//  }
//
//  private void queryPath() {
//    OkHttpClient httpClient = new OkHttpClient();
//    String apiAddress;
//
//
//
//    Path.ListSegmentsRequest protoRequest =
//            Path.ListSegmentsRequest.newBuilder().setSrcIsdAs(srcIA).setDstIsdAs(dstIA).build();
//    RequestBody requestBody = RequestBody.create(protoRequest.toByteArray());
//
//    Request request =
//            new Request.Builder()
//                    .url("http://" + apiAddress + "/scion.endhost.v1.PathService/ListPaths")
//                    .addHeader("Content-type", "application/proto")
//                    //            .addHeader("User-Agent", "OkHttp Bot")
//                    .post(requestBody)
//                    .build();
//
//    System.out.println("Sending request: " + request);
//    System.out.println("             to: " + apiAddress);
//    try (Response response = httpClient.newCall(request).execute()) {
//      //      String bodyStr = response.body().string();
//      //      System.out.println("Client received len: " + bodyStr.length());
//      //      System.out.println("Client received msg: " + bodyStr);
//      System.out.println("Client received len: " + response.message().length());
//      System.out.println("Client received msg: " + response.message());
//      System.out.println("Client received str: " + response);
//      if (!response.isSuccessful()) {
//        throw new IOException("Unexpected code " + response);
//      }
//      return Path.ListSegmentsResponse.newBuilder().mergeFrom(response.body().bytes()).build();
//    }
//  }
}
