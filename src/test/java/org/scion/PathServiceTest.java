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

package org.scion;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.proto.daemon.Daemon;
import org.scion.testutil.MockDaemon;

public class PathServiceTest {

  private static final String SCION_HOST = "as110.test";
  private static final String SCION_TXT = "\"scion=1-ff00:0:110,127.0.0.1\"";
  private static final int DEFAULT_PORT = MockDaemon.DEFAULT_PORT;

  @BeforeAll
  public static void beforeAll() {
    System.setProperty(ScionConstants.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + ";" + SCION_TXT);
  }

  @AfterAll
  public static void afterAll() {
    System.clearProperty(ScionConstants.DEBUG_PROPERTY_DNS_MOCK);
  }

  @BeforeEach
  public void beforeEach() {
    // reset counter
    MockDaemon.getAndResetCallCount();
  }

  @Test
  void testWrongDaemonAddress() throws IOException {
    String daemonAddr = "127.0.0.112:12345";
    long srcIA = ScionUtil.ParseIA("1-ff00:0:110");
    long dstIA = ScionUtil.ParseIA("1-ff00:0:112");
    try (Scion.CloseableService client = Scion.newServiceForAddress(daemonAddr)) {
      ScionException thrown =
          assertThrows(ScionException.class, () -> client.getPath(srcIA, dstIA));
      assertEquals(
          "io.grpc.StatusRuntimeException: UNAVAILABLE: io exception", thrown.getMessage());
    }
  }

  @Test
  void testDaemonCreationIPv6() throws IOException {
    MockDaemon daemon = MockDaemon.create();
    daemon.start();
    String daemonAddr = "[::1]:" + DEFAULT_PORT;
    long srcIA = ScionUtil.ParseIA("1-ff00:0:110");
    long dstIA = ScionUtil.ParseIA("1-ff00:0:112");
    try (Scion.CloseableService client = Scion.newServiceForAddress(daemonAddr)) {
      ScionPath path = client.getPath(srcIA, dstIA);
      assertNotNull(path);
      assertEquals(1, MockDaemon.getAndResetCallCount());
    } finally {
      daemon.close();
    }
  }

  @Test
  void testDaemonCreationIPv4() throws IOException {
    MockDaemon daemon = MockDaemon.create();
    daemon.start();
    String daemonAddr = "127.0.0.1:" + DEFAULT_PORT;
    long srcIA = ScionUtil.ParseIA("1-ff00:0:110");
    long dstIA = ScionUtil.ParseIA("1-ff00:0:112");
    try (Scion.CloseableService client = Scion.newServiceForAddress(daemonAddr)) {
      ScionPath path = client.getPath(srcIA, dstIA);
      assertNotNull(path);
      assertEquals(1, MockDaemon.getAndResetCallCount());
    } finally {
      daemon.close();
    }
  }

  @Test
  void getPath() throws IOException {
    MockDaemon daemon = MockDaemon.create().start();

    // String daemonAddr = "127.0.0.12:30255"; // from 110-topo
    List<Daemon.Path> paths;
    long srcIA = ScionUtil.ParseIA("1-ff00:0:110");
    long dstIA = ScionUtil.ParseIA("1-ff00:0:112");
    try (Scion.CloseableService client =
        Scion.newServiceForAddress(MockDaemon.DEFAULT_ADDRESS_STR)) {
      paths = client.getPathList(srcIA, dstIA);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Expected:
    //    Paths found: 1
    //    Path: first hop = 127.0.0.10:31004
    //    0: 2 561850441793808
    //    0: 1 561850441793810

    assertEquals(1, paths.size());
    Daemon.Path path0 = paths.get(0);
    assertEquals("127.0.0.10:31004", path0.getInterface().getAddress().getAddress());

    //    System.out.println("Paths found: " + paths.size());
    //    for (Daemon.Path path : paths) {
    //      System.out.println("Path: first hop = " +
    // path.getInterface().getAddress().getAddress());
    //      int i = 0;
    //      for (Daemon.PathInterface segment : path.getInterfacesList()) {
    //        System.out.println("    " + i + ": " + segment.getId() + " " + segment.getIsdAs());
    //      }
    //    }

    assertEquals(1, MockDaemon.getAndResetCallCount());
    daemon.close();
  }

  @Test
  void getScionAddress() {
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
  void getScionAddress_Mock() {
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
}
