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

import java.util.Collections;
import org.junit.jupiter.api.*;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockBootstrapServer;
import org.scion.jpan.testutil.MockNetwork;

class PathSelectorNullTest {

  private static final String TOPO_FILE = MockBootstrapServer.TOPO_TINY_110 + "topology.json";
  private PathSelectorNull pp = null;

  @BeforeEach
  void beforeEach() {
    MockNetwork.startTiny(MockNetwork.Mode.BOOTSTRAP);
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, TOPO_FILE);
  }

  @AfterEach
  void afterEach() {
    if (pp != null) {
      pp.close();
      pp = null;
    }
    MockNetwork.stopTiny();
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
  }

  @Test
  void open_fails() {
    pp = PathSelectorNull.instance();
    // Create expired path to trigger PathSelector
    Path path = PackageVisibilityHelper.createDummyPath();
    ScionSocketAddress addr = path.getRemoteSocketAddress();
    assertThrows(UnsupportedOperationException.class, () -> pp.open(addr));
    assertFalse(pp.isOpen());
  }

  @Test
  void setPathPolicy_fails() {
    pp = PathSelectorNull.instance();
    PathPolicy empty = paths1 -> Collections.emptyList();
    pp.setPathPolicy(empty);
    // Should not have changed
    assertEquals(PathPolicy.DEFAULT, pp.getPathPolicy());
    assertNotEquals(empty, pp.getPathPolicy());
  }

  @Test
  void getPath_fails() {
    pp = PathSelectorNull.instance();
    assertThrows(UnsupportedOperationException.class, () -> pp.getPath());
    pp.refresh();
    pp.setExpirationSafetyMargin(1);
    assertThrows(UnsupportedOperationException.class, () -> pp.getPath());
  }

  @Test
  void getRemoteSocketAddress_fails() {
    pp = PathSelectorNull.instance();
    assertThrows(UnsupportedOperationException.class, () -> pp.getRemoteSocketAddress());
  }

  @Test
  void reportError5() {
    pp = PathSelectorNull.instance();
    pp.reportError(createError5(PackageVisibilityHelper.createDummyPath()));
    assertThrows(UnsupportedOperationException.class, () -> pp.getPath());
  }

  @Test
  void reportError6() {
    pp = PathSelectorNull.instance();
    pp.reportError(createError6(PackageVisibilityHelper.createDummyPath()));
    assertThrows(UnsupportedOperationException.class, () -> pp.getPath());
  }

  private Scmp.Error5Message createError5(Path errorPath) {
    return Scmp.Error5Message.create(errorPath, 1, 2);
  }

  private Scmp.Error6Message createError6(Path errorPath) {
    return Scmp.Error6Message.create(errorPath, 112, 1, 2);
  }

  @Test
  void factory_fails() {
    PathSelectorNull.Factory factory = PathSelectorNull.Factory.instance();
    ScionService service = Scion.defaultService();
    assertThrows(UnsupportedOperationException.class, () -> factory.createPathSelector(service));
  }
}
