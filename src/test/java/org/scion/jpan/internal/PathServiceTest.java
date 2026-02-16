// Copyright 2025 ETH Zurich
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fi.iki.elonen.NanoHTTPD;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockControlServer;
import org.scion.jpan.testutil.MockNetwork2;
import org.scion.jpan.testutil.MockPathService;

class PathServiceTest {

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  @Test
  void testPathServiceFailure_NoPsDuringInit() {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      // Kill CS
      nw.getControlServers().forEach(MockControlServer::close);
      nw.getPathServices().forEach(MockPathService::close);
      Exception ex = assertThrows(ScionRuntimeException.class, Scion::defaultService);
      assertTrue(ex.getMessage().startsWith("Path services unreachable:"), ex.getMessage());
    }
  }

  @Test
  void testPathServiceFailure_NoPsDuringPathQuery() {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      long dstIA = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
      ScionService client = Scion.defaultService();

      // Kill CS
      nw.getControlServers().forEach(MockControlServer::close);
      nw.getPathServices().forEach(MockPathService::close);

      Exception ex =
          assertThrows(ScionRuntimeException.class, () -> client.getPaths(dstIA, dstAddress));
      String expected = "Error while connecting to SCION network, no path service available";
      assertTrue(ex.getMessage().startsWith(expected), ex.getMessage());
    }
  }

  @Test
  void testPathServiceMissing_Backup() {
    // Test success if one CS is down.
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      // Kill PS #1
      nw.getPathServices().get(0).close();
      checkPathService();
    }

    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      // Kill CS #2
      nw.getPathServices().get(1).close();
      checkPathService();
    }
  }

  private void checkPathService() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:111");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    // First path service does not exist, but we should automatically switch to the backup.
    ScionService client = Scion.defaultService();
    Path path = client.getPaths(dstIA, dstAddress).get(0);
    assertNotNull(path);
  }

  @Test
  void testPathServiceWorksThenFails_Backup() {
    for (NanoHTTPD.Response.Status error :
        new NanoHTTPD.Response.Status[] {
          NanoHTTPD.Response.Status.BAD_REQUEST,
          NanoHTTPD.Response.Status.INTERNAL_ERROR,
          NanoHTTPD.Response.Status.UNAUTHORIZED,
          NanoHTTPD.Response.Status.FORBIDDEN,
          NanoHTTPD.Response.Status.NOT_FOUND
        }) {
      // Test success if 1st CS reports errors during runtime
      try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
        long dstIA = ScionUtil.parseIA("1-ff00:0:111");
        InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
        ScionService client = Scion.defaultService();
        nw.getPathServices().get(0).getAndResetCallCount(); // reset
        Path path = client.getPaths(dstIA, dstAddress).get(0);
        assertNotNull(path);
        assertEquals(1, nw.getPathServices().get(0).getAndResetCallCount());

        // Kill CS #1
        nw.getPathServices().get(0).reportError(error);

        // try again
        // Where does path with BR-2 come from ????
        Path path2 = client.getPaths(dstIA, dstAddress).get(0);
        assertNotNull(path2);
        assertEquals(1, nw.getPathServices().get(0).getAndResetCallCount()); // error
        assertEquals(1, nw.getPathServices().get(1).getAndResetCallCount());

        ScionService.closeDefault();
      }
    }
  }

  @Test
  void testPathServiceFailure_PrimaryWorksThenFails() {
    // CS works, then fails, then 2nd CS fails, then work again.
    // This tests that failed CS services are retried if all services fail
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      long dstIA110 = ScionUtil.parseIA("1-ff00:0:110");
      long dstIA111 = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
      // First border router does not exist, but we should automatically switch to the backup.
      ScionService client = Scion.defaultService();
      Path path = client.getPaths(dstIA110, dstAddress).get(0);
      assertNotNull(path);

      // Kill CS - ask for different AS do avoid getting a cached path or similar.
      nw.getPathServices().get(0).reportError(NanoHTTPD.Response.Status.NOT_FOUND);
      // Should still works
      client.getPaths(dstIA111, dstAddress);
      // Kill 2nd CS (have both report errors)
      nw.getPathServices().get(0).reportError(NanoHTTPD.Response.Status.NOT_FOUND);
      nw.getPathServices().get(1).reportError(NanoHTTPD.Response.Status.NOT_FOUND);
      Exception ex =
          assertThrows(ScionRuntimeException.class, () -> client.getPaths(dstIA111, dstAddress));
      String expected = "Error while connecting to SCION network, no path service available";
      assertTrue(ex.getMessage().startsWith(expected));

      // Re-enable CS
      nw.getPathServices().get(0).reportError(null);
      nw.getPathServices().get(1).reportError(null);
      Path path2 = client.getPaths(dstIA111, dstAddress).get(0);
      assertNotNull(path2);
    }
  }

  @Test
  void testErrorInvalidRequest() {
    // Test success if 1st PS reports errors during runtime
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      long dstIA = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

      ScionService client = Scion.defaultService();

      // ingest error
      nw.getPathServices().get(0).reportError(NanoHTTPD.Response.Status.BAD_REQUEST);
      Exception ex =
          assertThrows(ScionRuntimeException.class, () -> client.getPaths(dstIA, dstAddress));
      assertTrue(ex.getMessage().contains("invalid request"));
    }
  }

  @Disabled // To be implemented once we get TRCs from the new endhost API
  @Test
  void testErrorTRC() {
    // Test success if 1st CS reports errors during runtime
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      long dstIA = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

      NanoHTTPD.Response.Status status =
          NanoHTTPD.Response.Status.NOT_FOUND; // TODO .withDescription("TRC not found");
      ScionService client = Scion.defaultService();

      // ingest error
      nw.getPathServices().get(0).reportError(status);
      Exception ex =
          assertThrows(ScionRuntimeException.class, () -> client.getPaths(dstIA, dstAddress));
      assertTrue(ex.getMessage().contains("TRC not found"), ex.getMessage());
    }
  }
}
