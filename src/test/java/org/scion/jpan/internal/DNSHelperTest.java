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
import java.util.Collections;
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
    //  dig -x 129.132.0.0
    //  ;; QUESTION SECTION:
    //  ;0.0.132.129.in-addr.arpa.	IN	PTR
    //
    //  ;; ANSWER SECTION:
    //  0.0.132.129.in-addr.arpa. 3165 IN	PTR	my-dhcp-129-132-0-0.my.domain,org.

    DNSUtil.installAddress("whoami.akamai.net", new byte[] {1, 2, 3, 4});
    DNSUtil.installPTR("4.3.2.1.in-addr.arpa.", "my-dhcp-122-133-233-773.inf.hello.test");
    DNSUtil.installNAPTR("hello.test", new byte[] {2, 2, 2, 2}, 12345);

    Name domain = DNSHelper.findSearchDomainViaReverseLookup();
    assertNotNull(domain);
    assertEquals("hello.test.", domain.toString());

    Lookup.setDefaultSearchPath(Collections.emptyList());
    String dsAddress = DNSHelper.searchForDiscoveryService();
    assertEquals("2.2.2.2:12345", dsAddress);
  }

  @Test
  void findSearchDomainViaReverseLookupV6() {
    //  dig -x 2001:67c:10ec:5784:8000::x
    //
    //  ;; QUESTION SECTION:
    //  ;0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa. IN PTR
    //
    //  ;; ANSWER SECTION:
    //  0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa. 300 IN PTR
    //                2001-67c-10ec-5784-8000--x.x.x.org.

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
          0,
          0
        });
    DNSUtil.installPTR(
        "0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa.",
        "my-dhcp-122-133-233-773.inf.hello6.test");
    DNSUtil.installNAPTR(
        "hello6.test", new byte[] {2, 2, 2, 2, 1, 1, 1, 1, 3, 3, 3, 3, 4, 4, 4, 4}, 12345);

    Name domain = DNSHelper.findSearchDomainViaReverseLookup();
    assertNotNull(domain);
    assertEquals("hello6.test.", domain.toString());

    Lookup.setDefaultSearchPath(Collections.emptyList());
    String dsAddress = DNSHelper.searchForDiscoveryService();
    assertEquals("[202:202:101:101:303:303:404:404]:12345", dsAddress);
  }

  @Test
  void reverseAddressForARPA_V4() {
    InetAddress input = IPHelper.getByAddress(new int[] {129, 132, 255, 255});
    String output = DNSHelper.reverseAddressForARPA(input);
    assertEquals("255.255.132.129.in-addr.arpa.", output);
  }

  @Test
  void reverseAddressForARPA_V6() {
    InetAddress input =
        IPHelper.getByAddress(
            new int[] {
              0x20, 0x01, 0x06, 0x7c, 0x10, 0xec, 0x57, 0x84, 0x80, 0x00, 0, 0, 0, 0, 0, 0
            });
    String expected = "0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa.";
    String output = DNSHelper.reverseAddressForARPA(input);
    assertEquals(expected, output);
  }

  @Test
  void soaLookup() throws TextParseException {
    {
      DNSUtil.installSOA("2.1.in-addr.arpa.", "my-ns-122-133-233-773.inf.hello.test");
      DNSUtil.installNAPTR("hello.test", new byte[] {1, 2, 2, 2}, 12345);
      InetAddress subnet = IPHelper.getByAddress(new int[] {1, 2, 0, 0});
      Name nameSub = DNSHelper.findSearchDomainViaSOALookup(subnet);
      System.out.println("Name Sub: " + nameSub);
      System.out.println();
    }

//    InetAddress addr = IPHelper.getByAddress(new int[]{172, 18, 0, 1});
//    Name nameAddr = DNSHelper.findSearchDomainViaSOALookup(addr);
//    System.out.println("Name Addr: " + nameAddr);
//    System.out.println();

    InetAddress subnet = IPHelper.getByAddress(new int[]{172, 18, 0, 0});
    Name nameSub = DNSHelper.findSearchDomainViaSOALookup(subnet);
    System.out.println("Name Sub: " + nameSub);
    System.out.println();

    InetAddress subnet2 = IPHelper.getByAddress(new int[]{163, 152, 0, 0});
    Name nameSub2 = DNSHelper.findSearchDomainViaSOALookup(subnet2);
    System.out.println("Name Sub2: " + nameSub2);
    System.out.println();
  }
}
