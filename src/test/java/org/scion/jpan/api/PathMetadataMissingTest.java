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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockNetwork2;

class PathMetadataMissingTest {

  private static final Function<PathMetadata.PathInterface, Integer> GET_ID = p -> (int) p.getId();
  private static final Function<PathMetadata.PathInterface, Long> GET_IAS_AS =
      PathMetadata.PathInterface::getIsdAs;
  private static final Function<PathMetadata.GeoCoordinates, String> GET_ADDR =
      PathMetadata.GeoCoordinates::getAddress;

  @Test
  void testCore_110_210() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/minimal/", "ASff00_0_110")) {
      ScionService service = Scion.defaultService();
      List<Path> paths = service.getPaths(ScionUtil.parseIA("2-ff00:0:210"), dstAddress);
      assertEquals(1, paths.size());
      Path path = paths.get(0);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 1, 10, 210, 105);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_ff00_0000_0110L,
          0x1_ff00_0000_0120L,
          0x1_ff00_0000_0120L,
          0x2_ff00_0000_0210L);
      checkEqual(meta.getBandwidthList(), 0L, 0L, 0L);
      checkEqual(meta.getLatencyList(), -1, -1, -1);
      checkEqual(
          meta.getLinkTypeList(),
          PathMetadata.LinkType.UNSPECIFIED,
          PathMetadata.LinkType.UNSPECIFIED);
      checkEqual(meta.getGeoList(), GET_ADDR, "", "", "", "");
      checkEqual(meta.getNotesList(), "", "", "");
      checkEqual(meta.getInternalHopsList(), 0);
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
  void testUp_1121_110() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/minimal/", "ASff00_0_1121")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:110"), dstAddress);
      assertEquals(1, paths.size());
      Path path = paths.get(0);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 345, 1121, 453, 3);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_ff00_0000_1121L,
          0x1_ff00_0000_0112L,
          0x1_ff00_0000_0112L,
          0x1_ff00_0000_0110L);
      checkEqual(meta.getBandwidthList(), 0L, 0L, 0L);
      checkEqual(meta.getLatencyList(), -1, -1, -1);

      checkEqual(
          meta.getLinkTypeList(),
          PathMetadata.LinkType.UNSPECIFIED,
          PathMetadata.LinkType.UNSPECIFIED);
      checkEqual(meta.getGeoList(), GET_ADDR, "", "", "", "");
      checkEqual(meta.getNotesList(), "", "", "");
      checkEqual(meta.getInternalHopsList(), 0);
    }

    // scion showpaths 1-ff00:0:120 --isd-as 1-ff00:0:112 --sciond 127.0.0.60:30255 --extended
    // Available paths to 1-ff00:0:120
    // 3 Hops:
    // [0] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 104>5 1-ff00:0:120]
    //    MTU: 1450
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:24:51 +0000 UTC (5h59m51s)
    //    Latency: 292ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-104") >
    // 79.12,45.2 ("geo120-5")]
    //    LinkType: [multihop, direct]
    //    InternalHops: [1-ff00:0:111: 4]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:120:
    // "asdf-1-120"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // [1] Hops: [1-ff00:0:112 495>113 1-ff00:0:130 105>1 1-ff00:0:120]
    //    MTU: 1450
    //    NextHop: 127.0.0.57:31032
    //    Expires: 2025-03-19 22:24:51 +0000 UTC (5h59m51s)
    //    Latency: >101ms (information incomplete)
    //    Bandwidth: 100Kbit/s (information incomplete)
    //    Geo: [79.2,45.2 ("geo112-495") > N/A > N/A > 47.12,62.2 ("geo120-1")]
    //    LinkType: [unset, direct]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:120: "asdf-1-120"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // 4 Hops:
    // ...
  }

  @Test
  void testDown_110_1121() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/minimal/", "ASff00_0_110")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:1121"), dstAddress);
      assertEquals(1, paths.size());
      Path path = paths.get(0);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 3, 453, 1121, 345);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_ff00_0000_0110L,
          0x1_ff00_0000_0112L,
          0x1_ff00_0000_0112L,
          0x1_ff00_0000_1121L);
      checkEqual(meta.getBandwidthList(), 0L, 0L, 0L);
      checkEqual(meta.getLatencyList(), -1, -1, -1);

      checkEqual(
          meta.getLinkTypeList(),
          PathMetadata.LinkType.UNSPECIFIED,
          PathMetadata.LinkType.UNSPECIFIED);
      checkEqual(meta.getGeoList(), GET_ADDR, "", "", "", "");
      checkEqual(meta.getNotesList(), "", "", "");
      checkEqual(meta.getInternalHopsList(), 0);
    }

    // scion showpaths 1-ff00:0:112 --isd-as 1-ff00:0:120 --sciond 127.0.0.69:30255 --extended
    // Available paths to 1-ff00:0:112
    // 3 Hops:
    // [0] Hops: [1-ff00:0:120 1>105 1-ff00:0:130 113>495 1-ff00:0:112]
    //    MTU: 1450
    //    NextHop: 127.0.0.65:31010
    //    Expires: 2025-03-19 22:27:19 +0000 UTC (5h59m49s)
    //    Geo: [47.12,62.2 ("geo120-1") > N/A > N/A > 79.2,45.2 ("geo112-495")]
    //    Notes: [1-ff00:0:120: "asdf-1-120", 1-ff00:0:112: "asdf-1-112"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // [1] Hops: [1-ff00:0:120 5>104 1-ff00:0:111 103>494 1-ff00:0:112]
    //    MTU: 1450
    //    NextHop: 127.0.0.67:31014
    //    Expires: 2025-03-19 22:27:20 +0000 UTC (5h59m50s)
    //    Latency: 292ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [79.12,45.2 ("geo120-5") > 47.12,62.2 ("geo111-104") > 47.12,42.23 ("geo111-103") >
    // 47.2,62.2 ("geo112-494")]
    //    LinkType: [direct, multihop]
    //    InternalHops: [1-ff00:0:111: 4]
    //    Notes: [1-ff00:0:120: "asdf-1-120", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:112:
    // "asdf-1-112"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // 4 Hops:
    // ...
  }

  @Test
  void testUpCoreDown_1121_211() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/minimal/", "ASff00_0_1121")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("2-ff00:0:211"), dstAddress);
      assertEquals(1, paths.size());
      Path path = paths.get(0);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 345, 1121, 453, 3, 1, 10, 210, 105, 450, 503);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_ff00_0000_1121L,
          0x1_ff00_0000_0112L,
          0x1_ff00_0000_0112L,
          0x1_ff00_0000_0110L,
          0x1_ff00_0000_0110L,
          0x1_ff00_0000_0120L,
          0x1_ff00_0000_0120L,
          0x2_ff00_0000_0210L,
          0x2_ff00_0000_0210L,
          0x2_ff00_0000_0211L);
      checkEqual(meta.getBandwidthList(), 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
      checkEqual(meta.getLatencyList(), -1, -1, -1, -1, -1, -1, -1, -1, -1);

      checkEqual(
          meta.getLinkTypeList(),
          PathMetadata.LinkType.UNSPECIFIED,
          PathMetadata.LinkType.UNSPECIFIED,
          PathMetadata.LinkType.UNSPECIFIED,
          PathMetadata.LinkType.UNSPECIFIED,
          PathMetadata.LinkType.UNSPECIFIED);
      checkEqual(meta.getGeoList(), GET_ADDR, "", "", "", "", "", "", "", "", "", "");
      checkEqual(meta.getNotesList(), "", "", "", "", "", "");
      checkEqual(meta.getInternalHopsList(), 0, 0, 0, 0);
    }
    //  scion showpaths 2-ff00:0:211 --sciond 127.0.0.67:30255 --extended
    //  Available paths to 2-ff00:0:211
    //  6 Hops:
    //  [0] Hops: [1-ff00:0:1121 345>1121 1-ff00:0:112 453>3 1-ff00:0:110 1>10 1-ff00:0:120 210>105
    // 2-ff00:0:210 450>503 2-ff00:0:211]
    //      MTU: 1280
    //      NextHop: 127.0.0.65:31038
    //      Expires: 2025-04-01 20:51:27 +0000 UTC (5h58m4s)
    //      SupportsEPIC: false
    //      Status: alive
    //      LocalIP: 127.0.0.1

    //  [0] Hops: [1-ff00:0:1121 345>1121 1-ff00:0:112 453>3 1-ff00:0:110 1>10 1-ff00:0:120 210>105
    // 2-ff00:0:210 450>503 2-ff00:0:211]
    //      MTU: 1280
    //      NextHop: 127.0.0.65
    //      Expires: 2025-04-01 20:51:27 +0000 UTC (5h59m51s)
    //      Latency: >0ms (information incomplete)
    //      Bandwidth: 0KBit/s (information incomplete)
    //      Geo: [N/A > N/A > N/A > N/A > N/A > N/A > N/A > N/A > N/A > N/A]
    //      LinkType: [unset, unset, unset, unset, unset]
    //      Notes: []
    //      SupportsEPIC: false
    //      Status: unknown
    //      LocalIP: 127.0.0.1
  }

  @SafeVarargs
  private final <T> void checkEqual(List<T> actual, T... expected) {
    for (int i = 0; i < expected.length; i++) {
      assertTrue(i < actual.size(), "No such element: " + i);
      assertEquals(expected[i], actual.get(i), "At position " + i);
    }
    assertEquals(expected.length, actual.size());
  }

  @SafeVarargs
  private final <T, R> void checkEqual(List<T> actual, Function<T, R> mapFn, R... expected) {
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], mapFn.apply(actual.get(i)), "At position " + i);
    }
    assertEquals(expected.length, actual.size());
  }
}
