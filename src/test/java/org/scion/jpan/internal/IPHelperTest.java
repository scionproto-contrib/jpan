// Copyright 2024 ETH Zurich
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

package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class IPHelperTest {

  @Test
  void isLocalhost() {
    assertTrue(IPHelper.isLocalhost("localhost"));
    assertTrue(IPHelper.isLocalhost("127.0.0.42"));

    assertTrue(IPHelper.isLocalhost("::1"));
    assertTrue(IPHelper.isLocalhost("0:0:0:0:0:0:0:1"));
    assertTrue(IPHelper.isLocalhost("ip6-localhost"));

    assertFalse(IPHelper.isLocalhost("dummy.com"));
    assertFalse(IPHelper.isLocalhost("192.168.0.1"));
  }

  @Test
  void lookupLocalhost() {
    assertArrayEquals(new byte[] {127, 0, 0, 1}, IPHelper.lookupLocalhost("localhost"));
    assertArrayEquals(new byte[] {127, 0, 0, 42}, IPHelper.lookupLocalhost("127.0.0.42"));

    byte[] ipv6 = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    assertArrayEquals(ipv6, IPHelper.lookupLocalhost("::1"));
    assertArrayEquals(ipv6, IPHelper.lookupLocalhost("0:0:0:0:0:0:0:1"));
    assertArrayEquals(ipv6, IPHelper.lookupLocalhost("ip6-localhost"));

    assertNull(IPHelper.lookupLocalhost("dummy.com"));
    assertNull(IPHelper.lookupLocalhost("192.168.0.1"));
  }

  @Test
  void toByteArray() {
    assertArrayEquals(new byte[] {127, 0, 0, 1}, IPHelper.toByteArray("localhost"));
    assertArrayEquals(new byte[] {127, 0, 0, 1}, IPHelper.toByteArray("127.0.0.1"));
    assertArrayEquals(new byte[] {127, 0, 0, 42}, IPHelper.toByteArray("127.0.0.42"));

    byte[] ipv6 = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    assertArrayEquals(ipv6, IPHelper.toByteArray("::1"));
    assertArrayEquals(ipv6, IPHelper.toByteArray("[::1]"));
    assertArrayEquals(ipv6, IPHelper.toByteArray("0:0:0:0:0:0:0:1"));
    assertArrayEquals(ipv6, IPHelper.toByteArray("[0:0:0:0:0:0:0:1]"));
    assertArrayEquals(ipv6, IPHelper.toByteArray("ip6-localhost"));

    assertNull(IPHelper.toByteArray("dummy.com"));
    assertArrayEquals(new byte[] {1, 2, 3, 4}, IPHelper.toByteArray("1.2.3.4"));
    assertArrayEquals(new byte[] {192 - 256, 168 - 256, 0, 1}, IPHelper.toByteArray("192.168.0.1"));

    assertNull(IPHelper.toByteArray("[::1"));
    assertNull(IPHelper.toByteArray("::1]"));

    assertNull(IPHelper.toByteArray("127.0.0.1x"));
    assertNull(IPHelper.toByteArray("[::1x]"));
  }

  @Test
  void toInetAddress() throws UnknownHostException {
    assertArrayEquals(new byte[] {127, 0, 0, 1}, IPHelper.toInetAddress("localhost").getAddress());
    assertArrayEquals(
        new byte[] {127, 0, 0, 42}, IPHelper.toInetAddress("127.0.0.42").getAddress());

    byte[] ipv6 = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    assertArrayEquals(ipv6, IPHelper.toInetAddress("::1").getAddress());
  }

  @Test
  void ensurePortOrDefault() {
    assertEquals("127.0.0.1:12345", IPHelper.ensurePortOrDefault("127.0.0.1", 12345));
    assertEquals("127.0.0.1:333", IPHelper.ensurePortOrDefault("127.0.0.1:333", 12345));

    assertEquals("[::1]:12345", IPHelper.ensurePortOrDefault("::1", 12345));
    assertEquals("[::1]:12345", IPHelper.ensurePortOrDefault("[::1]", 12345));
    assertEquals("[::1]:333", IPHelper.ensurePortOrDefault("[::1]:333", 12345));

    assertEquals("localhost:12345", IPHelper.ensurePortOrDefault("localhost", 12345));
    assertEquals("localhost:333", IPHelper.ensurePortOrDefault("localhost:333", 12345));

    assertEquals("localhost:333", IPHelper.ensurePortOrDefault("333", 12345));
  }
}
