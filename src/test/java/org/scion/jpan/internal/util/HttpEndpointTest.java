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

import org.junit.jupiter.api.Test;

class HttpEndpointTest {

  @Test
  void bareAddress_usesDefaultScheme() {
    assertEquals(
        "http://192.168.53.19:48080", HttpEndpoint.normalizeBaseUrl("192.168.53.19:48080", "http"));
    assertEquals(
        "https://192.168.53.19:48080",
        HttpEndpoint.normalizeBaseUrl("192.168.53.19:48080", "https"));
    assertEquals(
        "https://discovery.scion.xyz.net",
        HttpEndpoint.normalizeBaseUrl("discovery.scion.xyz.net", "https"));
  }

  @Test
  void explicitScheme_isKept_evenIfDifferentFromDefault() {
    // An explicit "https://" must never be downgraded to the default scheme.
    assertEquals(
        "https://s01.chgtg1.snap.xyz.net:5001",
        HttpEndpoint.normalizeBaseUrl("https://s01.chgtg1.snap.xyz.net:5001", "http"));
    // An explicit "http://" must never be upgraded to the default scheme either.
    assertEquals(
        "http://192.168.1.1:12345",
        HttpEndpoint.normalizeBaseUrl("http://192.168.1.1:12345", "https"));
  }

  @Test
  void trailingSlashes_areStripped() {
    assertEquals(
        "https://s01.chgtg1.snap.xyz.net:5001",
        HttpEndpoint.normalizeBaseUrl("https://s01.chgtg1.snap.xyz.net:5001/", "https"));
    assertEquals(
        "https://s01.chgtg1.snap.xyz.net:5001",
        HttpEndpoint.normalizeBaseUrl("https://s01.chgtg1.snap.xyz.net:5001///", "https"));
    // Trailing slashes on a scheme-less address must also be stripped after the default scheme
    // is prepended.
    assertEquals(
        "http://192.168.1.1:12345", HttpEndpoint.normalizeBaseUrl("192.168.1.1:12345/", "http"));
  }

  @Test
  void nullOrEmptyEndpoint_throws() {
    IllegalArgumentException e;
    e =
        assertThrows(
            IllegalArgumentException.class, () -> HttpEndpoint.normalizeBaseUrl(null, "http"));
    assertTrue(e.getMessage().contains("must not be empty"));
    e =
        assertThrows(
            IllegalArgumentException.class, () -> HttpEndpoint.normalizeBaseUrl("", "http"));
    assertTrue(e.getMessage().contains("must not be empty"));
  }
}
