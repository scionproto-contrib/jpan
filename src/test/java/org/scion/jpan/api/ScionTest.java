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
import org.scion.jpan.demo.util.ToStringUtil;
import org.scion.jpan.testutil.MockBootstrapServer;
import org.scion.jpan.testutil.MockDaemon;
import org.scion.jpan.testutil.MockNetwork;

public class ScionTest {

  private static final String SCION_HOST = "as110.test";
  private static final String SCION_TXT = "\"scion=1-ff00:0:110,127.0.0.1\"";
  private static final String TOPO_FILE = "topologies/scionproto-tiny/topology-110.json";
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
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      // local AS + path
      assertEquals(2, MockDaemon.getAndResetCallCount());
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
  void testIPv6_daemon_address_parsing() throws IOException {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);

    MockDaemon.createAndStartDefault();
    System.setProperty(Constants.PROPERTY_DAEMON, "[fd00:f00d:cafe::7f00:33]:31060");
    try {
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
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

    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    try {
      String host;
      MockBootstrapServer mts = MockNetwork.getTopoServer();
      if (mts.getAddress().getAddress() instanceof Inet6Address) {
        host = "[" + mts.getAddress().getAddress().getHostAddress() + "]";
      } else {
        host = mts.getAddress().getHostString();
      }
      host += ":" + mts.getAddress().getPort();

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
  void defaultService_bootstrapNaptrRecord() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.NAPTR);
    try {
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
    MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
    try {
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
      Scion.defaultService();
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
        Scion.newServiceWithBootstrapServer(ToStringUtil.toAddressPort(topoAddr))) {
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
  void newServiceWithTopoFile() throws IOException {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
    String topofile = "topologies/scionproto-tiny/topology-110.json";
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
  void defaultService_bootstrapTopoFile_ScionProto_11() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dstAddress = new InetSocketAddress("::1", 12345);
    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    try {
      System.setProperty(
          Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
          "topologies/scionproto-tiny/topology-scionproto-0.11.json");
      ScionService service = Scion.defaultService();
      Path path = service.getPaths(dstIA, dstAddress).get(0);
      assertNotNull(path);
      assertEquals(0, MockDaemon.getAndResetCallCount()); // Daemon is not used!
    } finally {
      MockNetwork.stopTiny();
    }
  }
}
