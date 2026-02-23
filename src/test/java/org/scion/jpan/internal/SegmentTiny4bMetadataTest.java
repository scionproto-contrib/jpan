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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockNetwork2;

class SegmentTiny4bMetadataTest {

  protected static final long AS_110 = ScionUtil.parseIA("1-ff00:0:110");
  protected static final long AS_112 = ScionUtil.parseIA("1-ff00:0:112");
  protected static final long AS_120 = ScionUtil.parseIA("1-ff00:0:120");
  protected static final long AS_121 = ScionUtil.parseIA("1-ff00:0:121");

  private static final MockNetwork2.Topology TOPO = MockNetwork2.Topology.TINY4B;
  private static final String TOPO_DIR = "topologies/tiny4b/";
  private static final String TOPO_110 = TOPO_DIR + "ASff00_0_110/topology.json";
  private static final String TOPO_112 = TOPO_DIR + "ASff00_0_112/topology.json";
  private static final String TOPO_120 = TOPO_DIR + "ASff00_0_120/topology.json";

  @Test
  void testCore_110_120() {
    try (MockNetwork2 nw = MockNetwork2.start(TOPO, "ASff00_0_110")) {

      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_110)) {
        List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_110, AS_120);
        assertNotNull(paths);
        assertEquals(1, paths.size());

        PathMetadata path = paths.get(0);
        assertEquals(1, path.getBandwidths().size());
        assertEquals(110120, path.getBandwidths().get(0));
        assertEquals(1, path.getLatencies().size());
        assertEquals(120, path.getLatencies().get(0));
      }
    }
  }

  @Test
  void testCore_120_110() {
    try (MockNetwork2 nw = MockNetwork2.start(TOPO, "ASff00_0_120")) {

      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_120)) {
        List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_120, AS_110);
        assertNotNull(paths);
        assertEquals(1, paths.size());

        PathMetadata path = paths.get(0);
        assertEquals(1, path.getBandwidths().size());
        assertEquals(110120, path.getBandwidths().get(0));
        assertEquals(1, path.getLatencies().size());
        assertEquals(120, path.getLatencies().get(0));
      }
    }

    // scion showpaths 1-ff00:0:110 --isd-as 1-ff00:0:120 --sciond 127.0.0.69:30255 --extended
    // Available paths to 1-ff00:0:110
    // 2 Hops:
    // [0] Hops: [1-ff00:0:120 6>1 1-ff00:0:110]
    //    MTU: 1472
    //    NextHop: 127.0.0.65:31010
    //    Expires: 2025-03-19 22:45:03 +0000 UTC (5h59m56s)
    //    Latency: 101ms
    //    Bandwidth: 100Kbit/s
    //    Geo: [47.12,42.23 ("geo120-6") > 47.2,62.2 ("geo110-1")]
    //    LinkType: [direct]
    //    Notes: [1-ff00:0:120: "asdf-1-120", 1-ff00:0:110: "asdf-1-110"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // 3 Hops:
    // ...
  }

  @Test
  void testDown_120_121() {
    try (MockNetwork2 nw = MockNetwork2.start(TOPO, "ASff00_0_120")) {
      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_120)) {
        List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_120, AS_121);
        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        PathMetadata path = paths.get(0);
        assertEquals(1, path.getBandwidths().size());
        assertEquals(120121, path.getBandwidths().get(0));
        assertEquals(1, path.getLatencies().size());
        assertEquals(121, path.getLatencies().get(0));
      }
    }

    // scion showpaths 2-ff00:0:220 --isd-as 1-ff00:0:120 --sciond 127.0.0.69:30255 --extended
    // Available paths to 2-ff00:0:220
    // 2 Hops:
    // [0] Hops: [1-ff00:0:120 2>501 2-ff00:0:220]
    //    MTU: 1350
    //    NextHop: 127.0.0.66:31012
    //    Expires: 2025-03-19 22:46:40 +0000 UTC (5h59m50s)
    //    Latency: 102ms
    //    Bandwidth: 100Kbit/s
    //    Geo: [79.12,45.2 ("geo120-2") > 79.2,45.2 ("geo220#501")]
    //    LinkType: [opennet]
    //    Notes: [1-ff00:0:120: "asdf-1-120", 2-ff00:0:220: "asdf-2-220"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // [1] Hops: [1-ff00:0:120 3>502 2-ff00:0:220]
    //    MTU: 1400
    //    NextHop: 127.0.0.66:31012
    //    Expires: 2025-03-19 22:46:40 +0000 UTC (5h59m50s)
    //    Latency: 103ms
    //    Bandwidth: 100Kbit/s
    //    Geo: [47.12,42.23 ("geo120-3") > 47.22,42.23 ("geo220#502")]
    //    LinkType: [multihop]
    //    Notes: [1-ff00:0:120: "asdf-1-120", 2-ff00:0:220: "asdf-2-220"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // 4 Hops:
    // ...
  }

  @Test
  void testUp_112_110() {
    try (MockNetwork2 nw = MockNetwork2.start(TOPO, "ASff00_0_112")) {
      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_112)) {
        List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_112, AS_110);
        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        PathMetadata path = paths.get(0);
        assertEquals(3, path.getBandwidths().size());
        assertEquals(112111, path.getBandwidths().get(0));
        assertEquals(511, path.getBandwidths().get(1));
        assertEquals(111110, path.getBandwidths().get(2));
        assertEquals(3, path.getLatencies().size());
        assertEquals(112, path.getLatencies().get(0));
        assertEquals(12, path.getLatencies().get(1));
        assertEquals(111, path.getLatencies().get(2));
      }
    }

    //  $ scion showpaths 1-ff00:0:110 --isd-as 1-ff00:0:112 --sciond 127.0.0.35:30255 --extended
    //  Available paths to 1-ff00:0:110
    //  3 Hops:
    //  [0] Hops: [1-ff00:0:112 11>12 1-ff00:0:111 10>11 1-ff00:0:110]
    //    MTU: 1280
    //    NextHop: 127.0.0.33:31020
    //    Expires: 2025-03-25 17:34:05 +0000 UTC (5h19m10s)
    //    Latency: 235ms
    //    Bandwidth: 111Kbit/s
    //    Geo: [79.112,45.112 ("geo112-11") > 47.111,62.112 ("geo111-112") > 47.111,42.11
    // ("geo111-110") > 47.11,42.111 ("geo110-111")]
    //    LinkType: [direct, direct]
    //    InternalHops: [1-ff00:0:111: 11]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:110:
    // "asdf-1-110"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
  }

  @Test
  void testDown_110_112() {
    try (MockNetwork2 nw = MockNetwork2.start(TOPO, "ASff00_0_110")) {
      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_110)) {
        List<PathMetadata> paths = PackageVisibilityHelper.getPathsCS(ss, AS_110, AS_112);
        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        PathMetadata path = paths.get(0);
        assertEquals(3, path.getBandwidths().size());
        assertEquals(111110, path.getBandwidths().get(0));
        assertEquals(511, path.getBandwidths().get(1));
        assertEquals(112111, path.getBandwidths().get(2));
        assertEquals(3, path.getLatencies().size());
        assertEquals(111, path.getLatencies().get(0));
        assertEquals(12, path.getLatencies().get(1));
        assertEquals(112, path.getLatencies().get(2));
      }
    }

    // $ scion showpaths 1-ff00:0:112 --isd-as 1-ff00:0:110 --sciond 127.0.0.20:30255 --extended
    // Available paths to 1-ff00:0:112
    // 3 Hops:
    // [0] Hops: [1-ff00:0:110 11>10 1-ff00:0:111 12>11 1-ff00:0:112]
    //    MTU: 1280
    //    NextHop: 127.0.0.17:31002
    //    Expires: 2025-03-25 22:12:45 +0000 UTC (5h59m51s)
    //    Latency: 235ms
    //    Bandwidth: 111Kbit/s
    //    Geo: [47.11,42.111 ("geo110-111") > 47.111,42.11 ("geo111-110") > 47.111,62.112
    // ("geo111-112") > 79.112,45.112 ("geo112-11")]
    //    LinkType: [direct, direct]
    //    InternalHops: [1-ff00:0:111: 11]
    //    Notes: [1-ff00:0:110: "asdf-1-110", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:112:
    // "asdf-1-112"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
  }
}
