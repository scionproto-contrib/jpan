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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockBootstrapServer;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockNetwork2;

class PathProviderWithRenameTest {

  private static final String TOPO_FILE = MockBootstrapServer.TOPO_TINY_110 + "topology.json";
  private PathProviderWithRefresh pp = null;

  @BeforeEach
  void beforeEach() {
    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
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
    assertEquals(0, PathProviderWithRefresh.getQueueSize());
  }

  @Test
  void autoRefresh() {
    ScionService service = Scion.defaultService();
    pp = PathProviderWithRefresh.create(service, PathPolicy.DEFAULT, 10, 50);

    try {
      InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      Path newPath =
          service.getPaths(ScionUtil.parseIA(MockNetwork.TINY_SRV_ISD_AS), dummyAddr).get(0);
      Path expiredPath = PackageVisibilityHelper.createExpiredPath(newPath, 100);
      SubscriberHelper subscriber = new SubscriberHelper(expiredPath);

      // Initial connect
      pp.subscribe(subscriber::callback);
      pp.connect(expiredPath);
      subscriber.await();

      // Reset and wait for timer thread
      MockNetwork.getControlServer().getAndResetCallCount();
      subscriber.subscribedPath.set(null);
      subscriber.barrier = new CountDownLatch(1);
      subscriber.await();
      // Wait for timer
      assertEquals(newPath, subscriber.subscribedPath.get());

      // Reset and wait for timer thread (again)
      MockNetwork.getControlServer().getAndResetCallCount();
      subscriber.subscribedPath.set(null);
      subscriber.barrier = new CountDownLatch(1);
      subscriber.await();
      // Wait for timer
      // TODO why compare to expire path?
      assertEquals(newPath, subscriber.subscribedPath.get());

      assertEquals(2, MockNetwork.getControlServer().getAndResetCallCount());
      assertNotSame(expiredPath, subscriber.subscribedPath.get());
    } finally {
      pp.disconnect();
    }
  }

  @Test
  void replaceExpired() {
    ScionService service = Scion.defaultService();
    pp = PathProviderWithRefresh.create(service, PathPolicy.DEFAULT, 10, 50);

    InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    Path p = service.getPaths(ScionUtil.parseIA(MockNetwork.TINY_SRV_ISD_AS), dummyAddr).get(0);
    p = PackageVisibilityHelper.createExpiredPath(p, 100);
    SubscriberHelper subscriber = new SubscriberHelper(p);
    // reset counter
    assertEquals(2, MockNetwork.getControlServer().getAndResetCallCount());

    pp.subscribe(subscriber::callback);
    pp.connect(p);
    subscriber.await();

    assertEquals(2, MockNetwork.getControlServer().getAndResetCallCount());
    assertNotSame(p, subscriber.subscribedPath.get());
  }

  @Test
  void connect_failsIfNoPath() throws IOException {
    // Test that the provider does not loop when no path is found.
    ScionService service = Scion.defaultService();
    pp = PathProviderWithRefresh.create(service, PathPolicy.DEFAULT, 10, 50);
    pp.subscribe(newPath -> {});

    List<Path> paths = Scion.defaultService().lookupPaths("127.0.0.1", 12345);

    // Create empty path policy
    PathPolicy empty = paths1 -> Collections.emptyList();
    pp.setPathPolicy(empty);

    // Create expired path to trigger PathProvider
    Path expired = PackageVisibilityHelper.createExpiredPath(paths.get(0), 10);
    Exception e = assertThrows(ScionRuntimeException.class, () -> pp.connect(expired));
    assertTrue(e.getMessage().startsWith("No path found to destination"));
  }

  @Test
  void setPathPolicy_failsIfNoPath() throws IOException {
    // Test that the provider does not loop when no path is found.
    ScionService service = Scion.defaultService();
    pp = PathProviderWithRefresh.create(service, PathPolicy.DEFAULT, 10, 50);
    pp.subscribe(newPath -> {});

    List<Path> paths = Scion.defaultService().lookupPaths("127.0.0.1", 12345);
    pp.connect(paths.get(0));

    // Create empty path policy
    PathPolicy empty = paths1 -> Collections.emptyList();
    Exception e = assertThrows(ScionRuntimeException.class, () -> pp.setPathPolicy(empty));
    assertTrue(e.getMessage().startsWith("No path found to destination"));
  }

  private static class SubscriberHelper {
    AtomicReference<Path> subscribedPath = new AtomicReference<>();
    CountDownLatch barrier = new CountDownLatch(1);

    public SubscriberHelper(Path path) {
      this.subscribedPath.set(path);
    }

    void callback(Path newPath) {
      subscribedPath.set(newPath);
      barrier.countDown();
    }

    void await() {
      try {
        assertTrue(barrier.await(2000, TimeUnit.MILLISECONDS));
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Test
  void reportFaultyPath() {
    MockNetwork.stopTiny();
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.DEFAULT, "ASff00_0_112")) {

      ScionService service = Scion.defaultService();
      pp = PathProviderWithRefresh.create(service, PathPolicy.DEFAULT, 10, 50);

      InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:110"), dummyAddr);

      // reset counter
      assertEquals(2, nw.getControlServer().getAndResetCallCount());

      SubscriberHelper subscriber = new SubscriberHelper(paths.get(0));
      pp.subscribe(subscriber::callback);

      // Use expired path to trigger fetching of paths from server
      pp.connect(PackageVisibilityHelper.createExpiredPath(paths.get(0), 10));
      subscriber.await();

      assertEquals(paths.get(0), subscriber.subscribedPath.get());

      // Replace path
      pp.reportFaultyPath(paths.get(0));
      assertNotEquals(paths.get(0), subscriber.subscribedPath.get());
      assertEquals(paths.get(1), subscriber.subscribedPath.get());

      // No change when reporting again
      pp.reportFaultyPath(paths.get(0));
      assertNotEquals(paths.get(0), subscriber.subscribedPath.get());
      assertEquals(paths.get(1), subscriber.subscribedPath.get());

      // Now reporting 2nd path
      pp.reportFaultyPath(paths.get(1));
      assertNotEquals(paths.get(0), subscriber.subscribedPath.get());
      assertNotEquals(paths.get(1), subscriber.subscribedPath.get());
      assertEquals(paths.get(2), subscriber.subscribedPath.get());

      assertEquals(2, nw.getControlServer().getAndResetCallCount());
    }
  }
}
