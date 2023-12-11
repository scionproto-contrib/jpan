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
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.testutil.MockDaemon;

public class ScionServiceTest {

  private static final String SCION_HOST = "as110.test";
  private static final String SCION_TXT = "\"scion=1-ff00:0:110,127.0.0.1\"";
  private static final int DEFAULT_PORT = MockDaemon.DEFAULT_PORT;

  @BeforeAll
  public static void beforeAll() {
    System.setProperty(
        PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + "=" + SCION_TXT);
  }

  @AfterAll
  public static void afterAll() {
    System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
  }

  @BeforeEach
  public void beforeEach() {
    // reset counter
    MockDaemon.getAndResetCallCount();
  }

  @Test
  void testWrongDaemonAddress() throws IOException {
    String daemonAddr = "127.0.0.112:12345";
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    try (Scion.CloseableService client = Scion.newServiceForAddress(daemonAddr)) {
      ScionException thrown =
          assertThrows(ScionException.class, () -> client.getPaths(dstIA, dstAddress).get(0));
      assertTrue(
          thrown.getMessage().startsWith("Error while getting AS info:"), thrown.getMessage());
    }
  }

  @Test
  void testDaemonCreationIPv6() throws IOException {
    MockDaemon.createAndStartDefault();
    String daemonAddr = "[::1]:" + DEFAULT_PORT;
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    try (Scion.CloseableService client = Scion.newServiceForAddress(daemonAddr)) {
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
    try (Scion.CloseableService client = Scion.newServiceForAddress(daemonAddr)) {
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
          Scion.newServiceForAddress(MockDaemon.DEFAULT_ADDRESS_STR)) {
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
          Scion.newServiceForAddress(MockDaemon.DEFAULT_ADDRESS_STR)) {
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
  void getScionAddress() throws ScionException {
    // TODO this test makes a DNS call _and_ it depends on ETH having a specific ISD/AS/IP
    ScionService pathService = ScionService.defaultService();
    // TXT entry: "scion=64-2:0:9,129.132.230.98"
    ScionAddress sAddr = pathService.getScionAddress("ethz.ch");
    assertNotNull(sAddr);
    assertEquals(64, sAddr.getIsd());
    assertEquals("64-2:0:9", ScionUtil.toStringIA(sAddr.getIsdAs()));
    assertEquals("/129.132.230.98", sAddr.getInetAddress().toString());
    assertEquals("ethz.ch", sAddr.getHostName());
  }

  @Test
  void getScionAddress_Mock() throws ScionException {
    // Test that DNS injection via properties works
    // TODO do injection here instead of @BeforeAll
    ScionService pathService = ScionService.defaultService();
    // TXT entry: "scion=64-2:0:9,129.132.230.98"
    ScionAddress sAddr = pathService.getScionAddress(SCION_HOST);
    assertNotNull(sAddr);
    assertEquals(1, sAddr.getIsd());
    assertEquals("1-ff00:0:110", ScionUtil.toStringIA(sAddr.getIsdAs()));
    assertEquals("/127.0.0.1", sAddr.getInetAddress().toString());
    assertEquals(SCION_HOST, sAddr.getHostName());
  }

  @Test
  void getScionAddress_Failure_IpOnly() {
    ScionService pathService = ScionService.defaultService();
    // TXT entry: "scion=64-2:0:9,129.132.230.98"
    Exception ex =
        assertThrows(ScionException.class, () -> pathService.getScionAddress("127.12.12.12"));
    assertTrue(ex.getMessage().contains("No DNS"), ex.getMessage());
  }

  @Test
  void getScionAddress_Failure_NoScion() {
    ScionService pathService = ScionService.defaultService();
    // TODO this may fail if google supports SCION...
    Exception exception =
        assertThrows(ScionException.class, () -> pathService.getScionAddress("google.com"));

    String actualMessage = exception.getMessage();
    assertTrue(actualMessage.contains("Host has no SCION"));
  }

  @Test
  void getScionAddress_Failure_InvalidTXT() {
    testInvalidTxtEntry("\"XXXscion=1-ff00:0:110,127.0.0.55\"");
    testInvalidTxtEntry("\"XXXscion=1-ff00:0:110,127.0.0.55");
    testInvalidTxtEntry("\"XXXscion=1-xxxx:0:110,127.0.0.55\"");
    testInvalidTxtEntry("\"XXXscion=1-ff:00:0:110,127.0.0.55\"");
    testInvalidTxtEntry("\"XXXscion=1-ff:00:0:110,127.55\"");
  }

  private void testInvalidTxtEntry(String txtEntry) {
    ScionService pathService = ScionService.defaultService();
    String host = "127.0.0.55";
    try {
      System.setProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, host + "=" + txtEntry);
      Exception ex = assertThrows(ScionException.class, () -> pathService.getScionAddress(host));
      assertTrue(ex.getMessage().startsWith("Invalid TXT entry"), ex.getMessage());
    } finally {
      System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
    }
  }

  @Test
  void bootstrapViaDns() throws IOException {
    InetSocketAddress addr = Scion.defaultService().bootstrapViaDNS("inf.ethz.ch");
    assertNotNull(addr);
    System.out.println(addr);
    // TODO avoid argument!
    //System.out.println(Scion.defaultService().bootstrapViaDNS("inf.ethz.ch").ddr);

    // TODO
    //   - default to (inf).ethz.ch
    //   - default to http (not https)?
  }
}
