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
import java.util.function.BiConsumer;
import org.junit.jupiter.api.*;
import org.scion.jpan.*;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.testutil.MockBootstrapServer;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockNetwork2;

class PathSelectorFixedTest {

  private static final String TOPO_FILE = MockBootstrapServer.TOPO_TINY_110 + "topology.json";
  private PathSelectorFixed pp = null;
  private InetSocketAddress someAddress;

  @BeforeEach
  void beforeEach() {
    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
    someAddress = new InetSocketAddress(IPHelper.toInetAddress("myHost", "127.0.0.1"), 12345);
    MockDNS.install("1-ff00:0:110", someAddress.getAddress());
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

  @Test
  void connect_noPath() throws IOException {
    // Test that the provider does not loop when no path is found.
    pp = PathSelectorFixed.create(PathPolicy.DEFAULT);

    List<Path> paths = Scion.defaultService().lookupPaths(someAddress);

    // Create empty path policy
    PathPolicy empty = paths1 -> Collections.emptyList();
    pp.setPathPolicy(empty);

    // Create expired path to trigger PathSelector
    pp.connect(paths.get(0).getRemoteSocketAddress());
    assertNull(pp.getPath());
  }

  @Test
  void setPathPolicy_failsIfNoPath() throws IOException {
    // Test that the provider does not loop when no path is found.
    pp = PathSelectorFixed.create(PathPolicy.DEFAULT);

    List<Path> paths = Scion.defaultService().lookupPaths(someAddress);
    pp.connect(paths.get(0).getRemoteSocketAddress());

    // Create empty path policy
    PathPolicy empty = paths1 -> Collections.emptyList();
    pp.setPathPolicy(empty);
    assertNull(pp.getPath());
  }

  @Test
  void reportError_NoException_NothingChanges() {
    // Check that other errors do not have an effect or cause an exception
    testError(
        (pathProvider, path) -> pathProvider.reportError(Scmp.Error2Message.create(path, 1200)),
        false);
  }

  @Test
  void reportError5() {
    testError((pathProvider, path) -> pathProvider.reportError(createError5(path)));
  }

  @Test
  void reportError6() {
    testError((pathProvider, path) -> pathProvider.reportError(createError6(path)));
  }

  private void testError(BiConsumer<PathSelector, Path> test) {
    testError(test, true);
  }

  private void testError(BiConsumer<PathSelector, Path> test, boolean expectNull) {
    MockNetwork.stopTiny();
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.DEFAULT, "ASff00_0_112")) {
      ScionService service = Scion.defaultService();
      pp = PathSelectorFixed.create(PathPolicy.DEFAULT);
      InetSocketAddress dummyAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      ScionSocketAddress remote = PackageVisibilityHelper.toSSA("1-ff00:0:110", dummyAddr);
      List<Path> paths = service.getPaths(remote);
      // reset counter
      assertEquals(2, nw.getControlServer().getAndResetCallCount());

      // Use path directly here because this is how FixedSelector works.
      pp.connect(paths.get(0).getRemoteSocketAddress());
      assertEquals(paths.get(0), pp.getPath());

      // Replace path
      test.accept(pp, paths.get(0));
      if (expectNull) {
        assertNull(pp.getPath());
      } else {
        assertEquals(paths.get(0), pp.getPath());
      }
      assertEquals(0, nw.getControlServer().getAndResetCallCount());
    }
  }

  private Scmp.Error5Message createError5(Path errorPath) {
    // All paths use a different ingress interface here.
    PathMetadata.PathInterface pif = errorPath.getMetadata().getInterfaces().get(5);
    return Scmp.Error5Message.create(errorPath, pif.getIsdAs(), pif.getId());
  }

  private Scmp.Error6Message createError6(Path errorPath) {
    // interfaces 7 and 8 are unique/common to the first two paths.
    PathMetadata.PathInterface pifIn = errorPath.getMetadata().getInterfaces().get(3);
    PathMetadata.PathInterface pifEg = errorPath.getMetadata().getInterfaces().get(4);
    assertEquals(pifIn.getIsdAs(), pifEg.getIsdAs());
    return Scmp.Error6Message.create(errorPath, pifIn.getIsdAs(), pifIn.getId(), pifEg.getId());
  }
}
