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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class HttpEndpointTest {

  @ParameterizedTest
  @CsvSource({
    "192.168.53.19:48080, http, http://192.168.53.19:48080", // bare address uses default scheme
    "192.168.53.19:48080, https, https://192.168.53.19:48080",
    "discovery.scion.xyz.net, https, https://discovery.scion.xyz.net",
    "https://s01.chgtg1.snap.xyz.net:5001, http, https://s01.chgtg1.snap.xyz.net:5001", // explicit
    "http://192.168.1.1:12345, https, http://192.168.1.1:12345", // scheme is kept either way
    "https://s01.chgtg1.snap.xyz.net:5001/, https, https://s01.chgtg1.snap.xyz.net:5001", // and
    "https://s01.chgtg1.snap.xyz.net:5001///, https, https://s01.chgtg1.snap.xyz.net:5001", // slashes
    "192.168.1.1:12345/, http, http://192.168.1.1:12345" // are stripped even for a bare address
  })
  void normalizeBaseUrl_variousInputs_normalizedCorrectly(
      String endpoint, String defaultScheme, String expected) {
    assertEquals(expected, HttpEndpoint.normalizeBaseUrl(endpoint, defaultScheme));
  }

  @ParameterizedTest
  @NullAndEmptySource
  void normalizeBaseUrl_nullOrEmptyEndpoint_throws(String endpoint) {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> HttpEndpoint.normalizeBaseUrl(endpoint, "http"));
    assertTrue(e.getMessage().contains("must not be empty"));
  }
}
