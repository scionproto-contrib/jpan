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

package org.scion.jpan.selectors;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.scion.jpan.*;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.testutil.*;

class PathSelectorWithRefreshTest {

  private static final String TOPO_FILE = MockBootstrapServer.TOPO_TINY_110 + "topology.json";
  private static final InetSocketAddress dummyAddress;

  static {
    dummyAddress = new InetSocketAddress(IPHelper.toInetAddress("myServer", "127.0.0.1"), 12345);
  }

  private PathSelectorWithRefresh pp = null;

  @BeforeEach
  void beforeEach() {
    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
    MockDNS.install("1-ff00:0:110", dummyAddress.getAddress());
  }

  @AfterEach
  void afterEach() {
    if (pp != null) {
      pp.disconnect();
      pp = null;
    }
    MockNetwork.stopTiny();
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    System.clearProperty(Constants.ENV_PATH_POLLING_INTERVAL_SEC);
    assertEquals(0, PathSelectorWithRefresh.getQueueSize());
  }

  @Test
  void autoRefresh() {
    ScionService service = Scion.defaultService();
    pp = PathSelectorWithRefresh.create(service, PathPolicy.DEFAULT, 0, 1000);

    InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    ScionSocketAddress remote =
        PackageVisibilityHelper.toSSA(MockNetwork.TINY_SRV_ISD_AS, dummyAddr);
    try {
      Path newPath =
          service.getPaths(ScionUtil.parseIA(MockNetwork.TINY_SRV_ISD_AS), dummyAddr).get(0);
      MockNetwork.getControlServer().getAndResetCallCount();

      // Expire in one second from now
      Path expiredPath = PackageVisibilityHelper.createExpiredPath(newPath, -1);
      // Confirm difference in expiredPath/newPath
      assertNotEquals(
          expiredPath.getMetadata().getExpiration(), newPath.getMetadata().getExpiration());

      // Initial connect
      pp.connect(remote);

      // Inject an expired path
      AtomicBoolean returnExpired = new AtomicBoolean(true);
      PathPolicy ppExp = x -> returnExpired.get() ? Collections.singletonList(expiredPath) : x;
      pp.setPathPolicy(ppExp);
      pp.refresh();

      // No calls done in connect() or setPathPolicy()
      assertEquals(4, MockNetwork.getControlServer().getAndResetCallCount());
      // Path is now the expired path
      assertEquals(
          expiredPath.getMetadata().getExpiration(), pp.getPath().getMetadata().getExpiration());

      // Allow policy to return proper paths, then wait for it to be called again.
      returnExpired.set(false);

      // Wait for timer
      TestUtil.sleep(1500);
      assertEquals(2, MockNetwork.getControlServer().getAndResetCallCount());
      assertEquals(
          newPath.getMetadata().getExpiration(), pp.getPath().getMetadata().getExpiration());
    } finally {
      pp.disconnect();
    }
  }

  /** Test that the PathSelector can handle paths with different local ISDs. */
  @Test
  void multiISD() {
    // Stop default network
    MockNetwork.stopTiny();
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);

    // Use multi-isd setup
    try (MockNetwork2 nw =
        MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_111", "ASff00_0_112")) {
      ScionService service = Scion.defaultService();
      pp = PathSelectorWithRefresh.create(service, PathPolicy.DEFAULT);

      InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddr);
      List<Path> paths = service.getPaths(remote);
      try {
        Path newPath0 = paths.get(0);
        Path newPath1 = paths.get(1);
        assertEquals(ScionUtil.parseIA("1-ff00:0:111"), newPath0.getLocalIsdAs());
        assertEquals(ScionUtil.parseIA("1-ff00:0:112"), newPath1.getLocalIsdAs());
        assertNotEquals(newPath0, newPath1);

        // Initial connect
        pp.connect(remote);

        pp.reportError(createError5(newPath0, 1));
        assertEquals(newPath1, pp.getPath());

        pp.reportError(createError5(newPath1, 1));
        assertEquals(newPath0, pp.getPath());
      } finally {
        pp.disconnect();
      }
    }
  }

  @Test
  void connect_noPath() {
    // Test that the selector does not loop when no path is found.
    ScionService service = Scion.defaultService();
    pp = PathSelectorWithRefresh.create(service, PathPolicy.DEFAULT);

    ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddress);

    // Create empty path policy
    PathPolicy empty = paths1 -> Collections.emptyList();
    pp.setPathPolicy(empty);

    // Trigger PathSelector
    pp.connect(remote);
    assertNull(pp.getPath());
  }

  @Test
  void setPathPolicy_failsIfNoPath() {
    ScionService service = Scion.defaultService();
    pp = PathSelectorWithRefresh.create(service, PathPolicy.DEFAULT);

    ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddress);
    pp.connect(remote);

    // Create empty path policy
    PathPolicy empty = paths1 -> Collections.emptyList();
    Path p = pp.getPath();
    pp.setPathPolicy(empty);
    // Nothing changes
    assertEquals(p, pp.getPath());

    // Now path should be removed
    pp.refresh();
    assertNull(pp.getPath());
  }

  @Test
  void setPathPolicy_countRefresh() {
    // Test that the selector does not loop when no path is found.
    ScionService service = Scion.defaultService();
    pp = PathSelectorWithRefresh.create(service, PathPolicy.DEFAULT);

    ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddress);
    pp.connect(remote);
    RefreshCounter refreshCounter = new RefreshCounter();
    pp.setPathPolicy(refreshCounter);
    assertEquals(0, refreshCounter.count.get());
    pp.refresh();
    assertEquals(1, refreshCounter.count.get());
  }

  private static class RefreshCounter implements PathPolicy {
    final AtomicInteger count = new AtomicInteger();

    @Override
    public List<Path> filter(List<Path> paths) {
      count.incrementAndGet();
      return paths;
    }
  }

  @Test
  void reportError_noChange() {
    MockNetwork.stopTiny();
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.DEFAULT, "ASff00_0_112")) {
      ScionService service = Scion.defaultService();
      pp = PathSelectorWithRefresh.create(service, PathPolicy.DEFAULT);
      InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddr);
      List<Path> paths = service.getPaths(remote);
      // reset counter
      assertEquals(2, nw.getControlServer().getAndResetCallCount());

      pp.connect(remote);
      assertEquals(paths.get(0), pp.getPath());

      // Replace path
      pp.reportError(Scmp.Error2Message.create(paths.get(0), 1200));
      // Assert that nothing changed and no error occurred
      assertEquals(paths.get(0), pp.getPath());

      // reset counter
      assertEquals(2, nw.getControlServer().getAndResetCallCount());
    }
  }

  @Test
  void reportError5() {
    MockNetwork.stopTiny();
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.DEFAULT, "ASff00_0_112")) {
      ScionService service = Scion.defaultService();
      pp = PathSelectorWithRefresh.create(service, PathPolicy.DEFAULT);
      InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddr);
      List<Path> paths = service.getPaths(remote);
      // reset counter
      assertEquals(2, nw.getControlServer().getAndResetCallCount());

      pp.connect(remote);
      assertEquals(paths.get(0), pp.getPath());

      // Replace path
      pp.reportError(createError5(paths.get(0)));
      assertNotEquals(paths.get(0), pp.getPath());
      assertEquals(paths.get(1), pp.getPath());

      // reset counter
      assertEquals(2, nw.getControlServer().getAndResetCallCount());

      // No change when reporting again
      pp.reportError(createError5(paths.get(0)));
      assertNotEquals(paths.get(0), pp.getPath());
      assertEquals(paths.get(1), pp.getPath());

      // Now reporting 2nd path
      pp.reportError(createError5(paths.get(1)));
      assertNotEquals(paths.get(0), pp.getPath());
      assertNotEquals(paths.get(1), pp.getPath());
      assertEquals(paths.get(2), pp.getPath());

      // Make sure that a faulty path remains considered "faulty" until a later time or if
      // no other paths are available.
      pp.refresh();
      assertNotEquals(paths.get(0), pp.getPath());
      assertNotEquals(paths.get(1), pp.getPath());
      assertEquals(paths.get(2), pp.getPath());

      assertEquals(2, nw.getControlServer().getAndResetCallCount());

      // Now report _all_ paths a faulty
      // This should cause a refresh that will put all paths back into business.
      for (Path p : paths) {
        pp.reportError(createError5(p));
      }
      assertEquals(paths.get(0), pp.getPath());
      assertEquals(2, nw.getControlServer().getAndResetCallCount());
    }
  }

  @Test
  void reportError6() {
    MockNetwork.stopTiny();
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.DEFAULT, "ASff00_0_112")) {
      ScionService service = Scion.defaultService();
      // THe normal path ordering is as follows:
      // 0: [494>103 104>5 6>1]
      // 1: [494>103 104>5 1>105 104>2]
      // 2: [494>103 104>5 2>501 503>450 453>3]
      // 3: [494>103 104>5 3>502 503>450 453>3]
      // To test Error 6, we reverse the path ordering by ordering them by maximum hops.
      // Then, we can test error 6 to remove the first two path at once.
      PathPolicy mostHops =
          paths ->
              paths.stream()
                  .sorted(Comparator.comparing(path -> -path.getMetadata().getInterfaces().size()))
                  .collect(Collectors.toList());
      pp = PathSelectorWithRefresh.create(service, mostHops);
      InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddr);
      List<Path> paths = mostHops.filter(service.getPaths(remote));
      // reset counter
      assertEquals(2, nw.getControlServer().getAndResetCallCount());

      pp.connect(remote);
      assertEquals(paths.get(0), pp.getPath());

      // Replace path
      pp.reportError(createError6_7_8(paths.get(0)));
      assertNotEquals(paths.get(0), pp.getPath());
      assertEquals(paths.get(2), pp.getPath());

      // reset counter
      assertEquals(2, nw.getControlServer().getAndResetCallCount());

      // No change when reporting again
      pp.reportError(createError6_7_8(paths.get(0)));
      assertNotEquals(paths.get(0), pp.getPath());
      assertEquals(paths.get(2), pp.getPath());

      assertEquals(0, nw.getControlServer().getAndResetCallCount());

      // Now report _all_ paths a faulty
      // This should cause a refresh that will put all paths back into business.
      for (Path p : paths) {
        pp.reportError(createError5(p));
      }
      assertNotNull(pp.getPath());
      assertEquals(2, nw.getControlServer().getAndResetCallCount());
    }
  }

  private Scmp.Error5Message createError5(Path errorPath) {
    return createError5(errorPath, 5);
  }

  private Scmp.Error5Message createError5(Path errorPath, int interfaceId) {
    // All paths use a different ingress interface here.
    PathMetadata.PathInterface pif = errorPath.getMetadata().getInterfaces().get(interfaceId);
    return Scmp.Error5Message.create(errorPath, pif.getIsdAs(), pif.getId());
  }

  private Scmp.Error6Message createError6_7_8(Path errorPath) {
    // interfaces 7 and 8 are unique/common to the first two paths.
    PathMetadata.PathInterface pifIn = errorPath.getMetadata().getInterfaces().get(7);
    PathMetadata.PathInterface pifEg = errorPath.getMetadata().getInterfaces().get(8);
    assertEquals(pifIn.getIsdAs(), pifEg.getIsdAs());
    return Scmp.Error6Message.create(errorPath, pifIn.getIsdAs(), pifIn.getId(), pifEg.getId());
  }
}
