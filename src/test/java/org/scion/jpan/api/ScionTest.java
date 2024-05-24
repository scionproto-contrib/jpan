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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.util.ToStringUtil;
import org.scion.jpan.testutil.MockDaemon;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockTopologyServer;

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
    System.clearProperty(Constants.PROPERTY_DAEMON);
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
    System.setProperty(Constants.PROPERTY_DAEMON, "[::1]:" + DEFAULT_PORT);
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
  void defaultService_bootstrapTopoFile_IOError_NoSuchFile() {
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE + ".x");
    try {
      Scion.defaultService();
      fail("This should cause an IOException because the file doesn't exist");
    } catch (Exception e) {
      assertTrue(e.getCause() instanceof NoSuchFileException);
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_IOError_FilePermissionError() throws URISyntaxException, IOException {
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
    URL resource = getClass().getClassLoader().getResource(TOPO_FILE);
    java.nio.file.Path path =  Paths.get(resource.toURI());
    AclFileAttributeView aclAttr = Files.getFileAttributeView(path, AclFileAttributeView.class);
    List<AclEntry> oldAttributes = aclAttr.getAcl();
    try {
      aclAttr.setAcl(Collections.EMPTY_LIST);
      Scion.defaultService();
      fail("This should cause an IOException because the file doesn't exist");
    } catch (Exception e) {
      assertTrue(e.getCause() instanceof AccessDeniedException);
    } finally {
      aclAttr.setAcl(oldAttributes);
      System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_IOError() throws URISyntaxException {
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
    URL resource = getClass().getClassLoader().getResource(TOPO_FILE);
    File file = Paths.get(resource.toURI()).toFile();
    try (FileChannel channel = new RandomAccessFile(file, "rw").getChannel()) {
      channel.lock();
      // Attempt opening the file -> should fail
      Scion.defaultService();
      fail("This should cause an IOException because the file is locked");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("locked"));
      System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    }
  }

  @Test
  void defaultService_etcHostsFile_IO_error() throws URISyntaxException {
    URL resource = getClass().getClassLoader().getResource("etc-scion-hosts");
    java.nio.file.Path file = Paths.get(resource.toURI());
    System.setProperty(Constants.PROPERTY_HOSTS_FILES, file.toString());
    try (FileChannel channel = new RandomAccessFile(file.toFile(), "rw").getChannel()) {
      channel.lock();
      // Attempt opening the file -> should fail
      Scion.defaultService();
      fail("This should cause an IOException because the file is locked");
    } catch (Exception e) {
      e.printStackTrace();
      assertTrue(e.getMessage().contains("locked"));
    } finally {
      System.clearProperty(Constants.PROPERTY_HOSTS_FILES);
    }
  }

  @Test
  void newServiceWithDNS() throws IOException {
    long iaDst = ScionUtil.parseIA("1-ff00:0:112");
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(MockTopologyServer.TOPO_HOST)) {
      // destination address = 123.123.123.123 because we don´t care for getting a path
      InetAddress ip123 = InetAddress.getByAddress(new byte[] {123, 123, 123, 123});
      List<RequestPath> paths = ss.getPaths(iaDst, ip123, 12345);
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
      InetAddress ip123 = InetAddress.getByAddress(new byte[] {123, 123, 123, 123});
      List<RequestPath> paths = ss.getPaths(iaDst, ip123, 12345);
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
