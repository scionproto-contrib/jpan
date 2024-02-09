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
import org.scion.testutil.MockNetwork;
import org.scion.testutil.MockTopologyServer;

public class ScionServiceTest {

  private static final String SCION_HOST = "as110.test";
  private static final String SCION_TXT = "\"scion=1-ff00:0:110,127.0.0.1\"";
  private static final int DEFAULT_PORT = MockDaemon.DEFAULT_PORT;

  @BeforeAll
  public static void beforeAll() {
    //    System.setProperty(
    //        PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + "=" + SCION_TXT);
  }

  @AfterAll
  public static void afterAll() {
    System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
    // Defensive clean up
    ScionService.closeDefault();
  }

  @BeforeEach
  public void beforeEach() {
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
  void getScionAddress() throws IOException {
    // Test that DNS injection via properties works
    System.setProperty(
        PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + "=" + SCION_TXT);
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try {
      ScionService pathService = Scion.defaultService();
      // TXT entry: "scion=64-2:0:9,129.132.230.98"
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
  void getScionAddress_Failure_IpOnly() {
    // TXT entry: "scion=64-2:0:9,129.132.230.98"
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
    testInvalidTxtEntry("\"XXXscion=1-ff00:0:110,127.0.0.55\"");
    testInvalidTxtEntry("\"scion=1-ff00:0:110,127.0.0.55");
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
}
