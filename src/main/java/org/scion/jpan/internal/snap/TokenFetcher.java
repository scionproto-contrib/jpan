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

package org.scion.jpan.internal.snap;

import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.scion.jpan.proto.snap.aa.Auth;

public class TokenFetcher {

  private TokenFetcher() {}

  public static String fetchSnapToken(String apiKey, String serverUrl) throws IOException {
    return fetchSnapTokenWithMetadata(apiKey, serverUrl).snapToken;
  }

  /**
   * Like {@link #fetchSnapToken}, but also surfaces the AA service's optional metadata -- in
   * particular {@code endhost_api_discovery_url}, a discovery-service URL scoped to the
   * authenticated user. When present, callers should prefer it over the global discovery service
   * at {@code https://discovery.scion.anapaya.net}.
   */
  public static Result fetchSnapTokenWithMetadata(String apiKey, String serverUrl)
      throws IOException {
    String baseUrl =
        serverUrl.startsWith("http://") || serverUrl.startsWith("https://")
            ? serverUrl
            : "https://" + serverUrl;
    Auth.AuthenticateByKeyRequest request =
        Auth.AuthenticateByKeyRequest.newBuilder()
            .setApiKey(apiKey)
            .setDeviceId("jpan-app")
            .setRequestedValidity(0)
            .build();
    RequestBody body = RequestBody.create(request.toByteArray());
    Request httpRequest =
        new Request.Builder()
            .url(baseUrl + "/anapaya.aa.v1.AuthService/AuthenticateByKey")
            .addHeader("Content-type", "application/proto")
            .post(body)
            .build();
    OkHttpClient client = new OkHttpClient();
    try (Response response = client.newCall(httpRequest).execute()) {
      ResponseBody responseBody = response.body();
      if (!response.isSuccessful() || responseBody == null) {
        throw new IOException("AA auth failed: " + response.code() + " " + response.message());
      }
      Auth.AuthenticateByKeyResponse parsed =
          Auth.AuthenticateByKeyResponse.newBuilder().mergeFrom(responseBody.bytes()).build();
      String discoveryUrl = null;
      if (parsed.hasMetadata() && parsed.getMetadata().hasEndhostApiDiscoveryUrl()) {
        String url = parsed.getMetadata().getEndhostApiDiscoveryUrl();
        if (!url.isEmpty()) {
          discoveryUrl = url;
        }
      }
      return new Result(parsed.getSnapToken(), discoveryUrl);
    }
  }

  /** Result of {@link #fetchSnapTokenWithMetadata}. */
  public static final class Result {
    public final String snapToken;
    /** User-scoped endhost API discovery URL, or {@code null} if the AA service provided none. */
    public final String endhostApiDiscoveryUrl;

    Result(String snapToken, String endhostApiDiscoveryUrl) {
      this.snapToken = snapToken;
      this.endhostApiDiscoveryUrl = endhostApiDiscoveryUrl;
    }
  }
}
