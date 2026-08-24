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

package org.scion.jpan.internal.util;

/** Helpers for building HTTP(S) URLs for bootstrap and control-plane endpoints. */
public final class HttpEndpoint {

  private HttpEndpoint() {}

  /**
   * Normalizes an endpoint into a base URL, e.g. for use as {@code baseUrl + path}. If the endpoint
   * already starts with "http://" or "https://" that scheme is kept, otherwise {@code
   * defaultScheme} is prepended. Any trailing slashes are stripped.
   */
  public static String normalizeBaseUrl(String endpoint, String defaultScheme) {
    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalArgumentException("endpoint must not be empty");
    }
    String normalized =
        endpoint.startsWith("http://") || endpoint.startsWith("https://")
            ? endpoint
            : defaultScheme + "://" + endpoint;
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}
