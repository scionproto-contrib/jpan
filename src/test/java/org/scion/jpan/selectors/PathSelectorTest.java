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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.scion.jpan.*;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.testutil.MockBootstrapServer;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockNetwork2;

class PathSelectorTest {

  private static final String TOPO_FILE = MockBootstrapServer.TOPO_TINY_110 + "topology.json";
  private static final InetSocketAddress dummyAddress;

  static {
    InetAddress dummyIPv4 = IPHelper.toInetAddress("dummyHost", "127.0.0.1");
    dummyAddress = new InetSocketAddress(dummyIPv4, 44444);
  }

  private PathSelector pp = null;

  @BeforeEach
  void beforeEach() {
    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
    MockDNS.install("1-ff00:0:112", dummyAddress.getAddress());
  }

  @AfterEach
  void afterEach() {
    if (pp != null) {
      pp.disconnect();
      pp = null;
    }
    MockNetwork.stopTiny();
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
  }

  private enum Implementation {
    FIXED,
    WITH_REFRESH
  }

  private PathSelector create(Implementation impl) {
    switch (impl) {
      case FIXED:
        return PathSelectorFixed.create(PathPolicy.DEFAULT);
      case WITH_REFRESH:
        return PathSelectorWithRefresh.create(Scion.defaultService(), PathPolicy.DEFAULT, 10, 50);
      default:
        throw new IllegalArgumentException(impl.name());
    }
  }

  @ParameterizedTest
  @EnumSource(Implementation.class)
  void connect_noPath(Implementation impl) throws IOException {
    // Test that the provider does not loop when no path is found.
    pp = create(impl);

    List<Path> paths = Scion.defaultService().lookupPaths(dummyAddress);

    // Create empty path policy
    PathPolicy empty = paths1 -> Collections.emptyList();
    pp.setPathPolicy(empty);

    // Create expired path to trigger PathSelector
    Path expired = PackageVisibilityHelper.createExpiredPath(paths.get(0), 10);
    pp.connect(expired.getRemoteSocketAddress());
    assertNull(pp.getPath());
  }

  @ParameterizedTest
  @EnumSource(Implementation.class)
  void connect_failsIfConnected(Implementation impl) {
    pp = create(impl);

    ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddress);
    pp.connect(remote);
    Exception e = assertThrows(IllegalStateException.class, () -> pp.connect(remote));
    assertTrue(e.getMessage().contains("already connected"));
  }

  @ParameterizedTest
  @EnumSource(Implementation.class)
  void setPathPolicy_failsIfNoPath(Implementation impl) {
    pp = create(impl);

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

  @ParameterizedTest
  @EnumSource(Implementation.class)
  void reportFaultyPath(Implementation impl) {
    MockNetwork.stopTiny();
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.DEFAULT, "ASff00_0_112")) {
      ScionService service = Scion.defaultService();
      pp = create(impl);
      InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddr);
      List<Path> paths = service.getPaths(remote);
      Path p0 = paths.get(0);
      pp.connect(p0.getRemoteSocketAddress()); // Must use path.getRemote() for FixedSelector
      // reset counter
      nw.getControlServer().getAndResetCallCount();

      assertEquals(p0, pp.getPath());

      // Replace path
      pp.reportError(createError5(p0));
      assertNotEquals(p0, pp.getPath());
      assertEquals(0, nw.getControlServer().getAndResetCallCount());
    }
  }

  private Scmp.Error5Message createError5(Path errorPath) {
    // All paths use a different ingress interface here.
    PathMetadata.PathInterface pif = errorPath.getMetadata().getInterfaces().get(5);
    return Scmp.Error5Message.create(errorPath, pif.getIsdAs(), pif.getId());
  }
}
