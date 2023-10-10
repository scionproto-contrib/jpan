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
import org.junit.jupiter.api.Test;
import org.scion.proto.daemon.Daemon;
import org.scion.testutil.MockDaemon;

public class PathServiceTest {

  private static final String SCION_HOST = "as110.test";
  private static final String SCION_TXT = "\"scion=1-ff00:0:110,127.0.0.1\"";

  @BeforeAll
  public static void beforeAll() {
    System.setProperty(ScionConstants.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + ";" + SCION_TXT);
  }

  @AfterAll
  public static void afterAll() {
    System.clearProperty(ScionConstants.DEBUG_PROPERTY_DNS_MOCK);
  }

  @Test
  public void testWrongDaemonAddress() {
    ScionException thrown =
        assertThrows(
            ScionException.class,
            () -> {
              String daemonAddr = "127.0.0.112:65432"; // 30255";
              long srcIA = ScionUtil.ParseIA("1-ff00:0:110");
              long dstIA = ScionUtil.ParseIA("1-ff00:0:112");
              try (ScionPathService client = ScionPathService.create(daemonAddr)) {
                client.getPath(srcIA, dstIA);
              }
            });

    assertEquals("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception", thrown.getMessage());
  }

  @Test
  public void getPath() throws IOException {
    MockDaemon mock = MockDaemon.create().start();

    // String daemonAddr = "127.0.0.12:30255"; // from 110-topo
    List<Daemon.Path> paths;
    long srcIA = ScionUtil.ParseIA("1-ff00:0:110");
    long dstIA = ScionUtil.ParseIA("1-ff00:0:112");
    try (ScionPathService client = ScionPathService.create(MockDaemon.DEFAULT_ADDRESS)) {
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

    mock.close();
  }

  @Test
  public void getScionAddress() throws IOException {
    // TODO this test makes a DNS call _and_ it depends on ETH having a specific ISD/AS/IP
    try (ScionPathService pathService = ScionPathService.create()) {
      // TXT entry: "scion=64-2:0:9,129.132.230.98"
      ScionAddress sAddr = pathService.getScionAddress("ethz.ch");
      assertNotNull(sAddr);
      assertEquals(64, sAddr.getIsd());
      assertEquals("64-2:0:9", ScionUtil.toStringIA(sAddr.getIsdAs()));
      assertEquals("/129.132.230.98", sAddr.getInetAddress().toString());
      assertEquals("ethz.ch", sAddr.getHostName());
    }
  }

  @Test
  public void getScionAddress_Mock() throws IOException {
    // Test that DNS injection via properties works
    // TODO do injection here instead of @BeforeAll
    try (ScionPathService pathService = ScionPathService.create()) {
      // TXT entry: "scion=64-2:0:9,129.132.230.98"
      ScionAddress sAddr = pathService.getScionAddress(SCION_HOST);
      assertNotNull(sAddr);
      assertEquals(1, sAddr.getIsd());
      assertEquals("1-ff00:0:110", ScionUtil.toStringIA(sAddr.getIsdAs()));
      assertEquals("/127.0.0.1", sAddr.getInetAddress().toString());
      assertEquals(SCION_HOST, sAddr.getHostName());
    }
  }

  @Test
  public void getScionAddress_Failure_IpOnly() {
    try (ScionPathService pathService = ScionPathService.create()) {
      // TXT entry: "scion=64-2:0:9,129.132.230.98"
      pathService.getScionAddress("127.12.12.12");
      fail();
    } catch (ScionException e) {
      assertTrue(e.getMessage().contains("No DNS"), e.getMessage());
    } catch (Throwable t) {
      fail();
    }
  }

  @Test
  public void getScionAddress_Failure_NoScion() {
    try (ScionPathService pathService = ScionPathService.create()) {
      pathService.getScionAddress("google.com"); // TODO this may fail if google supports SCION...
      fail();
    } catch (ScionException e) {
      assertTrue(e.getMessage().contains("Host has no SCION"), e.getMessage());
    } catch (Throwable t) {
      fail();
    }
  }
}
