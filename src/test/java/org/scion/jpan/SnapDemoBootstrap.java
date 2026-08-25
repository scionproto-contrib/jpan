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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.scion.jpan.internal.bootstrap.EndhostApiDiscoveryClient;

/**
 * Shared {@code --endhost-api}/{@code --discovery} resolution for the SNAP demos ({@link
 * SnapPacketDemo}, {@link SnapEchoDemo}, {@link SnapTracerouteDemo}), which otherwise all
 * duplicate this logic verbatim.
 */
final class SnapDemoBootstrap {

  private SnapDemoBootstrap() {}

  /**
   * Resolves the endhost API address(es) to bootstrap from. If {@code discoveryEndpoint} is
   * given, the global endhost-API discovery service is queried first and its candidates (tried in
   * order by the existing multi-candidate path-service bootstrap) are used instead of a fixed
   * {@code endhostApi} address.
   */
  static String resolveBootstrapAddress(String endhostApi, String discoveryEndpoint) {
    if (discoveryEndpoint != null) {
      List<String> candidates = EndhostApiDiscoveryClient.discoverEndhostApis(discoveryEndpoint);
      if (candidates.isEmpty()) {
        throw new ScionRuntimeException(
            "Endhost API discovery returned no candidates: " + discoveryEndpoint);
      }
      return String.join(";", candidates);
    }
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
}
