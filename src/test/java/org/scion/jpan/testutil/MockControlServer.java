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
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.jpan.proto.crypto.Signed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockControlServer {

  private static final Logger logger = LoggerFactory.getLogger(MockControlServer.class.getName());
  private final AtomicInteger callCount = new AtomicInteger();
  private final InetSocketAddress address;
  private Server server;
  private ControlServiceImpl controlServer;

  private MockControlServer(InetSocketAddress address) {
    this.address = address;
  }

  public static MockControlServer start(int port) {
    InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    try {
      return new MockControlServer(addr).startInternal();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public int getAndResetCallCount() {
    return callCount.getAndSet(0);
  }

  private MockControlServer startInternal() throws IOException {
    int port = address.getPort();
    controlServer = new MockControlServer.ControlServiceImpl();
    server =
        Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .addService(controlServer)
            .build()
            .start();
    logger.info("Server started, listening on {}", address);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                  }
                }));
    return this;
  }

  public void close() {
    server.shutdown();
    try {
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        server.shutdownNow();
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.error("Control server did not terminate");
        }
      }
      logger.info("Control server shut down");
    } catch (InterruptedException ie) {
      server.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  public void addResponse(
      long srcIA, boolean srcIsCore, long dstIA, boolean dstIsCore, Seg.SegmentsResponse response) {
    this.controlServer.addResponse(srcIA, srcIsCore, dstIA, dstIsCore, response);
  }

  public void clearSegments() {
    this.controlServer.clearSegments();
  }

  private class ControlServiceImpl extends SegmentLookupServiceGrpc.SegmentLookupServiceImplBase {
    final Map<String, Seg.SegmentsResponse> responses = new ConcurrentHashMap<>();

    ControlServiceImpl() {}

    @Override
    public void segments(
        Seg.SegmentsRequest req, StreamObserver<Seg.SegmentsResponse> responseObserver) {
      String srcIsdAsStr = ScionUtil.toStringIA(req.getSrcIsdAs());
      String dstIsdAsStr = ScionUtil.toStringIA(req.getDstIsdAs());
      logger.info("Segment request: {} -> {}", srcIsdAsStr, dstIsdAsStr);
      callCount.incrementAndGet();

      if (responses.isEmpty()) {
        responseObserver.onNext(defaultResponse(req.getSrcIsdAs(), req.getDstIsdAs()));
      } else {
        responseObserver.onNext(responses.get(key(req.getSrcIsdAs(), req.getDstIsdAs())));
      }
      responseObserver.onCompleted();
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
      System.out.println("MCS-add: " + ScionUtil.toStringIA(srcIA) + " -> " + ScionUtil.toStringIA(dstIA));
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
      System.out.println("MCS-add: " + key);
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
  }

  private static Seg.SegmentsResponse defaultResponse(long srcIA, long dstIA) {
    Seg.SegmentsResponse.Builder replyBuilder = Seg.SegmentsResponse.newBuilder();
    if (srcIA == dstIA) {
      // Single core AS, no paths are available
      return replyBuilder.build();
    }
    // Wildcards are only used for CORE AS, and there is only one core AS in "tiny": 1-ff00:0:110
    srcIA = ScionUtil.isWildcard(srcIA) ? ScionUtil.parseIA("1-ff00:0:110") : srcIA;
    dstIA = ScionUtil.isWildcard(dstIA) ? ScionUtil.parseIA("1-ff00:0:110") : dstIA;

    ByteString mac0 = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
    Seg.HopField hop0 =
        Seg.HopField.newBuilder().setMac(mac0).setIngress(3).setEgress(2).setExpTime(64).build();
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
        Seg.HopField.newBuilder().setMac(mac1).setIngress(1).setEgress(0).setExpTime(64).build();
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

    long now = Instant.now().getEpochSecond();
    Seg.SegmentInformation info = Seg.SegmentInformation.newBuilder().setTimestamp(now).build();
    ByteString infoBS = info.toByteString();
    Seg.PathSegment pathSegment =
        Seg.PathSegment.newBuilder()
            .addAsEntries(asEntry0)
            .addAsEntries(asEntry1)
            .setSegmentInfo(infoBS)
            .build();
    Seg.SegmentsResponse.Segments segments =
        Seg.SegmentsResponse.Segments.newBuilder().addSegments(pathSegment).build();
    if (srcIA == ScionUtil.parseIA("1-ff00:0:110")) {
      replyBuilder.putSegments(Seg.SegmentType.SEGMENT_TYPE_DOWN_VALUE, segments);
    } else if (dstIA == ScionUtil.parseIA("1-ff00:0:110")) {
      replyBuilder.putSegments(Seg.SegmentType.SEGMENT_TYPE_UP_VALUE, segments);
    }
    return replyBuilder.build();
  }
}
