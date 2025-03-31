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
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.testutil.MockNetwork2;

class SegmentTiny4bMetadataTest {

  protected static final long AS_110 = ScionUtil.parseIA("1-ff00:0:110");
  protected static final long AS_111 = ScionUtil.parseIA("1-ff00:0:111");
  protected static final long AS_112 = ScionUtil.parseIA("1-ff00:0:112");
  protected static final long AS_120 = ScionUtil.parseIA("1-ff00:0:120");
  protected static final long AS_121 = ScionUtil.parseIA("1-ff00:0:121");

  private static final String TOPO_DIR = "topologies/tiny4b/";
  private static final String TOPO_110 = TOPO_DIR + "ASff00_0_110/topology.json";
  private static final String TOPO_112 = TOPO_DIR + "ASff00_0_112/topology.json";
  private static final String TOPO_120 = TOPO_DIR + "ASff00_0_120/topology.json";

  @Test
  void testCore_110_120() throws IOException {
    try (MockNetwork2 nw = MockNetwork2.start(TOPO_DIR, "ASff00_0_110")) {

      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_110)) {
        List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_110, AS_120);
        assertNotNull(paths);
        assertEquals(1, paths.size());

        Daemon.Path path = paths.get(0);
        assertEquals(1, path.getBandwidthList().size());
        assertEquals(110120, path.getBandwidthList().get(0));
        assertEquals(1, path.getLatencyList().size());
        assertEquals(120, path.getLatencyList().get(0).getNanos() / 1_000_000);
      }
    }
  }

  @Test
  void testCore_120_110() throws IOException {
    try (MockNetwork2 nw = MockNetwork2.start(TOPO_DIR, "ASff00_0_120")) {

      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_120)) {
        List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_120, AS_110);
        assertNotNull(paths);
        assertEquals(1, paths.size());

        Daemon.Path path = paths.get(0);
        assertEquals(1, path.getBandwidthList().size());
        assertEquals(110120, path.getBandwidthList().get(0));
        assertEquals(1, path.getLatencyList().size());
        assertEquals(120, path.getLatencyList().get(0).getNanos() / 1_000_000);
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

    //    PathSeg: size=9
    //    SegInfo:  ts=2025-03-21T10:05:09Z  id=13212
    //    AS: signed=159   signature size=70
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T10:05:09.523003643Z
    // meta=0  data=9
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
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  time=2025-03-21T10:05:12.049697132Z
    // meta=0  data=238
    //    AS Body: IA=1-ff00:0:120 nextIA=0-0:0:0  mtu=1472
    //    HopEntry: true mtu=1472
    //    HopField: exp=63 ingress=6 egress=0
    //    Extensions: true/false/false
    //    Static: latencies=0/0  bandwidth=0/0  geo=1  interfaces=0  note='asdf-1-120'
    //    geo: 6 -> lon: 42.23; lat: 47.12; addr: geo120-6
    //    note: asdf-1-120
  }

  @Test
  void testDown_120_121() throws IOException {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start(TOPO_DIR, "ASff00_0_120")) {

      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_120)) {
        List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_120, AS_121);
        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        Daemon.Path path = paths.get(0);
        RequestPath rp = PackageVisibilityHelper.createRequestPath(path, AS_110, dstAddress);
        System.out.println("Path: " + ScionUtil.toStringPath(rp.getRawPath()));
        System.out.println("Path: " + ScionUtil.toStringPath(rp.getMetadata()));
        for (Object o : path.getBandwidthList()) {
          System.out.println("bw: " + o);
        }
        for (Object o : path.getLatencyList()) {
          System.out.println("lat: " + o);
        }
        assertEquals(1, path.getBandwidthList().size());
        assertEquals(20000, path.getBandwidthList().get(0));
        assertEquals(1, path.getLatencyList().size());
        assertEquals(121, path.getLatencyList().get(0).getNanos() / 1_000_000);
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

    // ProtobufSegmentDemo:
    //    PathSeg: size=10
    //    SegInfo:  ts=2025-03-21T10:01:37Z  id=18555
    //    AS: signed=168   signature size=72
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  ... meta=0  data=10
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
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  ... meta=0  data=250
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
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  ... meta=0  data=10
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
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  ... meta=0  data=249
    //    AS Body: IA=1-ff00:0:120 nextIA=0-0:0:0  mtu=1472
    //    HopEntry: true mtu=1350
    //    HopField: exp=63 ingress=2 egress=0
    //    Extensions: true/false/false
    //    Static: latencies=0/0  bandwidth=0/0  geo=1  interfaces=0  note='asdf-1-120'
    //    geo: 2 -> lon: 45.2; lat: 79.12; addr: geo120-2
    //    note: asdf-1-120
  }

  @Test
  void testUp_112_110() throws IOException {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start(TOPO_DIR, "ASff00_0_112")) {
      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_112)) {
        List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_112, AS_110);
        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        Daemon.Path path = paths.get(0);
        RequestPath rp = PackageVisibilityHelper.createRequestPath(path, AS_110, dstAddress);
        System.out.println("Path: " + ScionUtil.toStringPath(rp.getRawPath()));
        System.out.println("Path: " + ScionUtil.toStringPath(rp.getMetadata()));
        for (Object o : path.getBandwidthList()) {
          System.out.println("bw: " + o);
        }
        for (Object o : path.getLatencyList()) {
          System.out.println("lat: " + o);
        }
        assertEquals(3, path.getBandwidthList().size());
        assertEquals(112, path.getBandwidthList().get(0));
        assertEquals(511, path.getBandwidthList().get(1));
        assertEquals(11000, path.getBandwidthList().get(2));
        assertEquals(3, path.getLatencyList().size());
        assertEquals(112, path.getLatencyList().get(0).getNanos() / 1_000_000);
        assertEquals(12, path.getLatencyList().get(1).getNanos() / 1_000_000);
        assertEquals(111, path.getLatencyList().get(2).getNanos() / 1_000_000);
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

    //    wHF: 2  aIF=true   ->  [11>0 0>0] 1-ff00:0:112
    //    lat-intra1? 0 -> null
    //    lat-intra2? 11 -> null
    //    bw-intra1? 0 -> null
    //    bw-intra2? 11 -> null
    //    lat inter?: 0  null
    //    bw-inter?: 0  null
    //    lat inter2?: 11  null
    //    bw-inter2?: 11  null
    //    wHF: 1  aIF=true   ->  [11>12 10>0] 1-ff00:0:111
    //    lat-intra1? 12 -> null
    //    lat-intra2? 10 -> 12000
    //    bw-intra1? 12 -> null
    //    bw-intra2? 10 -> 512
    //    lat inter?: 12  112000
    //    bw-inter?: 12  112
    //    lat inter2?: 10  null
    //    bw-inter2?: 10  null
    //    wHF: 0  aIF=false   ->  [11>12 10>11] 1-ff00:0:110
    //    lat-intra1? 11 -> null
    //    lat-intra2? 0 -> null
    //    bw-intra1? 11 -> null
    //    bw-intra2? 0 -> null
    //    lat inter?: 11  111000
    //    bw-inter?: 11  111
    //    lat inter2?: 0  null
    //    bw-inter2?: 0  null
    //    Available paths to 1-ff00:0:110
    //            [0] Hops: [1-ff00:0:112 11>12 1-ff00:0:111 10>11 1-ff00:0:110]
    //    MTU: 1280
    //    NextHop: 127.0.0.33
    //    Expires: 2025-03-25 18:16:40 +0000 UTC (5h59m50s)
    //    Latency: 245ms
    //    Bandwidth: 111KBit/s
    //    Geo: [79.112,45.112 ("geo112-11") > 47.111,62.112 ("geo111-112") > 47.111,42.11
    // ("geo111-110") > 47.11,42.111 ("geo110-111")]
    //    LinkType: [unset, direct, direct]
    //    Notes: [1-ff00:0:112: "asdf-1-112", 1-ff00:0:111: "asdf-1-111", 1-ff00:0:110:
    // "asdf-1-110"]
    //    SupportsEPIC: false
    //    Status: unknown
    //    LocalIP: 127.0.0.1

  }

  @Test
  void testDown_110_112() throws IOException {
    InetSocketAddress dstAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try (MockNetwork2 nw = MockNetwork2.start(TOPO_DIR, "ASff00_0_110")) {
      try (Scion.CloseableService ss = Scion.newServiceWithTopologyFile(TOPO_110)) {
        List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_110, AS_112);
        assertNotNull(paths);
        assertFalse(paths.isEmpty());

        Daemon.Path path = paths.get(0);
        RequestPath rp = PackageVisibilityHelper.createRequestPath(path, AS_110, dstAddress);
        System.out.println("Path: " + ScionUtil.toStringPath(rp.getRawPath()));
        System.out.println("Path: " + ScionUtil.toStringPath(rp.getMetadata()));
        for (Object o : path.getBandwidthList()) {
          System.out.println("bw: " + o);
        }
        for (Object o : path.getLatencyList()) {
          System.out.println("lat: " + o);
        }
        assertEquals(3, path.getBandwidthList().size());
        assertEquals(11000, path.getBandwidthList().get(0));
        assertEquals(511, path.getBandwidthList().get(1));
        assertEquals(112, path.getBandwidthList().get(2));
        assertEquals(3, path.getLatencyList().size());
        assertEquals(111, path.getLatencyList().get(0).getNanos() / 1_000_000);
        assertEquals(12, path.getLatencyList().get(1).getNanos() / 1_000_000);
        assertEquals(112, path.getLatencyList().get(2).getNanos() / 1_000_000);
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

    //    Requesting segments: 1-ff00:0:110 -> 1-ff00:0:112
    //    SEG: key=SEGMENT_TYPE_DOWN -> n=1
    //    PathSeg: size=10
    //    SegInfo:  ts=2025-03-25T16:16:17Z  id=58187
    //    AS: signed=181   signature size=72
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  ... meta=0  data=10
    //    AS Body: IA=1-ff00:0:110 nextIA=1-ff00:0:111  mtu=1400
    //    HopEntry: true mtu=0
    //    HopField: exp=63 ingress=0 egress=11
    //    Extensions: true/false/false
    //    Static: latencies=1/1  bandwidth=1/1  geo=1  interfaces=1  note='asdf-1-110'
    //    latency intra: 20 -> 10.0 ms
    //    latency inter: 11 -> 111.0 ms
    //    bw intra: 20 -> 510
    //    bw inter: 11 -> 111
    //    geo: 11 -> lon: 42.111; lat: 47.11; addr: geo110-111
    //    link types: 11 -> LINK_TYPE_DIRECT
    //    note: asdf-1-110
    //    internal hops: 20 -> 10
    //    AS: signed=216   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  ... meta=0  data=263
    //    AS Body: IA=1-ff00:0:111 nextIA=1-ff00:0:112  mtu=1472
    //    HopEntry: true mtu=1280
    //    HopField: exp=63 ingress=10 egress=12
    //    Extensions: true/false/false
    //    Static: latencies=1/1  bandwidth=1/1  geo=2  interfaces=1  note='asdf-1-111'
    //    latency intra: 10 -> 12.0 ms
    //    latency inter: 12 -> 112.0 ms
    //    bw intra: 10 -> 512
    //    bw inter: 12 -> 112
    //    geo: 10 -> lon: 42.11; lat: 47.111; addr: geo111-110
    //    geo: 12 -> lon: 62.112; lat: 47.111; addr: geo111-112
    //    link types: 12 -> LINK_TYPE_DIRECT
    //    note: asdf-1-111
    //    internal hops: 10 -> 11
    //    AS: signed=132   signature size=71
    //    AS header: SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256  ... meta=0  data=550
    //    AS Body: IA=1-ff00:0:112 nextIA=0-0:0:0  mtu=1472
    //    HopEntry: true mtu=1280
    //    HopField: exp=63 ingress=11 egress=0
    //    Extensions: true/false/false
    //    Static: latencies=0/0  bandwidth=0/0  geo=1  interfaces=0  note='asdf-1-112'
    //    geo: 11 -> lon: 45.112; lat: 79.112; addr: geo112-11
    //    note: asdf-1-112
  }
}
