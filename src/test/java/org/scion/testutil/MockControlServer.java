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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.scion.ScionUtil;
import org.scion.proto.control_plane.Seg;
import org.scion.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.proto.crypto.Signed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockControlServer implements AutoCloseable {

  //  public static final String DEFAULT_IP = "127.0.0.15";
  //  public static final int DEFAULT_PORT = 31000;
  //  public static final InetSocketAddress DEFAULT_ADDRESS =
  //      new InetSocketAddress(DEFAULT_IP, DEFAULT_PORT);
  //  public static final String DEFAULT_ADDRESS_STR = DEFAULT_ADDRESS.toString().substring(1);
  private static final Logger logger = LoggerFactory.getLogger(MockControlServer.class.getName());
  private static final AtomicInteger callCount = new AtomicInteger();
  private static final byte[] PATH_RAW_TINY_110_112 = {
    0, 0, 32, 0, 1, 0, 11, 16,
    101, 83, 118, -81, 0, 63, 0, 0,
    0, 2, 118, -21, 86, -46, 89, 0,
    0, 63, 0, 1, 0, 0, -8, 2,
    -114, 25, 76, -122,
  };
  public static MockControlServer DEFAULT = null;
  private final InetSocketAddress address;
  private final List<InetSocketAddress> borderRouters;
  private Server server;

  private MockControlServer(InetSocketAddress address) {
    this.address = address;
    this.borderRouters = new ArrayList<>();
    // this.borderRouters.add(new InetSocketAddress("127.0.0.10", 31004));
  }

  private MockControlServer(InetSocketAddress address, List<InetSocketAddress> borderRouters) {
    this.address = address;
    this.borderRouters = borderRouters;
  }

  private static void setEnvironment() {
    //    System.setProperty(ScionConstants.PROPERTY_DAEMON_HOST, DEFAULT_IP);
    //    System.setProperty(ScionConstants.PROPERTY_DAEMON_PORT, "" + DEFAULT_PORT);
  }

  public static MockControlServer start(int port) throws IOException {
    if (DEFAULT != null) {
      throw new NullPointerException();
    }
    setEnvironment();
    InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    DEFAULT = new MockControlServer(addr);
    DEFAULT.startInternal();
    return DEFAULT;
  }

  public static void closeDefault() throws IOException {
    if (DEFAULT != null) {
      DEFAULT.close();
      DEFAULT = null;
    }
  }

  public static int getAndResetCallCount() {
    return callCount.getAndSet(0);
  }

  private MockControlServer startInternal() throws IOException {
    List<String> brStr =
        borderRouters.stream().map(br -> br.toString().substring(1)).collect(Collectors.toList());
    int port = address.getPort();
    server =
        Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .addService(new MockControlServer.ControlServiceImpl(brStr))
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

  static class ControlServiceImpl extends SegmentLookupServiceGrpc.SegmentLookupServiceImplBase {
    final List<String> borderRouters;

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
      Seg.SegmentsResponse.Builder replyBuilder = Seg.SegmentsResponse.newBuilder();

      ByteString mac0 = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
      Seg.HopField hopField0 = Seg.HopField.newBuilder().setMac(mac0).build();
      Seg.HopEntry hopEntry0 = Seg.HopEntry.newBuilder().setHopField(hopField0).build();
      Seg.ASEntrySignedBody asSigneBody0 =
          Seg.ASEntrySignedBody.newBuilder()
              .setIsdAs(req.getSrcIsdAs())
              .setHopEntry(hopEntry0)
              .build();
      Signed.HeaderAndBodyInternal habi0 =
          Signed.HeaderAndBodyInternal.newBuilder().setBody(asSigneBody0.toByteString()).build();
      Signed.SignedMessage sm0 =
          Signed.SignedMessage.newBuilder().setHeaderAndBody(habi0.toByteString()).build();
      Seg.ASEntry asEntry0 = Seg.ASEntry.newBuilder().setSigned(sm0).build();

      ByteString mac1 = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
      Seg.HopField hopField1 = Seg.HopField.newBuilder().setMac(mac1).build();
      Seg.HopEntry hopEntry1 = Seg.HopEntry.newBuilder().setHopField(hopField1).build();
      Seg.ASEntrySignedBody asSigneBody1 =
          Seg.ASEntrySignedBody.newBuilder()
              .setIsdAs(req.getDstIsdAs())
              .setHopEntry(hopEntry1)
              .build();
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
      responseObserver.onNext(replyBuilder.build());
      responseObserver.onCompleted();
    }
  }
}
