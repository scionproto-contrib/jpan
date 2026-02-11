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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.internal.AddressLookupService;
import org.scion.jpan.testutil.MockBootstrapServer;
import org.scion.jpan.testutil.MockDaemon;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockNetwork2;
import org.scion.jpan.testutil.MockPathService;
import org.scion.jpan.testutil.TestUtil;

class ScionTest {

  private static final String SCION_HOST = "as110.test";
  private static final String SCION_TXT = "\"scion=1-ff00:0:110,127.0.0.1\"";
  private static final String TOPO_FILE = "topologies/tiny4/ASff00_0_110/topology.json";
  private static final int DEFAULT_PORT = MockDaemon.DEFAULT_PORT;

  @BeforeAll
  static void beforeAll() {
    System.clearProperty(Constants.PROPERTY_DAEMON);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_HOST);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);

    Scion.closeDefault();
    System.setProperty(
        PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + "=" + SCION_TXT);
  }

  @AfterAll
  static void afterAll() {
    System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
    // Defensive clean up
    ScionService.closeDefault();
  }

  @BeforeEach
  void beforeEach() {
    // reset counter
    MockDaemon.getAndResetCallCount();
    MockNetwork.stopTiny();
  }

  @AfterEach
  void afterEach() throws IOException {
    System.clearProperty(Constants.PROPERTY_DAEMON);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_HOST);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_PATH_SERVICE);
    Scion.closeDefault();
    MockDaemon.closeDefault();
  }

  @Test
  void defaultService_pathService() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:110");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

    // Start daemon just to ensure we are not using it
    MockDaemon.createAndStartDefault();

    System.setProperty(
        Constants.PROPERTY_BOOTSTRAP_PATH_SERVICE, "[::1]:" + MockPathService.DEFAULT_PORT);
    System.setProperty(Constants.PROPERTY_DAEMON, "[::1]:" + DEFAULT_PORT);
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4B, "ASff00_0_112")) {
      // Remove TOPO property that is installed by MockNetwork2
      System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
      ScionService service = Scion.defaultService();
      assertEquals(1, nw.getPathService().getAndResetCallCount());
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);

      // TODO also test with daemon available! E.g. start MockDaemon directly?
      //    TODO remove, MockDaemon is anyway not called...
      assertEquals(0, MockDaemon.getAndResetCallCount());
      assertEquals(0, nw.getControlServer().getAndResetCallCount());
      assertEquals(1, nw.getPathService().getAndResetCallCount());
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }

  @Test
  void defaultService_daemon() throws IOException {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

    MockDaemon.createAndStartDefault();
    System.setProperty(Constants.PROPERTY_DAEMON, "[::1]:" + DEFAULT_PORT);
    try {
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      // service init + path
      assertEquals(MockNetwork.SERVICE_TO_DAEMON_INIT_CALLS + 1, MockDaemon.getAndResetCallCount());
    } finally {
      Scion.closeDefault();
      MockDaemon.closeDefault();
    }
  }

  @Test
  void defaultService_daemon_error_address() throws IOException {
    MockDaemon.createAndStartDefault();
    System.setProperty(Constants.PROPERTY_DAEMON, "127.0.0.234:123");
    try {
      Exception e = assertThrows(Exception.class, Scion::defaultService);
      boolean okay = false;
      while (e != null) {
        if (e.getMessage().contains("127.0.0.234")) {
          okay = true;
          break;
        }
        e = (Exception) e.getCause();
      }
      assertTrue(okay);
    } finally {
      Scion.closeDefault();
      MockDaemon.closeDefault();
    }
  }

  @Test
  void defaultService_daemon_error_timeout() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

    MockNetwork.startTiny(MockNetwork.Mode.DAEMON);
    System.setProperty(Constants.PROPERTY_CONTROL_PLANE_TIMEOUT_MS, "10");
    try {
      MockDaemon.block();
      try {
        Scion.defaultService();
      } catch (ScionRuntimeException e) {
        assertTrue(e.getCause().getMessage().contains("DEADLINE_EXCEEDED"));
      }
      MockDaemon.unblock();

      // try again
      System.setProperty(Constants.PROPERTY_CONTROL_PLANE_TIMEOUT_MS, "200");
      ScionService service = Scion.defaultService();
      MockDaemon.block();
      try {
        service.getPaths(dstIA, dstAddress);
        fail();
      } catch (ScionRuntimeException e) {
        assertTrue(e.getCause().getMessage().contains("DEADLINE_EXCEEDED"));
      }
      MockDaemon.unblock();
    } finally {
      Scion.closeDefault();
      MockNetwork.stopTiny();
      System.clearProperty(Constants.PROPERTY_CONTROL_PLANE_TIMEOUT_MS);
    }
  }

  @Test
  void defaultService_topoFile_error_timeout() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
    System.setProperty(Constants.PROPERTY_CONTROL_PLANE_TIMEOUT_MS, "10");
    try {
      ScionService service = Scion.defaultService();

      MockNetwork.getControlServer().block();
      try {
        service.getPaths(dstIA, dstAddress);
        fail();
      } catch (ScionRuntimeException e) {
        assertTrue(e.getMessage().contains("DEADLINE_EXCEEDED"));
      }
      MockNetwork.getControlServer().unblock();
    } finally {
      Scion.closeDefault();
      MockNetwork.stopTiny();
      System.clearProperty(Constants.PROPERTY_CONTROL_PLANE_TIMEOUT_MS);
    }
  }

  @Test
  void defaultService_topoFile() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    try {
      MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
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
    try {
      MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
      InetSocketAddress discoveryAddress = MockNetwork.getTopoServer().getAddress();
      String host = TestUtil.toString(discoveryAddress.getAddress());
      host += ":" + discoveryAddress.getPort();

      System.setProperty(Constants.PROPERTY_BOOTSTRAP_HOST, host);
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapAddress_defaultPort() {
    try {
      MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
      InetSocketAddress discoveryAddress = MockNetwork.getTopoServer().getAddress();
      String host = TestUtil.toString(discoveryAddress.getAddress());
      // We do _not_ add a port here.

      System.setProperty(Constants.PROPERTY_BOOTSTRAP_HOST, host);
      Throwable t = assertThrows(ScionRuntimeException.class, Scion::defaultService);
      // Check that the default port 8041 was added
      assertTrue(t.getMessage().contains(host + ":8041"));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapNaptrRecord() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    try {
      MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
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
    try {
      MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_withoutDiscovery() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    try {
      MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/no-discovery.json");
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_dispatcherPortRange_ignoredByExplicitPort()
      throws IOException {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("localhost", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
    System.setProperty(
        Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/dispatcher-port-range.json");
    ScionService service = Scion.defaultService();
    Path path = service.getPaths(dstIA, dstAddress).get(0);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.bind(new InetSocketAddress(12321));
      channel.connect(path);
      assertEquals(12321, channel.getLocalAddress().getPort());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_dispatcherPortRange() throws IOException {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("localhost", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
    System.setProperty(
        Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/dispatcher-port-range.json");
    ScionService service = Scion.defaultService();
    Path path = service.getPaths(dstIA, dstAddress).get(0);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(path);
      assertEquals(31000, channel.getLocalAddress().getPort());

      try (ScionDatagramChannel channel2 = ScionDatagramChannel.open()) {
        channel2.connect(path);
        assertEquals(31001, channel2.getLocalAddress().getPort());
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_dispatcherPortRange_EMPTY() throws IOException {
    testDefaultService_bootstrapTopoFile_dispatcherPortRange(
        "topologies/dispatcher-port-range-empty.json");
  }

  @Test
  void defaultService_bootstrapTopoFile_dispatcherPortRange_NONE() throws IOException {
    testDefaultService_bootstrapTopoFile_dispatcherPortRange(
        "topologies/dispatcher-port-range-none.json");
  }

  private void testDefaultService_bootstrapTopoFile_dispatcherPortRange(String topoFile)
      throws IOException {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("localhost", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, topoFile);
    ScionService service = Scion.defaultService();
    Path path = service.getPaths(dstIA, dstAddress).get(0);
    // stop here to free up port 30041 for the SHIM
    MockNetwork.stopTiny();
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(path);
      // use ephemeral port
      assertTrue(
          32768 <= channel.getLocalAddress().getPort(),
          "port=" + channel.getLocalAddress().getPort());

      try (ScionDatagramChannel channel2 = ScionDatagramChannel.open()) {
        channel2.connect(path);
        // use ephemeral port
        assertTrue(
            32768 <= channel2.getLocalAddress().getPort(),
            "port=" + channel2.getLocalAddress().getPort());
        assertNotEquals(channel.getLocalAddress().getPort(), channel2.getLocalAddress().getPort());
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_dispatcherPortRange_ALL() throws IOException {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("localhost", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
    System.setProperty(
        Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/dispatcher-port-range-all.json");
    ScionService service = Scion.defaultService();
    Path path = service.getPaths(dstIA, dstAddress).get(0);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(path);
      // just any port
      assertNotNull(channel.getLocalAddress());

      try (ScionDatagramChannel channel2 = ScionDatagramChannel.open()) {
        channel2.connect(path);
        assertNotNull(channel2.getLocalAddress());
        assertNotEquals(channel.getLocalAddress().getPort(), channel2.getLocalAddress().getPort());
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_dispatcherPortRange_Illegal() {
    System.setProperty(
        Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/dispatcher-port-range-bad.json");
    Throwable t = assertThrows(Throwable.class, Scion::defaultService);
    assertTrue(t.getMessage().contains("Illegal port values in topo file"));
  }

  @Test
  void defaultService_bootstrapTopoFile_IOError_NoSuchFile() {
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE + ".x");
    try {
      Scion.defaultService();
      fail("This should cause an IOException because the file doesn't exist");
    } catch (Exception e) {
      assertNotNull(e.getCause());
      assertInstanceOf(NoSuchFileException.class, e.getCause());
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_IOError_FilePermissionError()
      throws URISyntaxException, IOException {
    java.nio.file.Path path = getPath(TOPO_FILE);
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
    try {
      AclFileAttributeView aclAttr = Files.getFileAttributeView(path, AclFileAttributeView.class);
      if (aclAttr != null) {
        // Yay, ACLs are supported on this machine
        defaultService_bootstrapTopoFile_IOError_FilePermissionError_ACL(aclAttr);
      } else {
        // Try POSIX
        defaultService_bootstrapTopoFile_IOError_FilePermissionError_POSIX(path);
      }
    } finally {
      System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    }
  }

  private void defaultService_bootstrapTopoFile_IOError_FilePermissionError_ACL(
      AclFileAttributeView aclAttr) throws IOException {
    List<AclEntry> oldAttributes = aclAttr.getAcl();
    try {
      aclAttr.setAcl(Collections.emptyList());
      Scion.defaultService();
      fail("This should cause an IOException because the file doesn't exist");
    } catch (Exception e) {
      assertNotNull(e.getCause());
      assertInstanceOf(AccessDeniedException.class, e.getCause());
    } finally {
      aclAttr.setAcl(oldAttributes);
    }
  }

  private void defaultService_bootstrapTopoFile_IOError_FilePermissionError_POSIX(
      java.nio.file.Path path) throws IOException {
    Set<PosixFilePermission> oldAttributes = Files.getPosixFilePermissions(path);
    try {
      Files.setPosixFilePermissions(path, new HashSet<>());
      Scion.defaultService();
      fail("This should cause an IOException because the file doesn't exist");
    } catch (Exception e) {
      assertNotNull(e.getCause());
      assertInstanceOf(AccessDeniedException.class, e.getCause());
    } finally {
      Files.setPosixFilePermissions(path, oldAttributes);
    }
  }

  @Test
  void defaultService_bootstrapTopoFile_IOError() throws URISyntaxException {
    if (!isWindows()) {
      // File locking only works on windows
      return;
    }
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
    java.nio.file.Path file = getPath(TOPO_FILE);
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
        FileChannel channel = raf.getChannel()) {
      assertNotNull(channel.lock());
      // Attempt opening the file -> should fail
      Scion.defaultService();
      fail("This should cause an IOException because the file is locked");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("locked"));
    } finally {
      System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    }
  }

  @Test
  void defaultService_etcHostsFile_IO_error() throws URISyntaxException {
    if (!isWindows()) {
      // File locking only works on windows
      return;
    }
    java.nio.file.Path file = getPath("etc-scion-hosts");
    System.setProperty(Constants.PROPERTY_HOSTS_FILES, file.toString());
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
        FileChannel channel = raf.getChannel()) {
      assertNotNull(channel.lock());
      // Attempt opening the file -> should fail
      // Normally that happens in ScionService.defaultService() but this has been called already
      // in other tests.
      AddressLookupService.refresh();
      fail("This should cause an IOException because the file is locked");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("locked"), e.getMessage());
    } finally {
      System.clearProperty(Constants.PROPERTY_HOSTS_FILES);
    }
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name");
    return os.startsWith("Windows");
  }

  private java.nio.file.Path getPath(String fileName) throws URISyntaxException {
    URL resource = getClass().getClassLoader().getResource(fileName);
    assertNotNull(resource);
    return Paths.get(resource.toURI());
  }

  @Test
  void newServiceWithDNS() throws IOException {
    long iaDst = ScionUtil.parseIA("1-ff00:0:112");
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(MockBootstrapServer.TOPO_HOST)) {
      // destination address = 123.123.123.123 because we don´t care for getting a path
      InetAddress ip123 = InetAddress.getByAddress(new byte[] {123, 123, 123, 123});
      List<Path> paths = ss.getPaths(iaDst, ip123, 12345);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      assertEquals(1, MockNetwork.getTopoServer().getAndResetCallCount());
      assertNotEquals(Scion.defaultService(), ss);
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void newServiceWithBootstrapServer() throws IOException {
    long iaDst = ScionUtil.parseIA("1-ff00:0:112");
    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    InetSocketAddress topoAddr = MockNetwork.getTopoServer().getAddress();
    try (Scion.CloseableService ss =
        Scion.newServiceWithBootstrapServer(TestUtil.toAddressPort(topoAddr))) {
      // destination address = 123.123.123.123 because we don´t care for getting a path
      InetAddress ip123 = InetAddress.getByAddress(new byte[] {123, 123, 123, 123});
      List<Path> paths = ss.getPaths(iaDst, ip123, 12345);
      assertNotNull(paths);
      assertFalse(paths.isEmpty());
      assertEquals(1, MockNetwork.getTopoServer().getAndResetCallCount());
      // No DNS, no daemon, no ENV variables -> fail.
      assertThrows(ScionRuntimeException.class, Scion::defaultService);
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void newServiceWithTopoFile() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
    String topofile = "topologies/tiny4/ASff00_0_110/topology.json";
    try (Scion.CloseableService service = Scion.newServiceWithTopologyFile(topofile)) {
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }

  /** See Issue #72: <a href="https://github.com/scionproto-contrib/jpan/issues/72">...</a> */
  @Test
  void defaultService_bootstrapTopoFile_ScionProto_0_10() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    try {
      MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
      System.setProperty(
          Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/topology-scionproto-0.10.json");
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }

  /** See Issue #72: <a href="https://github.com/scionproto-contrib/jpan/issues/72">...</a> */
  @Test
  void defaultService_bootstrapTopoFile_ScionProto_0_11() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    try {
      System.setProperty(
          Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/topology-scionproto-0.11.json");
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }
}
