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

package org.scion.jpan.internal.bootstrap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.internal.util.HttpEndpoint;
import org.scion.jpan.proto.endhost.discovery.DiscoveryService;

/**
 * Client for the global endhost-API discovery service (e.g. {@code
 * https://discovery.scion.xyz.net}). This is a directory-of-directories lookup: the addresses it
 * returns are themselves endhost APIs exposing {@code
 * scion.endhost.v1.UnderlayService/ListUnderlays} (see {@link LocalAsFromPathService}), not
 * underlay information directly.
 */
public final class EndhostApiDiscoveryClient {

  private static final String SERVICE_PATH = "/endhost.discovery.v1.EndhostApiDiscoveryService";
  private static final String GET_ENDHOST_APIS_PATH = "/GetEndhostApis";

  private EndhostApiDiscoveryClient() {}

  /**
   * @param discoveryEndpoint base URL of the discovery service, e.g. {@code
   *     https://discovery.scion.anapaya.net}
   * @return endhost API addresses to try, in priority order (groups are flattened, preserving group
   *     and within-group order)
   */
  public static List<String> discoverEndhostApis(String discoveryEndpoint) {
    String baseUrl = HttpEndpoint.normalizeBaseUrl(discoveryEndpoint, "https");
    OkHttpClient httpClient = new OkHttpClient();
    RequestBody requestBody =
        RequestBody.create(
            DiscoveryService.RpcGetEndhostApisRequest.newBuilder().build().toByteArray());
    Request request =
        new Request.Builder()
            .url(baseUrl + SERVICE_PATH + GET_ENDHOST_APIS_PATH)
            .addHeader("Content-type", "application/proto")
            .post(requestBody)
            .build();
    try (Response response = httpClient.newCall(request).execute()) {
      ResponseBody body = response.body();
      if (!response.isSuccessful() || body == null) {
        throw new IOException("Unexpected code " + response.code() + ": " + response.message());
      }
      DiscoveryService.RpcGetEndhostApisResponse parsed =
          DiscoveryService.RpcGetEndhostApisResponse.newBuilder().mergeFrom(body.bytes()).build();
      List<String> addresses = new ArrayList<>();
      for (DiscoveryService.RpcEndhostApiGroup group : parsed.getGroupsList()) {
        for (DiscoveryService.RpcEndhostApiInfo api : group.getApisList()) {
          addresses.add(api.getAddress());
        }
      }
      return addresses;
    } catch (IOException e) {
      throw new ScionRuntimeException("Endhost API discovery failed: " + discoveryEndpoint, e);
    }
  }
}
