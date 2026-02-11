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

package org.scion.jpan.testutil;

import com.google.protobuf.ByteString;
import fi.iki.elonen.NanoHTTPD;
import io.grpc.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.crypto.Signed;
import org.scion.jpan.proto.endhost.Path;
import org.scion.jpan.proto.endhost.Underlays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockPathService {

  public static final int DEFAULT_PORT = 48080;
  private static final String MIME = "application/proto";
  private static final Logger logger = LoggerFactory.getLogger(MockPathService.class.getName());
  private final AtomicInteger callCount = new AtomicInteger();
  private PathServiceImpl pathService;
  private final Semaphore block = new Semaphore(1);
  private final AtomicReference<Status> errorToReport = new AtomicReference<>();
  private final AsInfo asInfo;

  private MockPathService(AsInfo asInfo) {
    this.asInfo = asInfo;
  }

  public static MockPathService start(int port, AsInfo asInfo) {
    return new MockPathService(asInfo).startInternal(port);
  }

  public int getAndResetCallCount() {
    return callCount.getAndSet(0);
  }

  private MockPathService startInternal(int port) {
    try {
      pathService = new MockPathService.PathServiceImpl(port);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Wait for server to start
    while (!pathService.wasStarted()) {
      TestUtil.sleep(1);
    }

    logger.info("Path service started, listening on port {}", port);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> pathService.stop()));
    return this;
  }

  public void close() {
    pathService.stop();
    logger.info("Path service shut down");
  }

  public void addResponse(
      long srcIA, boolean srcIsCore, long dstIA, boolean dstIsCore, Seg.SegmentsResponse response) {
    this.pathService.addResponse(srcIA, srcIsCore, dstIA, dstIsCore, response);
  }

  public void clearSegments() {
    this.pathService.clearSegments();
  }

  public int getPort() {
    return pathService.getListeningPort();
  }

  public void block() {
    try {
      block.acquire();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void unblock() {
    block.release();
  }

  private void awaitBlock() {
    block();
    unblock();
  }

  public void reportError(Status errorToReport) {
    this.errorToReport.set(errorToReport);
  }

  private class PathServiceImpl extends NanoHTTPD {
    final Map<String, List<Seg.PathSegment>> responsesUP = new ConcurrentHashMap<>();
    final Map<String, List<Seg.PathSegment>> responsesCORE = new ConcurrentHashMap<>();
    final Map<String, List<Seg.PathSegment>> responsesDOWN = new ConcurrentHashMap<>();
    final long start = Instant.now().getEpochSecond();

    public PathServiceImpl(int port) throws IOException {
      super(port);
      start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
      logger.info("Path service started on port {}", super.getListeningPort());
      if (session.getMethod() != Method.POST) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME, "POST expected");
      }

      if (errorToReport.get() != null) {
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME, "ERROR: " + errorToReport.get());
      }

      String resource = session.getUri();
      String listService = "/scion.endhost.v1.PathService/ListPaths";
      String underlayService = "/scion.endhost.v1.UnderlayService/ListUnderlays";
      if (listService.equals(resource)) {
        return handlePathRequest(session);
      } else if (underlayService.equals(resource)) {
        return handleUnderlayRequest(session);
      } else {
        logger.warn("Illegal request: {}", session.getUri());
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME, "9");
      }
    }

    private Response handlePathRequest(IHTTPSession session) {
      logger.info("Path server serves paths to {}", session.getRemoteIpAddress());
      callCount.incrementAndGet();
      awaitBlock(); // for testing timeouts

      Path.ListSegmentsRequest request;
      try {
        InputStream inputStream = session.getInputStream();
        ByteBuffer byteBuffer = ByteBuffer.allocate(inputStream.available());
        Channels.newChannel(inputStream).read(byteBuffer);
        byteBuffer.flip();
        System.out.println("SERVER: " + new String(byteBuffer.array()));
        request = Path.ListSegmentsRequest.parseFrom(byteBuffer);
      } catch (IOException e) {
        logger.error(e.getMessage());
        throw new RuntimeException(e);
      }

      long srcIA = request.getSrcIsdAs();
      long dstIA = request.getDstIsdAs();

      logger.info(
          "Path request: {} -> {}", ScionUtil.toStringIA(srcIA), ScionUtil.toStringIA(dstIA));

      Path.ListSegmentsResponse protoResponse;
      if (responsesUP.isEmpty() && responsesCORE.isEmpty() && responsesDOWN.isEmpty()) {
        protoResponse = defaultResponse(srcIA, dstIA);
      } else {
        protoResponse = toResponse(srcIA, dstIA);
      }

      InputStream targetStream = new ByteArrayInputStream(protoResponse.toByteArray());
      return newFixedLengthResponse(
          Response.Status.OK, MIME, targetStream, protoResponse.toByteArray().length);
    }

    private Response handleUnderlayRequest(IHTTPSession session) {
      logger.info("Path server serves underlay to {}", session.getRemoteIpAddress());
      callCount.incrementAndGet();
      awaitBlock(); // for testing timeouts

      Underlays.ListUnderlaysRequest request;
      try {
        InputStream inputStream = session.getInputStream();
        ByteBuffer byteBuffer = ByteBuffer.allocate(inputStream.available());
        Channels.newChannel(inputStream).read(byteBuffer);
        byteBuffer.flip();
        System.out.println("SERVER: " + new String(byteBuffer.array()));
        request = Underlays.ListUnderlaysRequest.parseFrom(byteBuffer);
      } catch (IOException e) {
        logger.error(e.getMessage());
        throw new RuntimeException(e);
      }

      logger.info("Underlay request: ... ISD/AS = {}", ScionUtil.toStringIA(request.getIsdAs()));

      Underlays.ListUnderlaysResponse.Builder b = Underlays.ListUnderlaysResponse.newBuilder();
      Underlays.UdpUnderlay.Builder udp = Underlays.UdpUnderlay.newBuilder();
      for (AsInfo.BorderRouter br : asInfo.getBorderRouters()) {
        Underlays.Router.Builder router = Underlays.Router.newBuilder();
        router.setIsdAs(asInfo.getIsdAs());
        router.setAddress(br.getInternalAddress());
        for (AsInfo.BorderRouterInterface bri : br.getInterfaces()) {
          router.addInterfaces(bri.id);
        }
        udp.addRouters(router.build());
      }
      b.setUdp(udp);

      Underlays.ListUnderlaysResponse protoResponse = b.build();
      InputStream targetStream = new ByteArrayInputStream(protoResponse.toByteArray());
      return newFixedLengthResponse(
          Response.Status.OK, MIME, targetStream, protoResponse.toByteArray().length);
    }

    private Path.ListSegmentsResponse toResponse(long srcIA, long dstIA) {
      Path.ListSegmentsResponse.Builder responseBuilder = Path.ListSegmentsResponse.newBuilder();
      // Find UP
      Set<Long> localCORE = new HashSet<>();
      Set<Long> remoteCORE = new HashSet<>();
      for (Map.Entry<String, List<Seg.PathSegment>> e : responsesUP.entrySet()) {
        String[] s = e.getKey().split(" -> ");
        long ia1 = Long.parseLong(s[0]);
        long ia2 = Long.parseLong(s[1]);
        System.out.println(
            "  up check: " + ScionUtil.toStringIA(ia1) + " -> " + ScionUtil.toStringIA(ia2));
        if (ia1 == srcIA) {
          System.out.println("      up check: ++");
          localCORE.add(ia2);
          responseBuilder.addAllUpSegments(e.getValue());
        }
      }
      // Find DOWN
      for (Map.Entry<String, List<Seg.PathSegment>> e : responsesDOWN.entrySet()) {
        String[] s = e.getKey().split(" -> ");
        long ia1 = Long.parseLong(s[0]);
        long ia2 = Long.parseLong(s[1]);
        System.out.println(
            "  down check: " + ScionUtil.toStringIA(ia1) + " -> " + ScionUtil.toStringIA(ia2));
        if (ia2 == dstIA) {
          System.out.println("      down check: ++");
          remoteCORE.add(ia1);
          responseBuilder.addAllDownSegments(e.getValue());
        }
        if (ia1 == dstIA) {
          System.out.println("      down check: ++");
          remoteCORE.add(ia2);
          responseBuilder.addAllDownSegments(e.getValue());
        }
      }
      // Find CORE
      for (Map.Entry<String, List<Seg.PathSegment>> e : responsesCORE.entrySet()) {
        String[] s = e.getKey().split(" -> ");
        long ia1 = Long.parseLong(s[0]);
        long ia2 = Long.parseLong(s[1]);
        System.out.println(
            "  core check: " + ScionUtil.toStringIA(ia1) + " -> " + ScionUtil.toStringIA(ia2));
        if ((localCORE.contains(ia1) && remoteCORE.contains(ia2))
            || localCORE.contains(ia2) && remoteCORE.contains(ia1)) {
          System.out.println("      core check: ++");
          responseBuilder.addAllCoreSegments(e.getValue());
        }
      }
      return responseBuilder.build();
    }

    public void addResponse(
        long srcIA,
        boolean srcIsCore,
        long dstIA,
        boolean dstIsCore,
        Seg.SegmentsResponse response) {
      Map<Integer, Seg.SegmentsResponse.Segments> map = response.getSegmentsMap();
      if (srcIsCore && dstIsCore) {
        responsesCORE.put(key(srcIA, dstIA), map.get(3).getSegmentsList());
      } else if (dstIsCore && map.containsKey(1)) {
        responsesUP.put(key(srcIA, dstIA), map.get(1).getSegmentsList());
      } else if (srcIsCore && map.containsKey(2)) {
        responsesDOWN.put(key(srcIA, dstIA), map.get(2).getSegmentsList());
      }
    }

    public void clearSegments() {
      responsesUP.clear();
      responsesCORE.clear();
      responsesDOWN.clear();
    }

    private String key(long ia0, long ia1) {
      return ia0 + " -> " + ia1;
    }

    private Path.ListSegmentsResponse defaultResponse(long srcIA, long dstIA) {
      Path.ListSegmentsResponse.Builder responseBuilder = Path.ListSegmentsResponse.newBuilder();
      if (srcIA == dstIA) {
        // Single core AS, no paths are available
        return responseBuilder.build();
      }
      // Wildcards are only used for CORE AS, and there is only one core AS in "tiny": 1-ff00:0:110
      srcIA = ScionUtil.isWildcard(srcIA) ? ScionUtil.parseIA("1-ff00:0:110") : srcIA;
      dstIA = ScionUtil.isWildcard(dstIA) ? ScionUtil.parseIA("1-ff00:0:110") : dstIA;

      if (srcIA == ScionUtil.parseIA("1-ff00:0:110")) {
        responseBuilder.addDownSegments(buildSegment(srcIA, dstIA, 1, 2));
        responseBuilder.addDownSegments(buildSegment(srcIA, dstIA, 11, 22));
      } else if (dstIA == ScionUtil.parseIA("1-ff00:0:110")) {
        responseBuilder.addUpSegments(buildSegment(srcIA, dstIA, 1, 2));
        responseBuilder.addUpSegments(buildSegment(srcIA, dstIA, 11, 22));
      }
      return responseBuilder.build();
    }

    private Seg.PathSegment buildSegment(long srcIA, long dstIA, int ingress, int egress) {
      ByteString mac0 = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
      Seg.HopField hop0 =
          Seg.HopField.newBuilder()
              .setMac(mac0)
              .setIngress(3)
              .setEgress(egress)
              .setExpTime(64)
              .build();
      Seg.HopEntry hopEntry0 =
          Seg.HopEntry.newBuilder().setHopField(hop0).setIngressMtu(2345).build();
      Seg.ASEntrySignedBody asSigneBody0 =
          Seg.ASEntrySignedBody.newBuilder()
              .setIsdAs(srcIA)
              .setHopEntry(hopEntry0)
              .setMtu(4567)
              .build();
      Signed.HeaderAndBodyInternal habi0 =
          Signed.HeaderAndBodyInternal.newBuilder().setBody(asSigneBody0.toByteString()).build();
      Signed.SignedMessage sm0 =
          Signed.SignedMessage.newBuilder().setHeaderAndBody(habi0.toByteString()).build();
      Seg.ASEntry asEntry0 = Seg.ASEntry.newBuilder().setSigned(sm0).build();

      ByteString mac1 = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
      Seg.HopField hop1 =
          Seg.HopField.newBuilder()
              .setMac(mac1)
              .setIngress(ingress)
              .setEgress(0)
              .setExpTime(64)
              .build();
      Seg.HopEntry hopEntry1 =
          Seg.HopEntry.newBuilder().setHopField(hop1).setIngressMtu(1234).build();
      Seg.ASEntrySignedBody asSigneBody1 =
          Seg.ASEntrySignedBody.newBuilder()
              .setIsdAs(dstIA)
              .setHopEntry(hopEntry1)
              .setMtu(3456)
              .build();
      Signed.HeaderAndBodyInternal habi1 =
          Signed.HeaderAndBodyInternal.newBuilder().setBody(asSigneBody1.toByteString()).build();
      Signed.SignedMessage sm1 =
          Signed.SignedMessage.newBuilder().setHeaderAndBody(habi1.toByteString()).build();
      Seg.ASEntry asEntry1 = Seg.ASEntry.newBuilder().setSigned(sm1).build();

      Seg.SegmentInformation info = Seg.SegmentInformation.newBuilder().setTimestamp(start).build();
      ByteString infoBS = info.toByteString();
      return Seg.PathSegment.newBuilder()
          .addAsEntries(asEntry0)
          .addAsEntries(asEntry1)
          .setSegmentInfo(infoBS)
          .build();
    }
  }
}
