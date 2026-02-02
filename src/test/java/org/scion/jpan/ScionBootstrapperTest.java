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

package org.scion.jpan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.bootstrap.ScionBootstrapper;

class ScionBootstrapperTest {

  @AfterAll
  static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void testTiny110() {
    LocalAS topo = ScionBootstrapper.fromTopoFile("topologies/tiny4/ASff00_0_110/topology.json");

    assertEquals(ScionUtil.parseIA("1-ff00:0:110"), topo.getIsdAs());
    assertEquals("127.0.0.11:31000", topo.getControlServerAddress());
    assertTrue(topo.isCoreAs());
  }
}
