// Copyright 2023 ETH Zurich
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

package org.scion.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.internal.DNSHelper;
import org.scion.testutil.DNSUtil;
import org.scion.testutil.MockDaemon;
import org.scion.testutil.MockNetwork;
import org.scion.testutil.MockTopologyServer;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;

public class ScionServiceTest {

  private static final String SCION_HOST = "as110.test";
  private static final String SCION_TXT = "\"scion=1-ff00:0:110,127.0.0.1\"";
  private static final String SCION_TXT_IPV6 = "\"scion=1-ff00:0:110,[::1]\"";
  private static final int DEFAULT_PORT = MockDaemon.DEFAULT_PORT;

  @AfterAll
  public static void afterAll() {
    System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
    // Defensive clean up
    ScionService.closeDefault();
  }

  @BeforeEach
  public void beforeEach() {
    ScionService.closeDefault();
    // reset counter
    MockDaemon.getAndResetCallCount();
  }

  @Test
  void testWrongDaemonAddress() {
    String daemonAddr = "127.0.0.112:12345";
    ScionRuntimeException thrown =
        assertThrows(ScionRuntimeException.class, () -> Scion.newServiceWithDaemon(daemonAddr));
    assertTrue(thrown.getMessage().startsWith("Could not connect"), thrown.getMessage());
  }

  @Test
  void testDaemonCreationIPv6() throws IOException {
    MockDaemon.createAndStartDefault();
    String daemonAddr = "[::1]:" + DEFAULT_PORT;
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    try (Scion.CloseableService client = Scion.newServiceWithDaemon(daemonAddr)) {
      RequestPath path = client.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      // local AS + path
      assertEquals(2, MockDaemon.getAndResetCallCount());
    } finally {
      MockDaemon.closeDefault();
    }
  }

  @Test
  void testDaemonCreationIPv4() throws IOException {
    MockDaemon.createAndStartDefault();
    String daemonAddr = "127.0.0.1:" + DEFAULT_PORT;
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    try (Scion.CloseableService client = Scion.newServiceWithDaemon(daemonAddr)) {
      RequestPath path = client.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      // local AS + path
      assertEquals(2, MockDaemon.getAndResetCallCount());
    } finally {
      MockDaemon.closeDefault();
    }
  }

  @Test
  void getPath() throws IOException {
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    MockDaemon.createAndStartDefault();
    try {
      // String daemonAddr = "127.0.0.12:30255"; // from 110-topo
      RequestPath path;
      long dstIA = ScionUtil.parseIA("1-ff00:0:112");
      try (Scion.CloseableService client =
          Scion.newServiceWithDaemon(MockDaemon.DEFAULT_ADDRESS_STR)) {
        path = client.getPaths(dstIA, dstAddress).get(0);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // Expected:
      //    Paths found: 1
      //    Path: first hop = 127.0.0.10:31004
      //    0: 2 561850441793808
      //    0: 1 561850441793810
      assertEquals("/127.0.0.10:31004", path.getFirstHopAddress().toString());
      // assertEquals(srcIA, path.getSourceIsdAs());
      assertEquals(dstIA, path.getDestinationIsdAs());
      assertEquals(36, path.getRawPath().length);

      assertEquals("127.0.0.10:31004", path.getInterface().getAddress());
      assertEquals(2, path.getInterfacesList().size());
      // assertEquals(1, viewer.getInternalHopsList().size());
      // assertEquals(0, viewer.getMtu());
      // assertEquals(0, viewer.getLinkTypeList().size());

      // localAS & path
      assertEquals(2, MockDaemon.getAndResetCallCount());
    } finally {
      MockDaemon.closeDefault();
    }
  }

  @Test
  void getPaths() throws IOException {
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    MockDaemon.createAndStartDefault();
    try {
      // String daemonAddr = "127.0.0.12:30255"; // from 110-topo
      List<RequestPath> paths;
      long dstIA = ScionUtil.parseIA("1-ff00:0:112");
      try (Scion.CloseableService client =
          Scion.newServiceWithDaemon(MockDaemon.DEFAULT_ADDRESS_STR)) {
        paths = client.getPaths(dstIA, dstAddress);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      // Expected:
      //    Paths found: 1
      //    Path: first hop = 127.0.0.10:31004
      //    0: 2 561850441793808
      //    0: 1 561850441793810
      assertEquals(1, paths.size());
      for (RequestPath path : paths) {
        assertEquals("/127.0.0.10:31004", path.getFirstHopAddress().toString());
        // assertEquals(srcIA, path.getSourceIsdAs());
        assertEquals(dstIA, path.getDestinationIsdAs());
      }

      // get local AS, get PATH
      assertEquals(2, MockDaemon.getAndResetCallCount());
    } finally {
      MockDaemon.closeDefault();
    }
  }

  @Test
  void getPaths_localAS() throws IOException {
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    MockDaemon.createAndStartDefault();
    try {
      // String daemonAddr = "127.0.0.12:30255"; // from 110-topo
      List<RequestPath> paths;
      long dstIA = ScionUtil.parseIA("1-ff00:0:110");
      try (Scion.CloseableService client =
          Scion.newServiceWithDaemon(MockDaemon.DEFAULT_ADDRESS_STR)) {
        paths = client.getPaths(dstIA, dstAddress);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      //  Paths found: 1
      //  Path:  exp=1708596832 / 2024-02-22T10:13:52Z  mtu=1472
      //  Path: first hop =
      //          raw: []
      //  raw: {}
      assertEquals(1, paths.size());
      RequestPath path = paths.get(0);
      InetAddress addr = InetAddress.getByAddress(path.getDestinationAddress());
      InetSocketAddress sAddr = new InetSocketAddress(addr, path.getDestinationPort());
      assertEquals(sAddr, path.getFirstHopAddress());
      assertEquals(dstIA, path.getDestinationIsdAs());

      // get local AS, get PATH
      assertEquals(2, MockDaemon.getAndResetCallCount());
    } finally {
      MockDaemon.closeDefault();
    }
  }

  @Test
  void getScionAddress_IPv4() throws IOException {
    // Test that DNS injection via properties works
    System.setProperty(
        PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + "=" + SCION_TXT);
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try {
      ScionService pathService = Scion.defaultService();
      // TXT entry: "scion=64-2:0:9,129.x.x.x"
      ScionAddress sAddr = pathService.getScionAddress(SCION_HOST);
      assertNotNull(sAddr);
      assertEquals(1, sAddr.getIsd());
      assertEquals("1-ff00:0:110", ScionUtil.toStringIA(sAddr.getIsdAs()));
      assertEquals("/127.0.0.1", sAddr.getInetAddress().toString());
      assertEquals(SCION_HOST, sAddr.getHostName());
    } finally {
      System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
      ScionService.closeDefault();
      MockNetwork.stopTiny();
    }
  }

  @Test
  void getScionAddress_IPv6() throws IOException {
    // Test that DNS injection via properties works
    System.setProperty(
        PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + "=" + SCION_TXT_IPV6);
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try {
      ScionService pathService = Scion.defaultService();
      // TXT entry: "scion=64-2:0:9,129.x.x.x"
      ScionAddress sAddr = pathService.getScionAddress(SCION_HOST);
      assertNotNull(sAddr);
      assertEquals(1, sAddr.getIsd());
      assertEquals("1-ff00:0:110", ScionUtil.toStringIA(sAddr.getIsdAs()));
      assertEquals("/0:0:0:0:0:0:0:1", sAddr.getInetAddress().toString());
      assertEquals(SCION_HOST, sAddr.getHostName());
    } finally {
      System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
      ScionService.closeDefault();
      MockNetwork.stopTiny();
    }
  }

  @Test
  void getScionAddress_Failure_BadTxtRecord() throws IOException {
    // Test that DNS injection via properties works
    System.setProperty(
        PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + "=" + SCION_TXT);
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);

    byte[] ip = {127, 0, 0, 1};
    final String KEY_X = "x-sciondiscovery";
    final String KEY_X_TCP = "x-sciondiscovery:tcp";
    try {
      Throwable t;
      DNSUtil.clear();
      DNSUtil.installNAPTR(MockTopologyServer.TOPO_HOST, ip, KEY_X + "yyyy=12345", KEY_X_TCP);
      t = assertThrows(ScionRuntimeException.class, Scion::defaultService);
      assertTrue(t.getMessage().startsWith("Could not find valid TXT "));

      DNSUtil.clear();
      DNSUtil.installNAPTR(MockTopologyServer.TOPO_HOST, ip, KEY_X + "=1x2345", KEY_X_TCP);
      t = assertThrows(ScionRuntimeException.class, Scion::defaultService);
      assertTrue(t.getMessage().startsWith("Could not find valid TXT "));

      DNSUtil.clear();
      DNSUtil.installNAPTR(MockTopologyServer.TOPO_HOST, ip, KEY_X + "=100000", KEY_X_TCP);
      t = assertThrows(ScionRuntimeException.class, Scion::defaultService);
      assertTrue(t.getMessage().startsWith("Could not find valid TXT "));

      // Valid entry, but invalid port
      DNSUtil.clear();
      DNSUtil.installNAPTR(MockTopologyServer.TOPO_HOST, ip, KEY_X + "=10000", KEY_X_TCP);
      t = assertThrows(ScionRuntimeException.class, Scion::defaultService);
      assertTrue(t.getMessage().startsWith("Error while getting topology file"));

      // Invalid NAPTR key
      DNSUtil.clear();
      DNSUtil.installNAPTR(MockTopologyServer.TOPO_HOST, ip, KEY_X + "=10000", "x-wrong:tcp");
      t = assertThrows(ScionRuntimeException.class, Scion::defaultService);
      assertTrue(t.getMessage().startsWith("No valid DNS NAPTR entry found"));
    } finally {
      System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
      ScionService.closeDefault();
      MockNetwork.stopTiny();
      DNSUtil.clear();
    }
  }

  @Test
  void getScionAddress_Failure_IpOnly() {
    // TXT entry: "scion=64-2:0:9,129.x.x.x"
    Exception ex = assertThrows(ScionRuntimeException.class, Scion::defaultService);
    assertTrue(ex.getMessage().contains("DNS"), ex.getMessage());
  }

  @Test
  void getScionAddress_Failure_NoScion() {
    Exception exception = assertThrows(ScionRuntimeException.class, Scion::defaultService);

    String actualMessage = exception.getMessage();
    assertTrue(actualMessage.contains("DNS"), actualMessage);
  }

  @Test
  void getScionAddress_Failure_InvalidTXT() {
    Scion.closeDefault();
    //  testInvalidTxtEntry("\"XXXscion=1-ff00:0:110,127.0.0.55\"");
    //  testInvalidTxtEntry("\"scion=1-ff00:0:110,127.0.0.55");
    testInvalidTxtEntry("\"scion=1-xxxx:0:110,127.0.0.55\"");
    testInvalidTxtEntry("\"scion=1-ff:00:0:110,127.0.0.55\"");
    testInvalidTxtEntry("\"scion=1-ff:00:0:110,127.55\"");
  }

  private void testInvalidTxtEntry(String txtEntry) {
    String host = "127.0.0.55";
    System.setProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, host + "=" + txtEntry);
    // Use any topo file
    MockTopologyServer topo = MockTopologyServer.start(MockTopologyServer.TOPOFILE_TINY_110, true);
    try {
      ScionService pathService = Scion.defaultService();
      Exception ex = assertThrows(ScionException.class, () -> pathService.getScionAddress(host));
      assertTrue(ex.getMessage().startsWith("Invalid TXT entry"), ex.getMessage());
    } finally {
      System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
      ScionService.closeDefault();
      topo.close();
    }
  }

  @Test
  void openChannel() throws IOException {
    MockDaemon.createAndStartDefault();
    try (Scion.CloseableService service =
        Scion.newServiceWithDaemon(MockDaemon.DEFAULT_ADDRESS_STR)) {
      try (DatagramChannel channel = service.openChannel()) {
        assertEquals(service, channel.getService());
      }
    } finally {
      MockDaemon.closeDefault();
    }
  }

  @Test
  void getIsdAs_etcHostsFile() throws IOException, URISyntaxException {
    URL resource = getClass().getClassLoader().getResource("etc-scion-hosts");
    java.nio.file.Path file = Paths.get(resource.toURI());
    System.setProperty(Constants.PROPERTY_HOSTS_FILES, file.toString());
    MockDaemon.createAndStartDefault();
    try {
      ScionService service = Scion.defaultService();
      // line 1
      long ia1 = service.getIsdAs("test-server");
      assertEquals(ScionUtil.parseIA("1-ff00:0:111"), ia1);
      long ia1IP = service.getIsdAs("42.0.0.11");
      assertEquals(ScionUtil.parseIA("1-ff00:0:111"), ia1IP);

      // line 2
      long ia2a = service.getIsdAs("test-server-1");
      assertEquals(ScionUtil.parseIA("1-ff00:0:112"), ia2a);
      long ia2b = service.getIsdAs("test-server-2");
      assertEquals(ScionUtil.parseIA("1-ff00:0:112"), ia2b);
      long ia2IP = service.getIsdAs("42.0.0.12");
      assertEquals(ScionUtil.parseIA("1-ff00:0:112"), ia2IP);

      // line 3
      long ia3 = service.getIsdAs("test-server-ipv6");
      assertEquals(ScionUtil.parseIA("1-ff00:0:113"), ia3);
      long ia3IP = service.getIsdAs("::42");
      assertEquals(ScionUtil.parseIA("1-ff00:0:113"), ia3IP);

      // Should all fail for various reasons, but ensure that these domains
      // did not get registered despite being in the hosts file:
      assertThrows(IOException.class, () -> service.getIsdAs("hello"));
      assertThrows(IOException.class, () -> service.getIsdAs("42.0.0.10"));
      assertThrows(Exception.class, () -> service.getIsdAs(""));
    } finally {
      MockDaemon.closeDefault();
      System.clearProperty(Constants.PROPERTY_HOSTS_FILES);
    }
  }

  @Test
  void testDomainSearchResolver_invalidHost() throws IOException {
    try {
      String searchHost = "hello.there.com";
      Name n = Name.fromString(searchHost);
      Lookup.setDefaultSearchPath(n);
      // Topology server cannot be found
      assertNull(DNSHelper.searchForDiscoveryService());
      Throwable t = assertThrows(ScionRuntimeException.class, Scion::defaultService);
      assertTrue(
          t.getMessage().contains("Could not connect to daemon, DNS or bootstrap resource."));
    } finally {
      Lookup.setDefaultSearchPath(Collections.emptyList());
    }
  }

  @Test
  void testDomainSearchResolver_nonScionHost() throws IOException {
    try {
      String searchHost = "localhost"; // "google.com";
      Name n = Name.fromString(searchHost);
      Lookup.setDefaultSearchPath(n);
      // Topology server is not SCION enabled
      assertNull(DNSHelper.searchForDiscoveryService());
      Throwable t = assertThrows(ScionRuntimeException.class, Scion::defaultService);
      assertTrue(
          t.getMessage().contains("Could not connect to daemon, DNS or bootstrap resource."));
    } finally {
      Lookup.setDefaultSearchPath(Collections.emptyList());
    }
  }

  @Test
  void testDomainSearchResolver() throws IOException {
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try {
      String searchHost = MockTopologyServer.TOPO_HOST;
      // Change to use custom search domain
      Lookup.setDefaultSearchPath(Name.fromString(searchHost));
      // Lookup topology server
      String address = MockNetwork.getTopoServer().getAddress().toString();
      assertEquals(address.substring(1), DNSHelper.searchForDiscoveryService());
      ScionService service = Scion.defaultService();
      assertEquals(MockNetwork.getTopoServer().getLocalIsdAs(), service.getLocalIsdAs());
    } finally {
      Lookup.setDefaultSearchPath(Collections.emptyList());
      MockNetwork.stopTiny();
    }
  }
}
