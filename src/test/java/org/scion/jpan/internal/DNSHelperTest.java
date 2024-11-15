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

import java.net.InetAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.testutil.DNSUtil;
import org.xbill.DNS.*;

class DNSHelperTest {

  @AfterEach
  void afterEach() {
    DNSUtil.clear();
  }

  @Test
  void findSearchDomainViaReverseLookupV4() {
    //  dig -x 129.132.230.73
    //  ;; QUESTION SECTION:
    //  ;73.230.132.129.in-addr.arpa.	IN	PTR
    //
    //  ;; ANSWER SECTION:
    //  73.230.132.129.in-addr.arpa. 3165 IN	PTR	infsec-dhcp-129-132-230-73.inf.ethz.ch.

    DNSUtil.installAddress("whoami.akamai.net", new byte[] {1, 2, 3, 4});
    DNSUtil.installPTR("4.3.2.1.in-addr.arpa.", "my-dhcp-122-133-233-773.inf.hello.test");
    DNSUtil.installNAPTR("hello.test", new byte[] {2, 2, 2, 2}, 12345);

    Name domain = DNSHelper.findSearchDomainViaReverseLookup();
    assertNotNull(domain);
    assertEquals("hello.test.", domain.toString());
  }

  @Test
  void findSearchDomainViaReverseLookupV6() {
    //  dig -x 2001:67c:10ec:5784:8000::40a
    //
    //  ;; QUESTION SECTION:
    //  ;a.0.4.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa. IN PTR
    //
    //  ;; ANSWER SECTION:
    //  a.0.4.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa. 300 IN PTR
    //                2001-67c-10ec-5784-8000--40a.net6.ethz.ch.

    DNSUtil.installAddress("whoami.akamai.net", new byte[] {0, 0, 0, 1});
    DNSUtil.installAddress(
        "whoami.akamai.net",
        new byte[] {
          0x20,
          0x01,
          0x06,
          0x7c,
          0x10,
          0xec - 256,
          0x57,
          0x84 - 256,
          0x80 - 256,
          0x00,
          0,
          0,
          0,
          0,
          0x04,
          0x0a
        });
    DNSUtil.installPTR(
        "a.0.4.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa.",
        "my-dhcp-122-133-233-773.inf.hello6.test");
    DNSUtil.installNAPTR(
        "hello6.test", new byte[] {2, 2, 2, 2, 1, 1, 1, 1, 3, 3, 3, 3, 4, 4, 4, 4}, 12345);

    Name domain = DNSHelper.findSearchDomainViaReverseLookup();
    assertNotNull(domain);
    assertEquals("hello6.test.", domain.toString());
  }

  @Test
  void reverseAddressForARPA_V4() {
    InetAddress input = IPHelper.getByAddress(new int[] {129, 132, 230, 73});
    String output = DNSHelper.reverseAddressForARPA(input);
    assertEquals("73.230.132.129.in-addr.arpa.", output);
  }

  @Test
  void reverseAddressForARPA_V6() {
    InetAddress input =
        IPHelper.getByAddress(
            new int[] {
              0x20,
              0x01,
              0x06,
              0x7c,
              0x10,
              0xec - 256,
              0x57,
              0x84,
              0x80,
              0x00,
              0,
              0,
              0,
              0,
              0x04,
              0x0a
            });
    String expected = "a.0.4.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa.";
    String output = DNSHelper.reverseAddressForARPA(input);
    assertEquals(expected, output);
  }
}
