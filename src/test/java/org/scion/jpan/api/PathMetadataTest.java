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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.testutil.MockControlServer;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockNetwork2;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathMetadataTest {

  @BeforeEach
  void beforeEach() {
    MockNetwork.startTiny();
  }

  @AfterEach
  void afterEach() {
    MockNetwork.stopTiny();
  }

  @Test
  void test() {
    MockControlServer cs = MockControlServer.start(12345);
    Seg.SegmentsResponse.Builder respUp = Seg.SegmentsResponse.newBuilder();
    Seg.SegmentsResponse.Segments.Builder segUp = Seg.SegmentsResponse.Segments.newBuilder();
    Seg.PathSegment.Builder pSegUp = Seg.PathSegment.newBuilder();
    // pSegUp.setSegmentInfo();
    segUp.addSegments(pSegUp.build());
    respUp.putSegments(Seg.SegmentType.SEGMENT_TYPE_UP_VALUE, segUp.build());
    // TODO cs.addResponse();
  }

  @Test
  void testCore_120_110() {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_120")) {
      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:110"), dstAddress);
      assertEquals(0, paths.size());
    }

    // scion showpaths 1-ff00:0:110 --isd-as 1-ff00:0:120 --sciond 127.0.0.69:30255 --extended
    //Available paths to 1-ff00:0:110
    //2 Hops:
    //[0] Hops: [1-ff00:0:120 6>1 1-ff00:0:110]
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
    //3 Hops:
    // ...
  }

  @Test
  void testCore_120_220() {
    // scion showpaths 2-ff00:0:220 --isd-as 1-ff00:0:120 --sciond 127.0.0.69:30255 --extended
    //Available paths to 2-ff00:0:220
    //2 Hops:
    //[0] Hops: [1-ff00:0:120 2>501 2-ff00:0:220]
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
    //[1] Hops: [1-ff00:0:120 3>502 2-ff00:0:220]
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
    //4 Hops:
  }


  @Test
  void test112_120() {
    // scion showpaths 1-ff00:0:120 --isd-as 1-ff00:0:112 --sciond 127.0.0.60:30255 --extended
    //Available paths to 1-ff00:0:120
    //3 Hops:
    //[0] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 104>5 1-ff00:0:120]
    //    MTU: 1450
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:24:51 +0000 UTC (5h59m51s)
    //    Latency: 292ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-104") > 79.12,45.2 ("geo120-5")]
    //    LinkType: [multihop, direct]
    //    InternalHops: [1-ff00:0:111: 4]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:120: "asdf-1-120"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    //[1] Hops: [1-ff00:0:112 495>113 1-ff00:0:130 105>1 1-ff00:0:120]
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
    //4 Hops:
    // ...
  }

  @Test
  void test120_112() {
    // scion showpaths 1-ff00:0:112 --isd-as 1-ff00:0:120 --sciond 127.0.0.69:30255 --extended
    //Available paths to 1-ff00:0:112
    //3 Hops:
    //[0] Hops: [1-ff00:0:120 1>105 1-ff00:0:130 113>495 1-ff00:0:112]
    //    MTU: 1450
    //    NextHop: 127.0.0.65:31010
    //    Expires: 2025-03-19 22:27:19 +0000 UTC (5h59m49s)
    //    Geo: [47.12,62.2 ("geo120-1") > N/A > N/A > 79.2,45.2 ("geo112-495")]
    //    Notes: [1-ff00:0:120: "asdf-1-120", 1-ff00:0:112: "asdf-1-112"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    //[1] Hops: [1-ff00:0:120 5>104 1-ff00:0:111 103>494 1-ff00:0:112]
    //    MTU: 1450
    //    NextHop: 127.0.0.67:31014
    //    Expires: 2025-03-19 22:27:20 +0000 UTC (5h59m50s)
    //    Latency: 292ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [79.12,45.2 ("geo120-5") > 47.12,62.2 ("geo111-104") > 47.12,42.23 ("geo111-103") > 47.2,62.2 ("geo112-494")]
    //    LinkType: [direct, multihop]
    //    InternalHops: [1-ff00:0:111: 4]
    //    Notes: [1-ff00:0:120: "asdf-1-120", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:112: "asdf-1-112"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    //4 Hops:
    // ...

  }

  @Test
  void test112_221() {
    // scion showpaths 2-ff00:0:221 --isd-as 1-ff00:0:112 --sciond 127.0.0.60:30255 --extended
    //Available paths to 2-ff00:0:221
    //5 Hops:
    //[0] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 104>5 1-ff00:0:120 2>501 2-ff00:0:220 500>2 2-ff00:0:221]
    //    MTU: 1350
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:15:32 +0000 UTC (5h56m53s)
    //    Latency: 595ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-104") > 79.12,45.2 ("geo120-5") > 79.12,45.2 ("geo120-2") > 79.2,45.2 ("geo220#501") > 47.2,62.2 ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [multihop, direct, opennet, direct]
    //    InternalHops: [1-ff00:0:111: 4, 1-ff00:0:120: 5, 2-ff00:0:220: 2]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:120: "asdf-1-120", 2-ff00:0:220: "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    //[1] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 104>5 1-ff00:0:120 3>502 2-ff00:0:220 500>2 2-ff00:0:221]
    //    MTU: 1400
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:15:32 +0000 UTC (5h56m53s)
    //    Latency: 636ms
    //    Bandwidth: 40Kbit/s
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-104") > 79.12,45.2 ("geo120-5") > 47.12,42.23 ("geo120-3") > 47.22,42.23 ("geo220#502") > 47.2,62.2 ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [multihop, direct, multihop, direct]
    //    InternalHops: [1-ff00:0:111: 4, 1-ff00:0:120: 5, 2-ff00:0:220: 3]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:120: "asdf-1-120", 2-ff00:0:220: "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    //[2] Hops: [1-ff00:0:112 495>113 1-ff00:0:130 105>1 1-ff00:0:120 2>501 2-ff00:0:220 500>2 2-ff00:0:221]
    //    MTU: 1350
    //    NextHop: 127.0.0.57:31032
    //    Expires: 2025-03-19 22:15:33 +0000 UTC (5h56m54s)
    //    Latency: >404ms (information incomplete)
    //    Bandwidth: 50Kbit/s (information incomplete)
    //    Geo: [79.2,45.2 ("geo112-495") > N/A > N/A > 47.12,62.2 ("geo120-1") > 79.12,45.2 ("geo120-2") > 79.2,45.2 ("geo220#501") > 47.2,62.2 ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [unset, direct, opennet, direct]
    //    InternalHops: [1-ff00:0:120: 2, 2-ff00:0:220: 2]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:120: "asdf-1-120", 2-ff00:0:220: "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    //[3] Hops: [1-ff00:0:112 495>113 1-ff00:0:130 105>1 1-ff00:0:120 3>502 2-ff00:0:220 500>2 2-ff00:0:221]
    //    MTU: 1400
    //    NextHop: 127.0.0.57:31032
    //    Expires: 2025-03-19 22:15:33 +0000 UTC (5h56m54s)
    //    Latency: >465ms (information incomplete)
    //    Bandwidth: 80Kbit/s (information incomplete)
    //    Geo: [79.2,45.2 ("geo112-495") > N/A > N/A > 47.12,62.2 ("geo120-1") > 47.12,42.23 ("geo120-3") > 47.22,42.23 ("geo220#502") > 47.2,62.2 ("geo220#500") > 79.2,45.2 ("geo212-2")]
    //    LinkType: [unset, direct, multihop, direct]
    //    InternalHops: [1-ff00:0:120: 3, 2-ff00:0:220: 3]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:120: "asdf-1-120", 2-ff00:0:220: "asdf-2-220", 2-ff00:0:221: "asdf-2-212"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    //6 Hops:
    //...
  }

  @Test
  void test112_222() {
    // scion showpaths 2-ff00:0:222 --isd-as 1-ff00:0:112 --sciond 127.0.0.60:30255 --extended
    //Available paths to 2-ff00:0:222
    //4 Hops:
    //[0] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 101>5 2-ff00:0:211 4>301 2-ff00:0:222]
    //    MTU: 1450
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:15:32 +0000 UTC (5h59m37s)
    //    Latency: >287ms (information incomplete)
    //    Bandwidth: 51Kbit/s (information incomplete)
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 47.12,62.2 ("geo111-101") > N/A > N/A > N/A]
    //    LinkType: [multihop, direct, unset]
    //    InternalHops: [1-ff00:0:111: 3]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    //[1] Hops: [1-ff00:0:112 494>103 1-ff00:0:111 102>6 2-ff00:0:211 4>301 2-ff00:0:222]
    //    MTU: 1450
    //    NextHop: 127.0.0.58:31034
    //    Expires: 2025-03-19 22:15:32 +0000 UTC (5h59m37s)
    //    Latency: >288ms (information incomplete)
    //    Bandwidth: 52Kbit/s (information incomplete)
    //    Geo: [47.2,62.2 ("geo112-494") > 47.12,42.23 ("geo111-103") > 79.12,45.2 ("geo111-102") > N/A > N/A > N/A]
    //    LinkType: [multihop, opennet, unset]
    //    InternalHops: [1-ff00:0:111: 3]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111"]
    //    SupportsEPIC: false
    //    Status: alive
    //    LocalIP: 127.0.0.1
    //6 Hops:
    //..

  }
}
