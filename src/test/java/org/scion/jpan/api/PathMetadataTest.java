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

class PathMetadataTest {

  private static final Function<PathMetadata.PathInterface, Integer> GET_ID = p -> (int) p.getId();
  private static final Function<PathMetadata.PathInterface, Long> GET_IAS_AS =
      PathMetadata.PathInterface::getIsdAs;
  private static final Function<PathMetadata.GeoCoordinates, String> GET_ADDR =
      PathMetadata.GeoCoordinates::getAddress;

  @Test
  void testCore_120_110() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_120")) {
      ScionService service = Scion.defaultService();
      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:110"), dstAddress);
      assertEquals(7, paths.size());
      Path path = paths.get(0);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 6, 1);
      checkEqual(meta.getInterfacesList(), GET_IAS_AS, 0x1_ff00_0000_0120L, 0x1_ff00_0000_0110L);
      checkEqual(meta.getBandwidthList(), 100L);
      checkEqual(meta.getLatencyList(), 101);

      checkEqual(meta.getLinkTypeList(), PathMetadata.LinkType.DIRECT);
      checkEqual(meta.getGeoList(), GET_ADDR, "geo120-6", "geo110-1");
      checkEqual(meta.getNotesList(), "asdf-1-120", "asdf-1-110");
      checkEqual(meta.getInternalHopsList());
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
  void testCore_120_220() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_120")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("2-ff00:0:220"), dstAddress);
      Path path = null;
      for (Path p : paths) {
        PathMetadata meta = p.getMetadata();
        if (meta.getInterfacesList().size() == 2 && meta.getInterfacesList().get(0).getId() == 2) {
          path = p;
        }
      }
      assertNotNull(path);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 2, 501);
      checkEqual(meta.getInterfacesList(), GET_IAS_AS, 0x1_ff00_0000_0120L, 0x2_ff00_0000_0220L);
      checkEqual(meta.getBandwidthList(), 120220L);
      checkEqual(meta.getLatencyList(), 102);

      checkEqual(meta.getLinkTypeList(), PathMetadata.LinkType.OPEN_NET);
      checkEqual(meta.getGeoList(), GET_ADDR, "geo120-2", "geo220#501");
      checkEqual(meta.getNotesList(), "asdf-1-120", "asdf-2-220");
      checkEqual(meta.getInternalHopsList());
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
  }

  @Test
  void testUp_112_120() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_112")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:120"), dstAddress);
      assertEquals(9, paths.size());
      Path path = null;
      for (Path p : paths) {
        PathMetadata meta = p.getMetadata();
        if (meta.getInterfacesList().size() == 4
            && meta.getInterfacesList().get(0).getId() == 494) {
          path = p;
        }
      }
      assertNotNull(path);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 494, 103, 104, 5);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_ff00_0000_0112L,
          0x1_ff00_0000_0111L,
          0x1_ff00_0000_0111L,
          0x1_ff00_0000_0120L);
      checkEqual(meta.getBandwidthList(), 11200L, 50L, 11100L);
      checkEqual(meta.getLatencyList(), 112, 50, 111);

      checkEqual(
          meta.getLinkTypeList(), PathMetadata.LinkType.DIRECT, PathMetadata.LinkType.DIRECT);
      checkEqual(meta.getGeoList(), GET_ADDR, "geo112-494", "geo111-103", "geo111-104", "geo120-5");
      checkEqual(meta.getNotesList(), "asdf-1-112", "asdf-1-111", "asdf-1-120");
      checkEqual(meta.getInternalHopsList(), 3);
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
  void testDown_120_112() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_120")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:112"), dstAddress);
      assertEquals(9, paths.size());
      Path path = null;
      for (Path p : paths) {
        PathMetadata meta = p.getMetadata();
        if (meta.getInterfacesList().size() == 4 && meta.getInterfacesList().get(0).getId() == 5) {
          path = p;
        }
      }
      assertNotNull(path);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 5, 104, 103, 494);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_ff00_0000_0120L,
          0x1_ff00_0000_0111L,
          0x1_ff00_0000_0111L,
          0x1_ff00_0000_0112L);
      checkEqual(meta.getBandwidthList(), 11100L, 50L, 11200L);
      checkEqual(meta.getLatencyList(), 111, 50, 112);

      checkEqual(
          meta.getLinkTypeList(), PathMetadata.LinkType.DIRECT, PathMetadata.LinkType.DIRECT);
      checkEqual(meta.getGeoList(), GET_ADDR, "geo120-5", "geo111-104", "geo111-103", "geo112-494");
      checkEqual(meta.getNotesList(), "asdf-1-120", "asdf-1-111", "asdf-1-112");
      checkEqual(meta.getInternalHopsList(), 3);
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
  void testUpCoreDown_112_221() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_112")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("2-ff00:0:221"), dstAddress);
      assertEquals(4, paths.size());
      Path path = null;
      for (Path p : paths) {
        PathMetadata meta = p.getMetadata();
        List<PathMetadata.PathInterface> list = meta.getInterfacesList();
        if (list.size() == 8 && list.get(0).getId() == 494 && list.get(4).getId() == 2) {
          path = p;
        }
      }
      assertNotNull(path);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 494, 103, 104, 5, 2, 501, 500, 2);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_ff00_0000_0112L,
          0x1_ff00_0000_0111L,
          0x1_ff00_0000_0111L,
          0x1_ff00_0000_0120L,
          0x1_ff00_0000_0120L,
          0x2_ff00_0000_0220L,
          0x2_ff00_0000_0220L,
          0x2_ff00_0000_0221L);
      checkEqual(meta.getBandwidthList(), 11200L, 50L, 11100L, 50L, 120220L, 50L, 220221L);
      checkEqual(meta.getLatencyList(), 112, 50, 111, 50, 102, 50, 101);

      checkEqual(
          meta.getLinkTypeList(),
          PathMetadata.LinkType.DIRECT,
          PathMetadata.LinkType.DIRECT,
          PathMetadata.LinkType.OPEN_NET,
          PathMetadata.LinkType.OPEN_NET);
      checkEqual(
          meta.getGeoList(),
          GET_ADDR,
          "geo112-494",
          "geo111-103",
          "geo111-104",
          "geo120-5",
          "geo120-2",
          "geo220#501",
          "geo220#500",
          "geo221-2");
      checkEqual(
          meta.getNotesList(),
          "asdf-1-112",
          "asdf-1-111",
          "asdf-1-120",
          "asdf-2-220",
          "asdf-2-221");
      checkEqual(meta.getInternalHopsList(), 3, 5, 2);
    }
    // 494>103 104>5 2>501

    // scion showpaths 2-ff00:0:221 --isd-as 1-ff00:0:112 --sciond 127.0.0.60:30255 --extended
    // Available paths to 2-ff00:0:221
    // 5 Hops:
    // [0] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 104>5 1-ff00:0:120 2>501 2-ff00:0:220 500>2
    // 2-ff00:0:221]
    //    MTU: 1350
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:15:32 +0000 UTC (5h56m53s)
    //    Latency: 595ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-104") >
    // 79.12,45.2 ("geo120-5") > 79.12,45.2 ("geo120-2") > 79.2,45.2 ("geo220#501") > 47.2,62.2
    // ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [multihop, direct, opennet, direct]
    //    InternalHops: [1-ff00:0:111: 4, 1-ff00:0:120: 5, 2-ff00:0:220: 2]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:120:
    // "asdf-1-120", 2-ff00:0:220: "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // [1] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 104>5 1-ff00:0:120 3>502 2-ff00:0:220 500>2
    // 2-ff00:0:221]
    //    MTU: 1400
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:15:32 +0000 UTC (5h56m53s)
    //    Latency: 636ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-104") >
    // 79.12,45.2 ("geo120-5") > 47.12,42.23 ("geo120-3") > 47.22,42.23 ("geo220#502") > 47.2,62.2
    // ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [multihop, direct, multihop, direct]
    //    InternalHops: [1-ff00:0:111: 4, 1-ff00:0:120: 5, 2-ff00:0:220: 3]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:120:
    // "asdf-1-120", 2-ff00:0:220: "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // [2] Hops: [1-ff00:0:112 495>113 1-ff00:0:130 105>1 1-ff00:0:120 2>501 2-ff00:0:220 500>2
    // 2-ff00:0:221]
    //    MTU: 1350
    //    NextHop: 127.0.0.57:31032
    //    Expires: 2025-03-19 22:15:33 +0000 UTC (5h56m54s)
    //    Latency: >404ms (information incomplete)
    //    Bandwidth: 50Kbit/s (information incomplete)
    //    Geo: [79.2,45.2 ("geo112-495") > N/A > N/A > 47.12,62.2 ("geo120-1") > 79.12,45.2
    // ("geo120-2") > 79.2,45.2 ("geo220#501") > 47.2,62.2 ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [unset, direct, opennet, direct]
    //    InternalHops: [1-ff00:0:120: 2, 2-ff00:0:220: 2]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:120: "asdf-1-120", 2-ff00:0:220:
    // "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // [3] Hops: [1-ff00:0:112 495>113 1-ff00:0:130 105>1 1-ff00:0:120 3>502 2-ff00:0:220 500>2
    // 2-ff00:0:221]
    //    MTU: 1400
    //    NextHop: 127.0.0.57:31032
    //    Expires: 2025-03-19 22:15:33 +0000 UTC (5h56m54s)
    //    Latency: >465ms (information incomplete)
    //    Bandwidth: 80Kbit/s (information incomplete)
    //    Geo: [79.2,45.2 ("geo112-495") > N/A > N/A > 47.12,62.2 ("geo120-1") > 47.12,42.23
    // ("geo120-3") > 47.22,42.23 ("geo220#502") > 47.2,62.2 ("geo220#500") > 79.2,45.2
    // ("geo212-2")]
    //    LinkType: [unset, direct, multihop, direct]
    //    InternalHops: [1-ff00:0:120: 3, 2-ff00:0:220: 3]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:120: "asdf-1-120", 2-ff00:0:220:
    // "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // 6 Hops:
    // ...
  }

  @Test
  void testUpCoreDown_112_222() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_112")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("2-ff00:0:222"), dstAddress);
      assertEquals(4, paths.size());
      Path path = null;
      for (Path p : paths) {
        PathMetadata meta = p.getMetadata();
        List<PathMetadata.PathInterface> list = meta.getInterfacesList();
        if (list.size() == 10 && list.get(0).getId() == 494 && list.get(4).getId() == 2) {
          path = p;
        }
      }
      assertNotNull(path);
      PathMetadata meta = path.getMetadata();
      checkEqual(meta.getInterfacesList(), GET_ID, 494, 103, 104, 5, 2, 501, 500, 2, 1, 302);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_ff00_0000_0112L,
          0x1_ff00_0000_0111L,
          0x1_ff00_0000_0111L,
          0x1_ff00_0000_0120L,
          0x1_ff00_0000_0120L,
          0x2_ff00_0000_0220L,
          0x2_ff00_0000_0220L,
          0x2_ff00_0000_0221L,
          0x2_ff00_0000_0221L,
          0x2_ff00_0000_0222L);
      checkEqual(
          meta.getBandwidthList(), 11200L, 50L, 11100L, 50L, 120220L, 50L, 220221L, 50L, 221222L);
      checkEqual(meta.getLatencyList(), 112, 50, 111, 50, 102, 50, 101, 50, 101);

      checkEqual(
          meta.getLinkTypeList(),
          PathMetadata.LinkType.DIRECT,
          PathMetadata.LinkType.DIRECT,
          PathMetadata.LinkType.OPEN_NET,
          PathMetadata.LinkType.OPEN_NET,
          PathMetadata.LinkType.UNSPECIFIED);
      checkEqual(
          meta.getGeoList(),
          GET_ADDR,
          "geo112-494",
          "geo111-103",
          "geo111-104",
          "geo120-5",
          "geo120-2",
          "geo220#501",
          "geo220#500",
          "geo221-2",
          "geo221-1",
          "");
      checkEqual(
          meta.getNotesList(),
          "asdf-1-112",
          "asdf-1-111",
          "asdf-1-120",
          "asdf-2-220",
          "asdf-2-221",
          "");
      checkEqual(meta.getInternalHopsList(), 3, 5, 2, 2);
    }

    // 494>103 104>5 2>501 500>2 1>302

    //  scion showpaths 2-ff00:0:222 --sciond 127.0.0.60:30255 --extended
    //  ...
    //  [2] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 104>5 1-ff00:0:120 2>501 2-ff00:0:220 500>2
    //      2-ff00:0:221 1>302 2-ff00:0:222]
    //  MTU: 1350
    //  NextHop: 127.0.0.58:31034
    //  Expires: 2025-04-01 21:14:26 +0000 UTC (5h56m21s)
    //  Latency: 761ms
    //  Bandwidth: 40Kbit/s
    //  Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-104")
    //      > 79.12,45.2 ("geo120-5") > 79.12,45.2 ("geo120-2") > 79.2,45.2 ("geo220#501")
    //      > 47.2,62.2 ("geo220#500") > 79.2,45.2 ("geo221-2") > 47.2,62.2 ("geo221-1") > N/A]
    //  LinkType: [multihop, direct, opennet, direct, direct]
    //  InternalHops: [1-ff00:0:111: 4, 1-ff00:0:120: 5, 2-ff00:0:220: 2, 2-ff00:0:221: 2]
    //  Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:120: "asdf-1-120",
    //      2-ff00:0:220: "asdf-2-220", 2-ff00:0:221: "asdf-2-221"]
    //  SupportsEPIC: false
    //  Status: alive
    //  LocalIP: 127.0.0.1
    // ...
  }

  @Test
  void testUpCoreDown_tiny4b_112_121() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/tiny4b/", "ASff00_0_112")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:121"), dstAddress);
      assertEquals(1, paths.size());
      Path path = paths.get(0);
      PathMetadata meta = path.getMetadata();
      assertEquals(8, meta.getInterfacesList().size());
      checkEqual(meta.getInterfacesList(), GET_ID, 11, 12, 10, 11, 20, 10, 21, 20);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_FF00_0000_0112L,
          0x1_FF00_0000_0111L,
          0x1_FF00_0000_0111L,
          0x1_FF00_0000_0110L,
          0x1_FF00_0000_0110L,
          0x1_FF00_0000_0120L,
          0x1_FF00_0000_0120L,
          0x1_FF00_0000_0121L);

      checkEqual(meta.getBandwidthList(), 112111L, 511L, 111110L, 510L, 110120L, 520L, 120121L);
      checkEqual(meta.getLatencyList(), 112, 12, 111, 10, 120, 20, 121);

      checkEqual(
          meta.getLinkTypeList(),
          PathMetadata.LinkType.DIRECT,
          PathMetadata.LinkType.DIRECT,
          PathMetadata.LinkType.MULTI_HOP,
          PathMetadata.LinkType.DIRECT);
      checkEqual(
          meta.getGeoList(),
          GET_ADDR,
          "geo112-11",
          "geo111-112",
          "geo111-110",
          "geo110-111",
          "geo110-120",
          "geo120-110",
          "geo120-121",
          "geo121-20");
      checkEqual(
          meta.getNotesList(),
          "asdf-1-112",
          "asdf-1-111",
          "asdf-1-110",
          "asdf-1-120",
          "asdf-1-121");
      checkEqual(meta.getInternalHopsList(), 11, 10, 7);
    }
    // 494>103 104>5 2>501

    // scion showpaths 2-ff00:0:221 --isd-as 1-ff00:0:112 --sciond 127.0.0.60:30255 --extended
    // Available paths to 2-ff00:0:221
    // 5 Hops:
    // [0] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 104>5 1-ff00:0:120 2>501 2-ff00:0:220 500>2
    // 2-ff00:0:221]
    //    MTU: 1350
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:15:32 +0000 UTC (5h56m53s)
    //    Latency: 595ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-104") >
    // 79.12,45.2 ("geo120-5") > 79.12,45.2 ("geo120-2") > 79.2,45.2 ("geo220#501") > 47.2,62.2
    // ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [multihop, direct, opennet, direct]
    //    InternalHops: [1-ff00:0:111: 4, 1-ff00:0:120: 5, 2-ff00:0:220: 2]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:120:
    // "asdf-1-120", 2-ff00:0:220: "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // [1] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 104>5 1-ff00:0:120 3>502 2-ff00:0:220 500>2
    // 2-ff00:0:221]
    //    MTU: 1400
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:15:32 +0000 UTC (5h56m53s)
    //    Latency: 636ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-104") >
    // 79.12,45.2 ("geo120-5") > 47.12,42.23 ("geo120-3") > 47.22,42.23 ("geo220#502") > 47.2,62.2
    // ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [multihop, direct, multihop, direct]
    //    InternalHops: [1-ff00:0:111: 4, 1-ff00:0:120: 5, 2-ff00:0:220: 3]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:120:
    // "asdf-1-120", 2-ff00:0:220: "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // [2] Hops: [1-ff00:0:112 495>113 1-ff00:0:130 105>1 1-ff00:0:120 2>501 2-ff00:0:220 500>2
    // 2-ff00:0:221]
    //    MTU: 1350
    //    NextHop: 127.0.0.57:31032
    //    Expires: 2025-03-19 22:15:33 +0000 UTC (5h56m54s)
    //    Latency: >404ms (information incomplete)
    //    Bandwidth: 50Kbit/s (information incomplete)
    //    Geo: [79.2,45.2 ("geo112-495") > N/A > N/A > 47.12,62.2 ("geo120-1") > 79.12,45.2
    // ("geo120-2") > 79.2,45.2 ("geo220#501") > 47.2,62.2 ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [unset, direct, opennet, direct]
    //    InternalHops: [1-ff00:0:120: 2, 2-ff00:0:220: 2]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:120: "asdf-1-120", 2-ff00:0:220:
    // "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // [3] Hops: [1-ff00:0:112 495>113 1-ff00:0:130 105>1 1-ff00:0:120 3>502 2-ff00:0:220 500>2
    // 2-ff00:0:221]
    //    MTU: 1400
    //    NextHop: 127.0.0.57:31032
    //    Expires: 2025-03-19 22:15:33 +0000 UTC (5h56m54s)
    //    Latency: >465ms (information incomplete)
    //    Bandwidth: 80Kbit/s (information incomplete)
    //    Geo: [79.2,45.2 ("geo112-495") > N/A > N/A > 47.12,62.2 ("geo120-1") > 47.12,42.23
    // ("geo120-3") > 47.22,42.23 ("geo220#502") > 47.2,62.2 ("geo220#500") > 79.2,45.2
    // ("geo212-2")]
    //    LinkType: [unset, direct, multihop, direct]
    //    InternalHops: [1-ff00:0:120: 3, 2-ff00:0:220: 3]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:120: "asdf-1-120", 2-ff00:0:220:
    // "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    // 6 Hops:
    // ...
  }

  @Test
  void testUpDown_tiny4_112_111() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/tiny4/", "ASff00_0_112")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:111"), dstAddress);
      assertEquals(2, paths.size());
      Path path = null;
      for (Path p : paths) {
        PathMetadata meta = p.getMetadata();
        List<PathMetadata.PathInterface> list = meta.getInterfacesList();
        if (list.get(0).getId() == 1) {
          path = p;
        }
      }
      assertNotNull(path);
      PathMetadata meta = path.getMetadata();
      assertEquals(4, meta.getInterfacesList().size());
      checkEqual(meta.getInterfacesList(), GET_ID, 1, 2, 1, 41);
      checkEqual(
          meta.getInterfacesList(),
          GET_IAS_AS,
          0x1_FF00_0000_0112L,
          0x1_FF00_0000_0110L,
          0x1_FF00_0000_0110L,
          0x1_FF00_0000_0111L);

      checkEqual(meta.getBandwidthList(), 112110L, 510L, 111110L);
      checkEqual(meta.getLatencyList(), 112, 10, 111);

      checkEqual(
          meta.getLinkTypeList(), PathMetadata.LinkType.MULTI_HOP, PathMetadata.LinkType.DIRECT);
      checkEqual(meta.getGeoList(), GET_ADDR, "geo112-1", "geo110-2", "geo110-1", "geo111-41");
      checkEqual(meta.getNotesList(), "asdf-1-112", "asdf-1-110", "asdf-1-111");
      checkEqual(meta.getInternalHopsList(), 10);
    }
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
