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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.scion.jpan.testutil.MockSnapApiTokenService;

class TokenFetcherTest {

  @Test
  void fetchSnapTokenWithMetadata_noMetadata_discoveryUrlIsNull() throws IOException {
    try (MockSnapApiTokenService aaService = MockSnapApiTokenService.start()) {
      TokenFetcher.Result result =
          TokenFetcher.fetchSnapTokenWithMetadata(
              MockSnapApiTokenService.API_KEY, aaService.getBaseUrl());

      assertEquals(MockSnapApiTokenService.SNAP_TOKEN, result.snapToken);
      assertNull(result.endhostApiDiscoveryUrl);
    }
  }

  @Test
  void fetchSnapTokenWithMetadata_withDiscoveryUrl_isSurfaced() throws IOException {
    String discoveryUrl = "https://discovery.example.com:5001";
    try (MockSnapApiTokenService aaService = MockSnapApiTokenService.start(discoveryUrl)) {
      TokenFetcher.Result result =
          TokenFetcher.fetchSnapTokenWithMetadata(
              MockSnapApiTokenService.API_KEY, aaService.getBaseUrl());

      assertEquals(MockSnapApiTokenService.SNAP_TOKEN, result.snapToken);
      assertEquals(discoveryUrl, result.endhostApiDiscoveryUrl);
    }
  }

  @Test
  void fetchSnapToken_stillReturnsOnlyTheToken() throws IOException {
    try (MockSnapApiTokenService aaService =
        MockSnapApiTokenService.start("https://discovery.example.com:5001")) {
      String token = TokenFetcher.fetchSnapToken(MockSnapApiTokenService.API_KEY, aaService.getBaseUrl());

      assertEquals(MockSnapApiTokenService.SNAP_TOKEN, token);
    }
  }
}
