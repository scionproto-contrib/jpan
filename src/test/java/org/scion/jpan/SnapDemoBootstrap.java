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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

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

  /** Human-readable description of which endhost API source is in use, for demo log output. */
  static String endhostApiDescription(String endhostApi, String discoveryEndpoint) {
    return discoveryEndpoint != null ? "discovery:" + discoveryEndpoint : endhostApi;
  }
}
