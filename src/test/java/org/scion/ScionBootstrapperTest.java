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
import org.scion.internal.ScionBootstrapper;

public class ScionBootstrapperTest {

  @Test
  void testETH() {
    String bootETH = "129.132.121.175:8041";
    String csETH = "192.168.53.20:30252";
    long iaETH = ScionUtil.parseIA("64-2:0:9");
    long iaGEANT = ScionUtil.parseIA(ScionUtil.toStringIA(71, 20965));
    long iaOVGU = ScionUtil.parseIA("71-2:0:4a");
    long iaAnapayaHK = ScionUtil.parseIA("66-2:0:11");

    // ScionBootstrapper sb = ScionBootstrapper.createViaBootstrapServerIP(bootETH);
    java.nio.file.Path topoFile = Paths.get("topology-ETH.json");
    ScionBootstrapper sb = ScionBootstrapper.createViaTopoFile(topoFile);

    assertEquals(iaETH, sb.getLocalIsdAs());
    assertEquals(csETH, sb.getControlServerAddress());
    assertFalse(sb.isCoreAS());
  }

  @Test
  void testTiny110() {
    java.nio.file.Path topoFile = Paths.get("topology-tiny-110.json");
    ScionBootstrapper sb = ScionBootstrapper.createViaTopoFile(topoFile);

    assertEquals(ScionUtil.parseIA("1-ff00:0:110"), sb.getLocalIsdAs());
    assertEquals("127.0.0.11:31000", sb.getControlServerAddress());
    assertTrue(sb.isCoreAS());
  }
}
