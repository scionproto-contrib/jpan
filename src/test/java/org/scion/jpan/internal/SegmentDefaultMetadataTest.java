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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.testutil.MockControlServer;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockNetwork2;

import static org.junit.jupiter.api.Assertions.*;

class SegmentDefaultMetadataTest {

  /** ISD 1 - core AS */
  protected static final long AS_110 = ScionUtil.parseIA("1-ff00:0:110");

  /** ISD 1 - non-core AS */
  protected static final long AS_111 = ScionUtil.parseIA("1-ff00:0:111");

  /** ISD 1 - non-core AS */
  protected static final long AS_112 = ScionUtil.parseIA("1-ff00:0:112");

  /** ISD 1 - core AS */
  protected static final long AS_120 = ScionUtil.parseIA("1-ff00:0:120");

  /** ISD 1 - non-core AS */
  protected static final long AS_121 = ScionUtil.parseIA("1-ff00:0:121");

  /** ISD 2 - core AS */
  protected static final long AS_210 = ScionUtil.parseIA("2-ff00:0:210");
  protected static final long AS_220 = ScionUtil.parseIA("2-ff00:0:220");

  /** ISD 2 - non-core AS */
  protected static final long AS_211 = ScionUtil.parseIA("2-ff00:0:211");

  private static final String TOPO_112 = "topologies/default/ASff00_0_112/topology.json";
  private static final String TOPO_120 = "topologies/default/ASff00_0_120/topology.json";

  @Test
  void testCore_120_110() throws IOException {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_120")) {

      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_120)) {
        List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_120, AS_110);
        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        Daemon.Path path = paths.get(0);
        assertEquals(1, path.getBandwidthList().size());
        for (Object o : path.getBandwidthList()) {
          System.out.println("pb: " + o);
        }
        assertEquals(100, path.getBandwidthList().get(0));
        assertEquals(1, path.getLatencyList().size());
        System.out.println("LAT: " + path.getLatencyList().get(0));
        assertEquals(101, path.getLatencyList().get(0).getNanos()/1_000_000);
      }

      MockControlServer cs = nw.getControlServer();

      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:130"), dstAddress);
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

    //    PathSeg: size=9
    //    SegInfo:  ts=2025-03-21T10:05:09Z  id=13212
    //    AS: signed=159   signature size=70
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T10:05:09.523003643Z  meta=0  data=9
    //    AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:120  mtu=1472
    //    HopEntry: true mtu=0
    //    HopField: exp=63 ingress=0 egress=1
    //    Extensions: true/false/false
    //    Static: latencies=0/1  bandwidth=0/1  geo=1  interfaces=1  note='asdf-1-110'
    //    latency inter: 1 -> 101.0 ms
    //    bw inter: 1 -> 100
    //    geo: 1 -> lon: 62.2; lat: 47.2; addr: geo110-1
    //    link types: 1 -> LINK_TYPE_DIRECT
    //    note: asdf-1-110
    //    AS: signed=130   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T10:05:12.049697132Z  meta=0  data=238
    //    AS Body: IA=1-ff00:0:120 nextIA=0-0:0:0  mtu=1472
    //    HopEntry: true mtu=1472
    //    HopField: exp=63 ingress=6 egress=0
    //    Extensions: true/false/false
    //    Static: latencies=0/0  bandwidth=0/0  geo=1  interfaces=0  note='asdf-1-120'
    //    geo: 6 -> lon: 42.23; lat: 47.12; addr: geo120-6
    //    note: asdf-1-120
  }

  @Test
  void testCore_120_220() throws IOException {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_120")) {

      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_120)) {
        List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_120, AS_220);
        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        Daemon.Path path = paths.get(0);
        assertEquals(1, path.getBandwidthList().size());
        for (Object o : path.getBandwidthList()) {
          System.out.println("pb: " + o);
        }
        assertEquals(100, path.getBandwidthList().get(0));
        assertEquals(1, path.getLatencyList().size());
        System.out.println("LAT: " + path.getLatencyList().get(0));
        assertEquals(101, path.getLatencyList().get(0).getNanos()/1_000_000);
      }

      MockControlServer cs = nw.getControlServer();

      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:130"), dstAddress);
      assertEquals(0, paths.size());
    }

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

    // ProtobufSegmentDemo:
    //    PathSeg: size=10
    //    SegInfo:  ts=2025-03-21T10:01:37Z  id=18555
    //    AS: signed=168   signature size=72
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T10:01:37.630857126Z  meta=0  data=10
    //    AS Body: IA=2-ff00:0:220 nextIA=1-ff00:0:120  mtu=1472
    //    HopEntry: true mtu=0
    //    HopField: exp=63 ingress=0 egress=502
    //    Extensions: true/false/false
    //    Static: latencies=0/1  bandwidth=0/1  geo=1  interfaces=1  note='asdf-2-220'
    //    latency inter: 502 -> 103.0 ms
    //    bw inter: 502 -> 100
    //    geo: 502 -> lon: 42.23; lat: 47.22; addr: geo220#502
    //    link types: 502 -> LINK_TYPE_MULTI_HOP
    //    note: asdf-2-220
    //    AS: signed=131   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T10:01:39.560685521Z  meta=0  data=250
    //    AS Body: IA=1-ff00:0:120 nextIA=0-0:0:0  mtu=1472
    //    HopEntry: true mtu=1400
    //    HopField: exp=63 ingress=3 egress=0
    //    Extensions: true/false/false
    //    Static: latencies=0/0  bandwidth=0/0  geo=1  interfaces=0  note='asdf-1-120'
    //    geo: 3 -> lon: 42.23; lat: 47.12; addr: geo120-3
    //    note: asdf-1-120
    //    PathSeg: size=10
    //    SegInfo:  ts=2025-03-21T10:01:37Z  id=26399
    //    AS: signed=168   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T10:01:37.629688488Z  meta=0  data=10
    //    AS Body: IA=2-ff00:0:220 nextIA=1-ff00:0:120  mtu=1472
    //    HopEntry: true mtu=0
    //    HopField: exp=63 ingress=0 egress=501
    //    Extensions: true/false/false
    //    Static: latencies=0/1  bandwidth=0/1  geo=1  interfaces=1  note='asdf-2-220'
    //    latency inter: 501 -> 102.0 ms
    //    bw inter: 501 -> 100
    //    geo: 501 -> lon: 45.2; lat: 79.2; addr: geo220#501
    //    link types: 501 -> LINK_TYPE_OPEN_NET
    //    note: asdf-2-220
    //    AS: signed=131   signature size=69
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T10:01:39.560947350Z  meta=0  data=249
    //    AS Body: IA=1-ff00:0:120 nextIA=0-0:0:0  mtu=1472
    //    HopEntry: true mtu=1350
    //    HopField: exp=63 ingress=2 egress=0
    //    Extensions: true/false/false
    //    Static: latencies=0/0  bandwidth=0/0  geo=1  interfaces=0  note='asdf-1-120'
    //    geo: 2 -> lon: 45.2; lat: 79.12; addr: geo120-2
    //    note: asdf-1-120
  }


  @Test
  void testUp_112_120() throws IOException {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start("topologies/default/", "ASff00_0_112")) {

      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_112)) {
        List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_112, AS_120);
        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        Daemon.Path path = paths.get(0);
        assertEquals(3, path.getBandwidthList().size());
        for (Object o : path.getBandwidthList()) {
          System.out.println("pb: " + o);
        }
        assertEquals(100, path.getBandwidthList().get(0));
        assertEquals(1, path.getLatencyList().size());
        System.out.println("LAT: " + path.getLatencyList().get(0));
        assertEquals(101, path.getLatencyList().get(0).getNanos()/1_000_000);
      }

      MockControlServer cs = nw.getControlServer();

      ScionService service = Scion.defaultService();

      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:130"), dstAddress);
      assertEquals(0, paths.size());
    }

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
  void testDown_120_112() {
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

    //    PathSeg: size=10
    //    SegInfo:  ts=2025-03-21T11:35:37Z  id=36331
    //    AS: signed=241   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T11:35:37.051852243Z  meta=0  data=10
    //    AS Body: IA=1-ff00:0:120 nextIA=1-ff00:0:111  mtu=1472
    //    HopEntry: true mtu=0
    //    HopField: exp=63 ingress=0 egress=5
    //    Extensions: true/false/false
    //    Static: latencies=4/1  bandwidth=4/1  geo=1  interfaces=1  note='asdf-1-120'
    //    latency intra: 1 -> 50.0 ms
    //    latency intra: 2 -> 50.0 ms
    //    latency intra: 3 -> 60.0 ms
    //    latency intra: 6 -> 50.0 ms
    //    latency inter: 5 -> 105.0 ms
    //    bw intra: 2 -> 50
    //    bw intra: 3 -> 50
    //    bw intra: 6 -> 60
    //    bw intra: 1 -> 50
    //    bw inter: 5 -> 100
    //    geo: 5 -> lon: 45.2; lat: 79.12; addr: geo120-5
    //    link types: 5 -> LINK_TYPE_DIRECT
    //    note: asdf-1-120
    //    AS: signed=516   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T11:35:39.024261600Z  meta=0  data=322
    //    AS Body: IA=1-ff00:0:111 nextIA=1-ff00:0:112  mtu=1472
    //    HopEntry: true mtu=1472
    //    HopField: exp=63 ingress=104 egress=103
    //    Extensions: true/false/false
    //    Static: latencies=4/4  bandwidth=4/4  geo=5  interfaces=4  note='asdf-1-111'
    //    latency intra: 100 -> 83.0 ms
    //    latency intra: 101 -> 83.0 ms
    //    latency intra: 102 -> 83.0 ms
    //    latency intra: 104 -> 84.0 ms
    //    latency inter: 102 -> 102.0 ms
    //    latency inter: 103 -> 103.0 ms
    //    latency inter: 100 -> 100.0 ms
    //    latency inter: 101 -> 101.0 ms
    //    bw intra: 104 -> 40
    //    bw intra: 100 -> 50
    //    bw intra: 101 -> 51
    //    bw intra: 102 -> 52
    //    bw inter: 100 -> 100
    //    bw inter: 101 -> 100
    //    bw inter: 102 -> 100
    //    bw inter: 103 -> 100
    //    geo: 100 -> lon: 42.23; lat: 47.12; addr: geo111-100
    //    geo: 101 -> lon: 62.2; lat: 47.12; addr: geo111-101
    //    geo: 102 -> lon: 45.2; lat: 79.12; addr: geo111-102
    //    geo: 103 -> lon: 42.23; lat: 47.12; addr: geo111-103
    //    geo: 104 -> lon: 62.2; lat: 47.12; addr: geo111-104
    //    link types: 102 -> LINK_TYPE_OPEN_NET
    //    link types: 103 -> LINK_TYPE_MULTI_HOP
    //    link types: 100 -> LINK_TYPE_DIRECT
    //    link types: 101 -> LINK_TYPE_DIRECT
    //    note: asdf-1-111
    //    AS: signed=134   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T11:35:44.050599857Z  meta=0  data=909
    //    AS Body: IA=1-ff00:0:112 nextIA=0-0:0:0  mtu=1450
    //    HopEntry: true mtu=1472
    //    HopField: exp=63 ingress=494 egress=0
    //    Extensions: true/false/false
    //    Static: latencies=0/0  bandwidth=0/0  geo=1  interfaces=0  note='asdf-1-112'
    //    geo: 494 -> lon: 62.2; lat: 47.2; addr: geo112-494
    //    note: asdf-1-112
  }
}
