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

import io.grpc.Status;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockControlServer;
import org.scion.jpan.testutil.MockNetwork2;

class ControlServiceTest {

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  @Test
  void testControlServiceFailure_NoCS() {
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      // Kill CS
      nw.getControlServers().forEach(MockControlServer::close);
      long dstIA = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
      // First control service does not exist, but we should automatically switch to the backup.
      ScionService client = Scion.defaultService();
      Exception ex =
          assertThrows(ScionRuntimeException.class, () -> client.getPaths(dstIA, dstAddress));
      String expected = "Error while connecting to SCION network, no control service available";
      assertTrue(ex.getMessage().startsWith(expected));
    }
  }

  @Test
  void testControlServiceMissing_Backup() {
    // Test success if one CS is down.
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      // Kill CS #1
      nw.getControlServers().get(0).close();
      checkControlService();
    }

    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      // Kill CS #2
      nw.getControlServers().get(1).close();
      checkControlService();
    }
  }

  private void checkControlService() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:111");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    // First control service does not exist, but we should automatically switch to the backup.
    ScionService client = Scion.defaultService();
    Path path = client.getPaths(dstIA, dstAddress).get(0);
    assertNotNull(path);
  }

  @Test
  void testControlServiceWorksThenFails_Backup() {
    // Test success if 1st CS reports errors during runtime
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      long dstIA = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
      for (Status error :
          new Status[] {Status.DEADLINE_EXCEEDED, Status.UNAVAILABLE, Status.UNKNOWN}) {
        ScionService client = Scion.defaultService();
        nw.getControlServers().get(0).getAndResetCallCount(); // reset
        Path path = client.getPaths(dstIA, dstAddress).get(0);
        assertNotNull(path);
        assertEquals(3, nw.getControlServers().get(0).getAndResetCallCount());

        // Kill CS #1
        nw.getControlServers().get(0).reportError(error);

        // try again
        // Where does path with BR-2 come from ????
        Path path2 = client.getPaths(dstIA, dstAddress).get(0);
        assertNotNull(path2);
        assertEquals(1, nw.getControlServers().get(0).getAndResetCallCount()); // error
        assertEquals(3, nw.getControlServers().get(1).getAndResetCallCount());

        ScionService.closeDefault();
      }
    }
  }

  @Test
  void testControlServiceFailure_PrimaryWorksThenFails() {
    // CS works, then fails, then 2nd CS fails, then work again.
    // This tests that failed CS services are retried if all services fail
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      long dstIA110 = ScionUtil.parseIA("1-ff00:0:110");
      long dstIA111 = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
      // First border router does not exist, but we should automatically switch to the backup.
      ScionService client = Scion.defaultService();
      Path path = client.getPaths(dstIA110, dstAddress).get(0);
      assertNotNull(path);

      // Kill CS - ask for different AS do avoid getting a cached path or similar.
      nw.getControlServers().get(0).reportError(Status.UNAVAILABLE);
      // Should still works
      client.getPaths(dstIA111, dstAddress);
      // Kill 2nd CS (have both report errors)
      nw.getControlServers().get(0).reportError(Status.UNAVAILABLE);
      nw.getControlServers().get(1).reportError(Status.UNAVAILABLE);
      Exception ex =
          assertThrows(ScionRuntimeException.class, () -> client.getPaths(dstIA111, dstAddress));
      String expected = "Error while connecting to SCION network, no control service available";
      assertTrue(ex.getMessage().startsWith(expected));

      // Reenable CS
      nw.getControlServers().get(0).reportError(null);
      nw.getControlServers().get(1).reportError(null);
      Path path2 = client.getPaths(dstIA111, dstAddress).get(0);
      assertNotNull(path2);
    }
  }

  @Test
  void testErrorInvalidRequest() {
    // Test success if 1st CS reports errors during runtime
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      long dstIA = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

      Status status = Status.UNKNOWN.withDescription("invalid request");
      ScionService client = Scion.defaultService();

      // ingest error
      nw.getControlServers().get(0).reportError(status);
      Exception ex =
          assertThrows(ScionRuntimeException.class, () -> client.getPaths(dstIA, dstAddress));
      assertTrue(ex.getMessage().contains("invalid request"));
    }
  }

  @Test
  void testErrorTRC() {
    // Test success if 1st CS reports errors during runtime
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      long dstIA = ScionUtil.parseIA("1-ff00:0:111");
      InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

      Status status = Status.UNKNOWN.withDescription("TRC not found");
      ScionService client = Scion.defaultService();

      // ingest error
      nw.getControlServers().get(0).reportError(status);
      Exception ex =
          assertThrows(ScionRuntimeException.class, () -> client.getPaths(dstIA, dstAddress));
      assertTrue(ex.getMessage().contains("TRC not found"), ex.getMessage());
    }
  }
}
