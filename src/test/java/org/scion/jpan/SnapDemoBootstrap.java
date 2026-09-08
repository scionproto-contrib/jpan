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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.scion.jpan.internal.bootstrap.EndhostApiDiscoveryClient;
import org.scion.jpan.internal.snap.AAClient;

/**
 * Shared {@code --endhost-api}/{@code --discovery} resolution for the SNAP demos ({@link
 * SnapPacketDemo}, {@link SnapEchoDemo}, {@link SnapTracerouteDemo}), which otherwise all duplicate
 * this logic verbatim.
 */
final class SnapDemoBootstrap {

  private SnapDemoBootstrap() {}

  static String readTokenFile(String snapTokenFile) throws IOException {
    String snapToken =
        new String(Files.readAllBytes(Paths.get(snapTokenFile)), StandardCharsets.UTF_8).trim();
    if (snapToken.isEmpty()) {
      throw new IllegalArgumentException("Token file is empty: " + snapTokenFile);
    }
    return snapToken;
  }

  static String readAuthKeyFile(String authKeyFile) throws IOException {
    String authKey =
        new String(Files.readAllBytes(Paths.get(authKeyFile)), StandardCharsets.UTF_8).trim();
    if (authKey.isEmpty()) {
      throw new IllegalArgumentException("Auth key file is empty: " + authKeyFile);
    }
    return authKey;
  }

  /**
   * Resolves the endhost API address(es) to bootstrap from. If {@code discoveryEndpoint} is given,
   * the global endhost-API discovery service is queried first and its candidates (tried in order by
   * the existing multi-candidate path-service bootstrap) are used instead of a fixed {@code
   * endhostApi} address.
   */
  static String resolveBootstrapAddress(String endhostApi, String discoveryEndpoint) {
    if (discoveryEndpoint != null) {
      List<String> candidates = EndhostApiDiscoveryClient.discoverEndhostApis(discoveryEndpoint);
      if (candidates.isEmpty()) {
        throw new ScionRuntimeException(
            "Endhost API discovery returned no candidates: " + discoveryEndpoint);
      }
      System.out.println(
          "             ------------ DISCOVERY BOOT DP = "
              + discoveryEndpoint
              + " -> "
              + String.join(";", candidates));
      return String.join(";", candidates);
    }
    System.out.println(
        "             ------------ DISCOVERY BOOT E-API= "
            + endhostApi
            + " -> "
            + toBootstrapAddress(endhostApi));
    return toBootstrapAddress(endhostApi);
  }

  /** Human-readable description of which endhost API source is in use, for demo log output. */
  static String endhostApiDescription(String endhostApi, String discoveryEndpoint) {
    return discoveryEndpoint != null ? "discovery:" + discoveryEndpoint : endhostApi;
  }

  private static String toBootstrapAddress(String endhostApi) {
    try {
      URI uri = new URI(endhostApi);
      if (uri.getHost() == null || uri.getPort() < 0) {
        throw new IllegalArgumentException("endhost api must include host and port: " + endhostApi);
      }
      String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
      return scheme + "://" + uri.getHost() + ":" + uri.getPort();
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("invalid endhost api URL: " + endhostApi, e);
    }
  }

  /**
   * Resolves the SNAP auth token, either directly from a token file or by exchanging an API key
   * with the AA auth service. In the latter case, the AA response may include a discovery-service
   * URL scoped to the authenticated user (see {@link AAClient.Result#endhostApiDiscoveryUrl}),
   * which callers should use as a fallback {@code --discovery} endpoint when neither {@code
   * --endhost-api} nor {@code --discovery} was given explicitly on the command line.
   */
  static TokenResolution resolveSnapToken(String snapTokenFile, String authKeyFile)
      throws IOException {
    if (snapTokenFile != null) {
      String snapToken =
          new String(Files.readAllBytes(Paths.get(snapTokenFile)), StandardCharsets.UTF_8).trim();
      if (snapToken.isEmpty()) {
        throw new IllegalArgumentException("Token file is empty: " + snapTokenFile);
      }
      return new TokenResolution(null, snapToken, null);
    }
    String authKey =
        new String(Files.readAllBytes(Paths.get(authKeyFile)), StandardCharsets.UTF_8).trim();
    if (authKey.isEmpty()) {
      throw new IllegalArgumentException("Auth key file is empty: " + authKeyFile);
    }
    AAClient.Result result = AAClient.fetchAll(authKey, "auth.scion.anapaya.net");
    return new TokenResolution(authKey, result.snapToken, result.endhostApiDiscoveryUrl);
  }

  static final class TokenResolution {
    final String apiKey;
    final String snapToken;

    /** User-scoped discovery URL from the AA response, or {@code null} if none was provided. */
    final String endhostApiDiscoveryUrl;

    TokenResolution(String apiKey, String snapToken, String endhostApiDiscoveryUrl) {
      this.apiKey = apiKey;
      this.snapToken = snapToken;
      this.endhostApiDiscoveryUrl = endhostApiDiscoveryUrl;
    }
  }
}
