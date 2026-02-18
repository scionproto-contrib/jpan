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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockNetwork;

public class PathMetadataTest {

  @AfterEach
  void afterEach() {
    MockNetwork.stopTiny();
  }

  @Test
  void timestamp() throws IOException {
    MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
    InetAddress ia = InetAddress.getByAddress(ExamplePacket.DST_HOST);
    try (Scion.CloseableService service =
        Scion.newServiceWithTopologyFile("topologies/tiny4/ASff00_0_110/topology.json")) {
      List<Path> paths = service.getPaths(ExamplePacket.DST_IA, ia, 12345);
      Path path = paths.get(0);
      long expSec = path.getMetadata().getExpiration();
      Instant exp = Instant.ofEpochSecond(expSec);
      assertTrue(expSec > 0);
      assertTrue(exp.isAfter(Instant.now().plus(5, ChronoUnit.HOURS)));
      assertTrue(exp.isBefore(Instant.now().plus(7, ChronoUnit.HOURS)));
    }
  }

  @Test
  void mtu() throws IOException {
    MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
    InetAddress ia = InetAddress.getByAddress(ExamplePacket.DST_HOST);
    try (Scion.CloseableService service =
        Scion.newServiceWithTopologyFile("topologies/tiny4/ASff00_0_110/topology.json")) {
      List<Path> paths = service.getPaths(ExamplePacket.DST_IA, ia, 12345);
      Path path = paths.get(0);
      long mtu = path.getMetadata().getMtu();
      assertEquals(1234, mtu);
    }
  }
}
