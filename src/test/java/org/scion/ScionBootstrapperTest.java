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

package org.scion;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.scion.demo.DemoConstants;
import org.scion.internal.ScionBootstrapper;

public class ScionBootstrapperTest {

  @Test
  void testETH_bootstrapServer() {
    String bootETH = "129.132.121.175:8041";
    ScionBootstrapper sb = ScionBootstrapper.createViaBootstrapServerIP(bootETH);

    assertEquals(DemoConstants.iaETH, sb.getLocalIsdAs());
    assertEquals(DemoConstants.csETH, sb.getControlServerAddress());
    assertFalse(sb.isLocalAsCore());
  }

  @Test
  void testETH_topoFile() {
    java.nio.file.Path topoFile = Paths.get("topologies/ETH.json");
    ScionBootstrapper sb = ScionBootstrapper.createViaTopoFile(topoFile);

    assertEquals(DemoConstants.iaETH, sb.getLocalIsdAs());
    assertEquals(DemoConstants.csETH, sb.getControlServerAddress());
    assertFalse(sb.isLocalAsCore());
  }

  @Test
  void testTiny110() {
    java.nio.file.Path topoFile = Paths.get("topologies/scionproto-tiny-110.json");
    ScionBootstrapper sb = ScionBootstrapper.createViaTopoFile(topoFile);

    assertEquals(ScionUtil.parseIA("1-ff00:0:110"), sb.getLocalIsdAs());
    assertEquals("127.0.0.11:31000", sb.getControlServerAddress());
    assertTrue(sb.isLocalAsCore());
  }
}
