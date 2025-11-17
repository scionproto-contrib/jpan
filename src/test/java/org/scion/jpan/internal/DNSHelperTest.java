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
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.testutil.DNSUtil;
import org.scion.jpan.testutil.MockDNS;
import org.xbill.DNS.*;

class DNSHelperTest {

  @AfterEach
  void afterEach() {
    DNSUtil.clear();
  }

  @Test
  void findSearchDomainViaReverseLookup_PTR_NAPTR_A_TXT_V4() {
    //  dig -x 129.132.0.0
    //  ;; QUESTION SECTION:
    //  ;0.0.132.129.in-addr.arpa.	IN	PTR
    //
    //  ;; ANSWER SECTION:
    //  0.0.132.129.in-addr.arpa. 3165 IN	PTR	my-dhcp-129-132-0-0.my.domain,org.

    DNSUtil.installAddress("whoami.akamai.net", new byte[] {1, 2, 3, 4});
    DNSUtil.installPTR("4.3.2.1.in-addr.arpa.", "my-dhcp-122-133-233-773.inf.hello.test");
    DNSUtil.installNAPTR("hello.test", new byte[] {2, 2, 2, 2}, 12345);

    Lookup.setDefaultSearchPath(Collections.emptyList());
    InetSocketAddress dsAddress = DNSHelper.searchForDiscoveryService(new MockDNS.MockResolver());
    assertEquals("2.2.2.2:12345", IPHelper.toString(dsAddress));
  }

  @Test
  void findSearchDomainViaReverseLookup_PTR_NAPTR_AAAA_TXT_V6() throws UnknownHostException {
    //  dig -x 2001:67c:10ec:5784:8000::x
    //
    //  ;; QUESTION SECTION:
    //  ;0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa. IN PTR
    //
    //  ;; ANSWER SECTION:
    //  0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa. 300 IN PTR
    //                2001-67c-10ec-5784-8000--x.x.x.org.

    InetAddress local = IPHelper.toInetAddress("[201:6c:1ec:5784:8000:0::42]");
    DNSUtil.installAddress("whoami.akamai.net", local.getAddress());

    String localArpa = DNSHelper.reverseAddressForARPA(local);
    DNSUtil.installPTR(localArpa, "my-dhcp-122-133-233-773.inf.hello6.test");
    InetAddress discovery = IPHelper.toInetAddress("[202:202:101:101:303:303:404:404]");
    DNSUtil.installNAPTR("hello6.test", discovery.getAddress(), 12345);

    Lookup.setDefaultSearchPath(Collections.emptyList());
    InetSocketAddress dsAddress = DNSHelper.searchForDiscoveryService(new MockDNS.MockResolver());
    assertNotNull(dsAddress);
    assertEquals("[202:202:101:101:303:303:404:404]:12345", IPHelper.toString(dsAddress));
  }

  @Test
  void findSearchDomainViaReverseLookup_SOA_NAPTR_SRV_V4() {
    InetAddress subnet = findSubnet(4);
    String arpa = DNSHelper.reverseAddressForARPA(subnet);
    // Strip leading zeros
    while (arpa.startsWith("0.")) {
      arpa = arpa.substring(2);
    }

    DNSUtil.installSOA(arpa, "my-ns-122-133-233-773.inf.hello.test");
    DNSUtil.installNAPTR_S("hello.test", "hello-new.test.");
    DNSUtil.installSRV("hello-new.test.", "discovery.test", 12321);
    DNSUtil.installAddress("discovery.test", new byte[] {2, 2, 2, 2});

    Lookup.setDefaultSearchPath(Collections.emptyList());
    InetSocketAddress dsAddress = DNSHelper.searchForDiscoveryService(new MockDNS.MockResolver());
    assertEquals("2.2.2.2:12321", IPHelper.toString(dsAddress));
  }

  @Test
  void findSearchDomainViaReverseLookup_SOA_NAPTR_SRV_V6() throws UnknownHostException {
    InetAddress subnet = findSubnet(16);
    String arpa = DNSHelper.reverseAddressForARPA(subnet);

    InetAddress discovery = IPHelper.toInetAddress("[202:202:101:101:303:303:404:404]");
    DNSUtil.installSOA(arpa, "my-dhcp-122-133-233-773.inf.hello6.test");
    DNSUtil.installNAPTR_S("hello6.test", "hello6-new.test.");
    DNSUtil.installSRV("hello6-new.test.", "discovery6.test", 12121);
    DNSUtil.installAddress("discovery6.test", discovery.getAddress());

    Lookup.setDefaultSearchPath(Collections.emptyList());
    InetSocketAddress dsAddress = DNSHelper.searchForDiscoveryService(new MockDNS.MockResolver());
    assertNotNull(dsAddress);
    assertEquals("[202:202:101:101:303:303:404:404]:12121", IPHelper.toString(dsAddress));
  }

  @Test
  void testReverseLookup_SOA_SRV_V4() {
    InetAddress subnet = findSubnet(4);
    String arpa = DNSHelper.reverseAddressForARPA(subnet);
    // Strip leading zeros
    while (arpa.startsWith("0.")) {
      arpa = arpa.substring(2);
    }

    //  dig SOA 168.192.in-addr.arpa.
    //  ;; QUESTION SECTION:
    //  ;168.192.in-addr.arpa.		IN	SOA
    //
    //  ;; ANSWER SECTION:
    //  168.192.in-addr.arpa.	1792	IN	SOA	xyz.abc.com. root.ayz.abc.com. 2024020101 21600 600 604800
    // 21600

    DNSUtil.installSOA(arpa, "my-ns-122-133-233-773.inf.hello.test");
    DNSUtil.installSRV("hello.test.", "discovery.test", 12321);
    DNSUtil.installAddress("discovery.test", new byte[] {2, 2, 2, 2});

    //  dig SRV _sciondiscovery._tcp.xyz.abc.com
    //      ;; QUESTION SECTION:
    //  ;_sciondiscovery._tcp.xyz.abc.com.	IN	SRV
    //
    //  ;; ANSWER SECTION:
    //  _sciondiscovery._tcp.xyz.abc.com. 1317 IN	SRV	10 10 8041 discovery.xyz.abc.com.
    Lookup.setDefaultSearchPath(Collections.emptyList());
    InetSocketAddress dsAddress = DNSHelper.searchForDiscoveryService(new MockDNS.MockResolver());
    // No port given, we get the default port 8041
    assertEquals("2.2.2.2:12321", IPHelper.toString(dsAddress));
  }

  @Test
  void testReverseLookup_SOA_SRV_V6() throws UnknownHostException {
    InetAddress subnet = findSubnet(16);
    String arpa = DNSHelper.reverseAddressForARPA(subnet);

    //  dig -x 2001:67c:10ec:5784:8000::x
    //
    //  ;; QUESTION SECTION:
    //  ;0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa. IN PTR
    //
    //  ;; ANSWER SECTION:
    //  0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa. 300 IN PTR
    //                2001-67c-10ec-5784-8000--x.x.x.org.

    InetAddress discovery = IPHelper.toInetAddress("[202:202:101:101:303:303:404:404]");
    DNSUtil.installSOA(arpa, "my-dhcp-122-133-233-773.inf.hello6.test");
    DNSUtil.installSRV("hello6.test.", "discovery6.test", 12121);
    DNSUtil.installAddress("discovery6.test", discovery.getAddress());

    Lookup.setDefaultSearchPath(Collections.emptyList());
    InetSocketAddress dsAddress = DNSHelper.searchForDiscoveryService(new MockDNS.MockResolver());
    assertEquals("[202:202:101:101:303:303:404:404]:12121", IPHelper.toString(dsAddress));
  }

  @Test
  void reverseAddressForARPA_V4() {
    InetAddress input = IPHelper.getByAddress(new int[] {129, 132, 255, 255});
    String output = DNSHelper.reverseAddressForARPA(input);
    assertEquals("255.255.132.129.in-addr.arpa.", output);
  }

  @Test
  void reverseAddressForARPA_V6() throws UnknownHostException {
    InetAddress input = IPHelper.toInetAddress("[2001:67c:10ec:5784:8000:0::42]");
    String expected = "2.4.0.0.0.0.0.0.0.0.0.0.0.0.0.8.4.8.7.5.c.e.0.1.c.7.6.0.1.0.0.2.ip6.arpa.";
    String output = DNSHelper.reverseAddressForARPA(input);
    assertEquals(expected, output);
  }

  @Test
  void testNAPTROrderPriority() {
    DNSUtil.installAddress("whoami.akamai.net", new byte[] {1, 2, 3, 4});
    DNSUtil.installPTR("4.3.2.1.in-addr.arpa.", "my-dhcp-122-133-233-773.inf.hello.test");

    // The DNS cache processes entries in installation order.

    // Bad order
    DNSUtil.installNAPTR_only("hello.test", "S", "x-sciondiscovery:tcp", "hi21.test.", 2, 10);
    DNSUtil.installSRV("hi21.test.", "discovery21.test", 20010);
    DNSUtil.installAddress("discovery21.test", new byte[] {1, 1, 1, 1});

    // Good order, bad preference
    DNSUtil.installNAPTR_only("hello.test", "S", "x-sciondiscovery:tcp", "hi12.test.", 1, 20);
    DNSUtil.installSRV("hi12.test.", "discovery12.test", 10020);
    DNSUtil.installAddress("discovery12.test", new byte[] {1, 1, 1, 1});

    // Good order + good preference -> This is it!
    DNSUtil.installNAPTR_only("hello.test", "S", "x-sciondiscovery:tcp", "hi11.test.", 1, 10);
    DNSUtil.installSRV("hi11.test.", "discovery11.test", 10010);
    DNSUtil.installAddress("discovery11.test", new byte[] {1, 1, 1, 1});

    Lookup.setDefaultSearchPath(Collections.emptyList());
    InetSocketAddress dsAddress = DNSHelper.searchForDiscoveryService(new MockDNS.MockResolver());
    assertEquals("1.1.1.1:10010", IPHelper.toString(dsAddress));
  }

  private InetAddress findSubnet(int len) {
    for (InetAddress i : IPHelper.getSubnets()) {
      if (i.getAddress().length == len) {
        if (!i.isSiteLocalAddress()) {
          return i;
        }
      }
    }
    System.err.println("No IP address available with len = " + len);
    fail();
    return null;
  }
}
