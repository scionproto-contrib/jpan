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

package org.scion.testutil;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.scion.proto.control_plane.Seg;
import org.scion.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.proto.crypto.Signed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockControlServer implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(MockControlServer.class.getName());
  private final AtomicInteger callCount = new AtomicInteger();
  private final InetSocketAddress address;
  // TODO remove or use
  private final List<InetSocketAddress> borderRouters;
  private Server server;
  private ControlServiceImpl controlServer;

  private MockControlServer(InetSocketAddress address) {
    this.address = address;
    this.borderRouters = new ArrayList<>();
  }

  private MockControlServer(InetSocketAddress address, List<InetSocketAddress> borderRouters) {
    this.address = address;
    this.borderRouters = borderRouters;
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
    List<String> brStr =
        borderRouters.stream().map(br -> br.toString().substring(1)).collect(Collectors.toList());
    int port = address.getPort();
    controlServer = new MockControlServer.ControlServiceImpl(brStr);
    server =
        Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .addService(controlServer)
            .build()
            .start();
    logger.info("Server started, listening on " + address);

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

  @Override
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
    final List<String> borderRouters;
    final Map<String, Seg.SegmentsResponse> responses = new ConcurrentHashMap<>();

    ControlServiceImpl(List<String> borderRouters) {
      this.borderRouters = borderRouters;
    }

    @Override
    public void segments(
        Seg.SegmentsRequest req, StreamObserver<Seg.SegmentsResponse> responseObserver) {
      String srcIsdAsStr = ScionUtil.toStringIA(req.getSrcIsdAs());
      String dstIsdAsStr = ScionUtil.toStringIA(req.getDstIsdAs());
      logger.info("Segment request: " + srcIsdAsStr + " -> " + dstIsdAsStr);
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
  }

  private static Seg.SegmentsResponse defaultResponse(long srcIA, long dstIA) {
    Seg.SegmentsResponse.Builder replyBuilder = Seg.SegmentsResponse.newBuilder();

    ByteString mac0 = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
    Seg.HopField hop0 = Seg.HopField.newBuilder().setMac(mac0).setIngress(3).setEgress(2).build();
    Seg.HopEntry hopEntry0 = Seg.HopEntry.newBuilder().setHopField(hop0).build();
    Seg.ASEntrySignedBody asSigneBody0 =
        Seg.ASEntrySignedBody.newBuilder().setIsdAs(srcIA).setHopEntry(hopEntry0).build();
    Signed.HeaderAndBodyInternal habi0 =
        Signed.HeaderAndBodyInternal.newBuilder().setBody(asSigneBody0.toByteString()).build();
    Signed.SignedMessage sm0 =
        Signed.SignedMessage.newBuilder().setHeaderAndBody(habi0.toByteString()).build();
    Seg.ASEntry asEntry0 = Seg.ASEntry.newBuilder().setSigned(sm0).build();

    ByteString mac1 = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
    Seg.HopField hop1 = Seg.HopField.newBuilder().setMac(mac1).setIngress(5).setEgress(3).build();
    Seg.HopEntry hopEntry1 = Seg.HopEntry.newBuilder().setHopField(hop1).build();
    Seg.ASEntrySignedBody asSigneBody1 =
        Seg.ASEntrySignedBody.newBuilder().setIsdAs(dstIA).setHopEntry(hopEntry1).build();
    Signed.HeaderAndBodyInternal habi1 =
        Signed.HeaderAndBodyInternal.newBuilder().setBody(asSigneBody1.toByteString()).build();
    Signed.SignedMessage sm1 =
        Signed.SignedMessage.newBuilder().setHeaderAndBody(habi1.toByteString()).build();
    Seg.ASEntry asEntry1 = Seg.ASEntry.newBuilder().setSigned(sm1).build();

    Seg.PathSegment pathSegment =
        Seg.PathSegment.newBuilder().addAsEntries(asEntry0).addAsEntries(asEntry1).build();
    Seg.SegmentsResponse.Segments segments =
        Seg.SegmentsResponse.Segments.newBuilder().addSegments(pathSegment).build();
    replyBuilder.putSegments(Seg.SegmentType.SEGMENT_TYPE_CORE_VALUE, segments);
    return replyBuilder.build();
  }
}
