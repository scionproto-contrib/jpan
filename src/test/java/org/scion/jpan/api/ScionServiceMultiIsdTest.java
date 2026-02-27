// Copyright 2026 ETH Zurich
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

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.internal.PathProvider;
import org.scion.jpan.internal.PathProviderWithRefresh;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.crypto.Signed;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockNetwork2;
import org.scion.jpan.testutil.MockPathService;

@Disabled
class ScionServiceMultiIsdTest {

  private static final long AS_110 = ScionUtil.parseIA("1-ff00:0:110");
  private static final long AS_111 = ScionUtil.parseIA("1-ff00:0:111");
  private static final long AS_112 = ScionUtil.parseIA("1-ff00:0:112");
  private static final long AS_211 = ScionUtil.parseIA("2-ff00:0:211");
  private static final long AS_210 = ScionUtil.parseIA("2-ff00:0:210");
  private static final long AS_120 = ScionUtil.parseIA("1-ff00:0:120");
  private static final long AS_121 = ScionUtil.parseIA("1-ff00:0:121");

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  @Test
  void getPaths_pathServiceHandlesMultipleLocalIsdNumbers() {
    // We use 111 and 211 as identifiers for the local AS. In practice, only the ISD should
    // differ and the AS number should be the same, but that would require more changes to the
    // test classes.
    try (MockNetwork2 nw =
        MockNetwork2.startPS(MockNetwork2.Topology.MINIMAL, "ASff00_0_111", "ASff00_0_211")) {
      installPaths_111_211_to_121(nw);

      ScionService service = Scion.defaultService();
      List<Path> paths =
          service.getPaths(AS_121, new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));

      assertEquals(2, paths.size());
      Set<Integer> sourceIsds =
          paths.stream()
              .map(path -> ScionUtil.extractIsd(path.getMetadata().getSrcIdsAs()))
              .collect(Collectors.toSet());
      assertTrue(sourceIsds.contains(ScionUtil.extractIsd(AS_111)));
      assertTrue(sourceIsds.contains(ScionUtil.extractIsd(AS_211)));
    }
  }

  @Test
  void getPaths_pathServiceHandlesMultipleLocalSourceAses_tiny4() {
    try (MockNetwork2 nw =
        MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_111", "ASff00_0_112")) {
      ScionService service = Scion.defaultService();
      List<Path> paths =
          service.getPaths(AS_110, new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));

      assertEquals(2, paths.size());
      Set<Long> sourceAses = paths.stream().map(Path::getLocalIsdAs).collect(Collectors.toSet());
      assertTrue(sourceAses.contains(AS_111));
      assertTrue(sourceAses.contains(AS_112));
    }
  }

  @Test
  void send_DatagramChannel_HandlesMultipleLocalIsdNumbers() throws IOException {
    ManagedThread mt = ManagedThread.newBuilder().build();
    try (MockNetwork2 nw =
        MockNetwork2.startPS(MockNetwork2.Topology.MINIMAL, "ASff00_0_111", "ASff00_0_211")) {
      installPaths_111_211_to_121(nw);
      nw.startBorderRouters("ASff00_0_121");

      InetSocketAddress serverAddress =
          new InetSocketAddress(IPHelper.toInetAddress("m-host", "127.0.0.1"), 12345);
      MockDNS.install("1-ff00:0:121", serverAddress.getAddress());
      ScionService service = Scion.defaultService();
      List<Path> paths = service.getPaths(ScionUtil.parseIA("1-ff00:0:121"), serverAddress);

      // server thread
      mt.submit(
          mtn -> {
            ByteBuffer rcvBuffer = ByteBuffer.allocate(1000);
            try {
              try (ScionDatagramChannel server = ScionDatagramChannel.open()) {
                server.bind(serverAddress);
                System.err.println("---- rcv 0--- server=" + server.getLocalAddress());
                mtn.reportStarted();
                System.err.println("---- rcv 1---");
                ScionSocketAddress rcvAddr1 = server.receive(rcvBuffer);
                System.err.println("---- rcv 2---");
                assertEquals(50, rcvBuffer.remaining());
                System.err.println("---- rcv 3---");
                assertEquals(paths.get(0).getLocalIsdAs(), rcvAddr1.getIsdAs());
                System.err.println("---- rcv 4---");
                ScionSocketAddress rcvAddr2 = server.receive(rcvBuffer);
                System.err.println("---- rcv 5---");
                assertEquals(50, rcvBuffer.remaining());
                System.err.println("---- rcv 6---");
                assertEquals(paths.get(1).getLocalIsdAs(), rcvAddr2.getIsdAs());
                System.err.println("---- rcv 7---");
              }
            } catch (Exception e) {
              e.printStackTrace();
            }
          });

      System.err.println("---- send 1---");

      // 1st client - send()
      try (ScionDatagramChannel client = ScionDatagramChannel.open()) {
        ByteBuffer buffer = ByteBuffer.allocate(50);
        buffer.putInt(42);
        System.err.println("---- send 2--- client = " + paths.get(0).getFirstHopAddress());
        client.send(buffer, paths.get(0));
        // client.send(buffer, serverAddress);
        System.err.println("---- send 3--");
        client.send(buffer, paths.get(1));
      }
      System.err.println("---- send 4---");

      //     mt.join(10000000);

      // 2nd client - write (multi-ISD PAthProvider)
      // TODO
      //        ByteBuffer rcvBuffer = ByteBuffer.allocate(100);
      //        ScionSocketAddress rcvAddr1 = server.receive(rcvBuffer);
      //        assertEquals(50, rcvBuffer.remaining());
      //        assertEquals(paths.get(0).getLocalIsdAs(), rcvAddr1.getIsdAs());

      // 2st client - send()
      PathProvider pp = PathProviderWithRefresh.create(service, PathPolicy.FIRST);
      //        try (ScionDatagramChannel client = ScionDatagramChannel.open()) {
      //          client.connect(serverAddress);
      //          clientAddress1 = client.getLocalAddress();
      //        }
      // }
    } finally {
      mt.stopNow();
    }
    //    // We use 111 and 211 as identifiers for the local AS. In practice, only the ISD should
    //    // differ and the AS number should be the same, but that would require more changes to the
    //    // test classes.
    //    try (MockNetwork2 nw =
    //                 MockNetwork2.startPS(MockNetwork2.Topology.MINIMAL, "ASff00_0_111",
    // "ASff00_0_211")) {
    //      installPaths_111_211_to_121(nw);
    //
    //      ScionService service = Scion.defaultService();
    //      try (ScionDatagramChannel)
    //
    //      List<Path> paths =
    //              service.getPaths(AS_121, new InetSocketAddress(InetAddress.getLoopbackAddress(),
    // 12345));
    //
    //      assertEquals(2, paths.size());
    //      Set<Integer> sourceIsds =
    //              paths.stream()
    //                      .map(path -> ScionUtil.extractIsd(path.getMetadata().getSrcIdsAs()))
    //                      .collect(Collectors.toSet());
    //      assertTrue(sourceIsds.contains(ScionUtil.extractIsd(AS_111)));
    //      assertTrue(sourceIsds.contains(ScionUtil.extractIsd(AS_211)));
    //    }
  }

  @Disabled
  @Test
  void send_DatagramSocket_HandlesMultipleLocalIsdNumbers() throws IOException {
    int size = 10;
    try (MockNetwork2 nw =
        MockNetwork2.startPS(MockNetwork2.Topology.MINIMAL, "ASff00_0_111", "ASff00_0_211")) {
      installPaths_111_211_to_121(nw);

      InetSocketAddress serverAddress = MockNetwork.getTinyServerAddress();
      MockDNS.install("1-ff00:0:121", serverAddress.getAddress());
      try (ScionDatagramSocket server = new ScionDatagramSocket(serverAddress)) {
        assertFalse(server.isConnected()); // connected sockets do not have a cache
        InetSocketAddress clientAddress1;
        InetSocketAddress clientAddress2;

        // 1st client
        try (ScionDatagramSocket client =
            new ScionDatagramSocket(11111, InetAddress.getByAddress(new byte[] {127, 0, 0, 11}))) {
          client.connect(serverAddress);
          assertEquals(
              server.getLocalSocketAddress(), client.getConnectionPath().getRemoteSocketAddress());
          clientAddress1 = (InetSocketAddress) client.getLocalSocketAddress();
          DatagramPacket packet1 = new DatagramPacket(new byte[size], size, serverAddress);
          client.send(packet1);
        }

        DatagramPacket packet1 = new DatagramPacket(new byte[size], size, serverAddress);
        server.receive(packet1);
        // We only compare the port. Depending on the OS, the IP may have changed to 127.0.0.1 or
        // not.
        assertEquals(
            clientAddress1.getPort(), ((InetSocketAddress) packet1.getSocketAddress()).getPort());

        Path path1 = server.getCachedPath((InetSocketAddress) packet1.getSocketAddress());
        assertEquals(clientAddress1.getPort(), path1.getRemotePort());
      }
    }
  }

  InetSocketAddress toScionAddress(SocketAddress in) {
    try {
      InetAddress ipIn = ((InetSocketAddress) in).getAddress();
      InetAddress ipOut = InetAddress.getByAddress("myScionAddress", ipIn.getAddress());
      InetSocketAddress out = new InetSocketAddress(ipOut, ((InetSocketAddress) in).getPort());
      MockDNS.install("1-ff00:0:110", out.getAddress());
      return out;
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  private static void installPaths_111_211_to_121(MockNetwork2 nw) {
    nw.getPathServices().forEach(MockPathService::clearSegments);

    long now = Instant.now().getEpochSecond();
    // Source IA #1: 1-ff00:0:111 -> 1-ff00:0:110 -> 1-ff00:0:120 -> 1-ff00:0:121
    addUpSegment(nw, AS_111, AS_110, 2, 111, now);
    addCoreSegment(nw, AS_110, AS_120, 1, 2, now);

    // Source IA #2: 2-ff00:0:211 -> 2-ff00:0:210 -> 1-ff00:0:120 -> 1-ff00:0:121
    addUpSegment(nw, AS_211, AS_210, 2, 1111, now);
    addCoreSegment(nw, AS_210, AS_120, 1, 2, now);

    // Shared destination down segment.
    addDownSegment(nw, AS_120, AS_121, 121, 41, now);
  }

  private static Seg.SegmentsResponse buildResponse(
      Seg.SegmentType type, Seg.PathSegment... segments) {
    Seg.SegmentsResponse.Builder replyBuilder = Seg.SegmentsResponse.newBuilder();
    Seg.SegmentsResponse.Segments entries =
        Seg.SegmentsResponse.Segments.newBuilder().addAllSegments(Arrays.asList(segments)).build();
    replyBuilder.putSegments(type.getNumber(), entries);
    return replyBuilder.build();
  }

  private static Seg.PathSegment buildSegment(
      long srcIA, long dstIA, int dstIngress, int srcEgress, long timestamp) {
    Seg.ASEntry srcAsEntry = buildAsEntry(srcIA, 3, srcEgress, 4567, 2345);
    Seg.ASEntry dstAsEntry = buildAsEntry(dstIA, dstIngress, 0, 3456, 1234);
    Seg.SegmentInformation info =
        Seg.SegmentInformation.newBuilder().setTimestamp(timestamp).build();
    return Seg.PathSegment.newBuilder()
        .addAsEntries(srcAsEntry)
        .addAsEntries(dstAsEntry)
        .setSegmentInfo(info.toByteString())
        .build();
  }

  private static void addUpSegment(
      MockNetwork2 nw, long srcIA, long dstIA, int dstIngress, int srcEgress, long timestamp) {
    nw.addResponse(
        srcIA,
        false,
        dstIA,
        true,
        buildResponse(
            Seg.SegmentType.SEGMENT_TYPE_UP,
            buildSegment(srcIA, dstIA, dstIngress, srcEgress, timestamp)));
  }

  private static void addCoreSegment(
      MockNetwork2 nw, long srcIA, long dstIA, int dstIngress, int srcEgress, long timestamp) {
    nw.addResponse(
        srcIA,
        true,
        dstIA,
        true,
        buildResponse(
            Seg.SegmentType.SEGMENT_TYPE_CORE,
            buildSegment(srcIA, dstIA, dstIngress, srcEgress, timestamp)));
  }

  private static void addDownSegment(
      MockNetwork2 nw, long srcIA, long dstIA, int dstIngress, int srcEgress, long timestamp) {
    nw.addResponse(
        srcIA,
        true,
        dstIA,
        false,
        buildResponse(
            Seg.SegmentType.SEGMENT_TYPE_DOWN,
            buildSegment(srcIA, dstIA, dstIngress, srcEgress, timestamp)));
  }

  private static Seg.ASEntry buildAsEntry(
      long isdAs, int ingress, int egress, int mtu, int ingressMtu) {
    ByteString mac = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
    Seg.HopField hopField =
        Seg.HopField.newBuilder()
            .setMac(mac)
            .setIngress(ingress)
            .setEgress(egress)
            .setExpTime(64)
            .build();
    Seg.HopEntry hopEntry =
        Seg.HopEntry.newBuilder().setHopField(hopField).setIngressMtu(ingressMtu).build();
    Seg.ASEntrySignedBody signedBody =
        Seg.ASEntrySignedBody.newBuilder()
            .setIsdAs(isdAs)
            .setHopEntry(hopEntry)
            .setMtu(mtu)
            .build();
    Signed.HeaderAndBodyInternal habi =
        Signed.HeaderAndBodyInternal.newBuilder().setBody(signedBody.toByteString()).build();
    Signed.SignedMessage signedMessage =
        Signed.SignedMessage.newBuilder().setHeaderAndBody(habi.toByteString()).build();
    return Seg.ASEntry.newBuilder().setSigned(signedMessage).build();
  }
}
