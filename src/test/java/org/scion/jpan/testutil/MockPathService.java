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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.crypto.Signed;
import org.scion.jpan.proto.endhost.Path;
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

  private MockPathService() {}

  public static MockPathService start(int port) {
    return new MockPathService().startInternal(port);
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

  public void syncSegmentDatabaseFrom(MockControlServer referenceCS) {
    pathService.responses.putAll(referenceCS.getSegments());
  }

  public void reportError(Status errorToReport) {
    this.errorToReport.set(errorToReport);
  }

  private class PathServiceImpl extends NanoHTTPD {
    final Map<String, Seg.SegmentsResponse> responses = new ConcurrentHashMap<>();
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

      InputStream inputStream = session.getInputStream();
      String resource = session.getUri();
      String listService = "/scion.endhost.v1.PathService/ListPaths";
      if (listService.equals(resource)) {
        logger.info("Path server serves paths to {}", session.getRemoteIpAddress());
        callCount.incrementAndGet();

        Path.ListSegmentsRequest request;
        try {
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
        callCount.incrementAndGet();
        awaitBlock(); // for testing timeouts

        Path.ListSegmentsResponse protoResponse;
        if (responses.isEmpty()) {
          protoResponse = defaultResponse(srcIA, dstIA);
        } else {
          protoResponse = toResponse(srcIA, dstIA);
        }

        if (errorToReport.get() != null) {
          return newFixedLengthResponse(
              Response.Status.BAD_REQUEST, MIME, "ERROR: " + errorToReport.get());
        }

        InputStream targetStream = new ByteArrayInputStream(protoResponse.toByteArray());
        return newFixedLengthResponse(
            Response.Status.OK, MIME, targetStream, protoResponse.toByteArray().length);
      } else {
        logger.warn("Illegal request: {}", session.getUri());
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME, "9");
      }
    }

    private Path.ListSegmentsResponse toResponse(long srcIA, long dstIA) {
      Path.ListSegmentsResponse.Builder responseBuilder = Path.ListSegmentsResponse.newBuilder();

      // TODO use controlService.respponses
      Seg.SegmentsResponse cs = responses.get(key(srcIA, dstIA));
      if (cs == null) {
        throw new IllegalArgumentException(
            "Not found: " + ScionUtil.toStringIA(srcIA) + " -> " + ScionUtil.toStringIA(dstIA));
      }
      for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> e : cs.getSegmentsMap().entrySet()) {
        switch (e.getKey()) {
          case 1:
            responseBuilder.addAllUpSegments(e.getValue().getSegmentsList());
            break;
          case 2:
            responseBuilder.addAllCoreSegments(e.getValue().getSegmentsList());
            break;
          case 3:
            responseBuilder.addAllDownSegments(e.getValue().getSegmentsList());
            break;
          default:
            throw new UnsupportedOperationException("key=" + e.getKey());
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
      long maskISD = -1L << 48;
      long srcWildcard = srcIA & maskISD;
      long dstWildcard = dstIA & maskISD;
      addResponse(key(srcIA, dstIA), response);
      if (dstIsCore) {
        addResponse(key(srcIA, dstWildcard), response);
      }
      if (srcIsCore) {
        addResponse(key(srcWildcard, dstIA), response);
      }
      if (srcIsCore && dstIsCore) {
        addResponse(key(srcWildcard, dstWildcard), response);
      }
    }

    private void addResponse(String key, Seg.SegmentsResponse response) {
      if (!responses.containsKey(key)) {
        responses.put(key, response);
        return;
      }
      // merge new response with existing response
      Seg.SegmentsResponse existing = responses.get(key);
      int existingKey = existing.getSegmentsMap().entrySet().iterator().next().getKey();
      int newKey = response.getSegmentsMap().entrySet().iterator().next().getKey();
      if (newKey != existingKey) {
        throw new UnsupportedOperationException();
      }
      List<Seg.PathSegment> listExisting =
          existing.getSegmentsMap().entrySet().iterator().next().getValue().getSegmentsList();
      List<Seg.PathSegment> listNew =
          response.getSegmentsMap().entrySet().iterator().next().getValue().getSegmentsList();
      Seg.SegmentsResponse.Builder replyBuilder = Seg.SegmentsResponse.newBuilder();
      Seg.SegmentsResponse.Segments.Builder segmentsBuilder =
          Seg.SegmentsResponse.Segments.newBuilder();
      segmentsBuilder.addAllSegments(listExisting);
      segmentsBuilder.addAllSegments(listNew);

      replyBuilder.putSegments(existingKey, segmentsBuilder.build());
      responses.put(key, replyBuilder.build());
    }

    public void clearSegments() {
      responses.clear();
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
