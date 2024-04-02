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
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.demo.util.ToStringUtil;
import org.scion.testutil.MockDaemon;
import org.scion.testutil.MockNetwork;
import org.scion.testutil.MockTopologyServer;

public class ScionTest {

  private static final String SCION_HOST = "as110.test";
  private static final String SCION_TXT = "\"scion=1-ff00:0:110,127.0.0.1\"";
  private static final String TOPO_FILE = "topologies/scionproto-tiny-110.json";
  private static final int DEFAULT_PORT = MockDaemon.DEFAULT_PORT;

  @BeforeAll
  public static void beforeAll() {
    System.clearProperty(Constants.PROPERTY_DAEMON);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_HOST);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);

    Scion.closeDefault();
    System.setProperty(
        PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + "=" + SCION_TXT);
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

  @AfterEach
  public void afterEach() throws IOException {
    System.clearProperty(Constants.PROPERTY_DAEMON_HOST);
    System.clearProperty(Constants.PROPERTY_DAEMON_PORT);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_HOST);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    Scion.closeDefault();
    MockDaemon.closeDefault();
  }

  @Test
  void defaultService_daemon() throws IOException {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

    MockDaemon.createAndStartDefault();
    System.setProperty(Constants.PROPERTY_DAEMON_HOST, "[::1]");
    System.setProperty(Constants.PROPERTY_DAEMON_PORT, String.valueOf(DEFAULT_PORT));
    try {
      ScionService service = Scion.defaultService();
      RequestPath path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      // local AS + path
      assertEquals(2, MockDaemon.getAndResetCallCount());
    } finally {
      Scion.closeDefault();
      MockDaemon.closeDefault();
    }
  }

  @Test
  void defaultService_topoFile() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    try {
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
      ScionService service = Scion.defaultService();
      RequestPath path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapAddress() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    try {
      String host;
      MockTopologyServer mts = MockNetwork.getTopoServer();
      if (mts.getAddress().getAddress() instanceof Inet6Address) {
        host = "[" + mts.getAddress().getAddress().getHostAddress() + "]";
      } else {
        host = mts.getAddress().getHostString();
      }
      host += ":" + mts.getAddress().getPort();

      System.setProperty(Constants.PROPERTY_BOOTSTRAP_HOST, host);
      ScionService service = Scion.defaultService();
      RequestPath path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapNaptrRecord() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try {
      ScionService service = Scion.defaultService();
      RequestPath path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapTopoFile() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    try {
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
      ScionService service = Scion.defaultService();
      RequestPath path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void newServiceWithDNS() throws IOException {
    long iaDst = ScionUtil.parseIA("1-ff00:0:112");
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(MockTopologyServer.TOPO_HOST)) {
      // destination address = 123.123.123.123 because we don´t care for getting a path
      List<RequestPath> paths = ss.getPaths(iaDst, new byte[] {123, 123, 123, 123}, 12345);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      assertEquals(1, MockNetwork.getTopoServer().getAndResetCallCount());
      assertEquals(1, MockNetwork.getControlServer().getAndResetCallCount());
      assertNotEquals(Scion.defaultService(), ss);
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void newServiceWithBootstrapServer() throws IOException {
    long iaDst = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress topoAddr = MockNetwork.getTopoServer().getAddress();
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try (Scion.CloseableService ss =
        Scion.newServiceWithBootstrapServer(ToStringUtil.toAddressPort(topoAddr))) {
      // destination address = 123.123.123.123 because we don´t care for getting a path
      List<RequestPath> paths = ss.getPaths(iaDst, new byte[] {123, 123, 123, 123}, 12345);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      assertEquals(1, MockNetwork.getTopoServer().getAndResetCallCount());
      assertEquals(1, MockNetwork.getControlServer().getAndResetCallCount());
      assertNotEquals(Scion.defaultService(), ss);
    } finally {
      MockNetwork.stopTiny();
    }
  }
}
