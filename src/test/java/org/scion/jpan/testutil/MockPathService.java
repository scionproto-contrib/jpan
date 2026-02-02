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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.okhttp.OkHttpServerProvider;
import okhttp3.*;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.crypto.Signed;
import org.scion.jpan.proto.endhost.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockPathService {

  public static final int DEFAULT_PORT = 48080;
  private static final Logger logger = LoggerFactory.getLogger(MockPathService.class.getName());
  private final AtomicInteger callCount = new AtomicInteger();
  private final InetSocketAddress address;
  private PathServiceImpl pathService;
  private ExecutorService service;
  private final Semaphore block = new Semaphore(1);
  private final AtomicReference<Status> errorToReport = new AtomicReference<>();
  private static final CountDownLatch barrier = new CountDownLatch(1);

  private MockPathService(InetSocketAddress address) {
    this.address = address;
  }

  public static MockPathService start(int port) {
    InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    return new MockPathService(addr).startInternal();
  }

  public int getAndResetCallCount() {
    return callCount.getAndSet(0);
  }

  private MockPathService startInternal() {
    pathService = new MockPathService.PathServiceImpl();
    service = Executors.newFixedThreadPool(2);
    service.execute(pathService);
    try {
      barrier.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    logger.info("Server started, listening on {}", address);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    service.shutdown();
                    service.awaitTermination(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                  }
                }));
    return this;
  }

  public void close() {
    pathService.shutdown();
    try {
      if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
        service.shutdownNow();
        if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.error("Path service did not terminate");
        }
      }
      logger.info("Path service shut down");
    } catch (InterruptedException ie) {
      service.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  public void addResponse(
      long srcIA, boolean srcIsCore, long dstIA, boolean dstIsCore, Seg.SegmentsResponse response) {
    this.pathService.addResponse(srcIA, srcIsCore, dstIA, dstIsCore, response);
  }

  public void clearSegments() {
    this.pathService.clearSegments();
  }

  public int getPort() {
    return address.getPort();
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

  private class PathServiceImpl implements Runnable {
    final Map<String, Seg.SegmentsResponse> responses = new ConcurrentHashMap<>();
    final long start = Instant.now().getEpochSecond();

    PathServiceImpl() {}

    @Override
    public void run() {
      System.out.println("PS started");
      // TODO should we use the connectRPC library to create a server for UNARY calls (using
      //     a protofile service entry)?

      try (ServerSocketChannel chnLocal = ServerSocketChannel.open()) {
        // Explicit binding to "localhost" to avoid automatic binding to IPv6 which is not
        // supported by GitHub CI (https://github.com/actions/runner-images/issues/668).
        //InetSocketAddress local =
          //  new InetSocketAddress(InetAddress.getLoopbackAddress(), address.getPort());
        chnLocal.bind(address);
        chnLocal.configureBlocking(true);
        ByteBuffer buffer = ByteBuffer.allocate(66000);
        logger.info("Topology server started on port {}", chnLocal.getLocalAddress());
        barrier.countDown();
        while (true) {
          System.out.println("PS waiting: " + address);
          SocketChannel ss = chnLocal.accept();
          ss.read(buffer);
          System.out.println("PS received");
          SocketAddress srcAddress = ss.getRemoteAddress();

          buffer.flip();

          // Expected:
          //   "GET /topology HTTP/1.1"
          //   "GET /trcs HTTP/1.1"
          String request = Charset.defaultCharset().decode(buffer).toString();
          String resource = request.substring(request.indexOf(" ") + 1, request.indexOf(" HTTP"));
          String listService = "/scion.endhost.v1.PathService/ListPaths";
          if (listService.equals(resource)) {
            System.out.println("PS received ListPaths");
            logger.info("Bootstrap server serves file to {}", srcAddress);
            callCount.incrementAndGet();

            System.out.println("Request: " + request);
            System.out.println("Request: " + request.length());


            Path.ListSegmentsResponse protoResponse;
            String protoError = null;

            int posLen = request.indexOf("Content-Length: ") + 16;
            // int posLenEnd = request.indexOf("Host:", posLen) - 2;
            int posLenEnd = request.indexOf("\r", posLen);
            String sub = request.substring(posLen, posLenEnd);
            System.out.println("----'" + sub  + "'----");
            int len = Integer.parseInt(request.substring(posLen, posLenEnd));
            System.out.println("len = " + len);

//            OkHttpServerProvider sp = new OkHttpServerProvider();
//            OkHttpServerBuilder sb = OkHttpServerBuilder.forPort(48080);
//            sb.
//            Server server = sb.build();
//            server.start();
//
//            Request hr = new Request.Builder().

            buffer.position(buffer.limit() - len);
            Path.ListSegmentsRequest r = Path.ListSegmentsRequest.parseFrom(buffer);
            System.out.println("r=" + r);
            System.out.println("r=" + r);

            long srcIA = r.getSrcIsdAs();
            long dstIA = r.getDstIsdAs();

            logger.info("Segment request: {} -> {}", ScionUtil.toStringIA(srcIA), ScionUtil.toStringIA(dstIA));
            callCount.incrementAndGet();
            awaitBlock(); // for testing timeouts

            if (responses.isEmpty()) {
              protoResponse = defaultResponse(srcIA, dstIA);
            } else {
              protoResponse = toResponse(srcIA, dstIA);
            }
            if (errorToReport.get() != null) {
              protoResponse = null;
              protoError = ".....ERROR: " + errorToReport;
            }

            if (protoError != null) {
              // TODO send error
              return;
            }

            String apiAddress = IPHelper.toString(address);
           RequestBody requestBody = RequestBody.create(r.toByteArray());
           Request hr =
                    new Request.Builder()
                            .url("http://" + apiAddress + "/scion.endhost.v1.PathService/ListPaths")
                            .addHeader("Content-type", "application/proto")
                            //            .addHeader("User-Agent", "OkHttp Bot")
                            .post(requestBody)
                            .build();
            ResponseBody responseBody = ResponseBody.create(protoResponse.toByteArray(), MediaType.get("application/proto"));
            Response response = new Response.Builder().body(responseBody).addHeader("Content-type", "application/proto").code(200).request(hr).protocol(Protocol.HTTP_1_1)
                    .message(new String(protoResponse.toByteArray())).build();

            System.out.println("Response xxx: " + new String(protoResponse.toByteArray()));
            System.out.println("Response msg: " + response.message());
            System.out.println("Response toS: " + response.toString());

            System.out.println("RRRRR: " + responseBody.string());


//            Request request =
//                    new Request.Builder()
//                            .url("http://" + apiAddress + "/scion.endhost.v1.PathService/ListPaths")
//                            .addHeader("Content-type", "application/proto")
//                            //            .addHeader("User-Agent", "OkHttp Bot")
//                            .post(requestBody)
//                            .build();


            //String strResponse = "HTTP/1.1 201 Created\r";
            String strResponse = "HTTP/1.1 200 OK\r";
            strResponse += "Content-Type: application/proto";
            strResponse += "Length: " + protoResponse.toByteArray().length; // TODO!!!!

            buffer.clear();
            buffer.put(response.message().getBytes());
//            buffer.put(strResponse.getBytes());
//            buffer.put(protoResponse.toByteArray());
            buffer.flip();
            ss.write(buffer);
          } else {
            logger.warn("Illegal request: {}", request);
          }
          buffer.clear();
        }

      } catch (ClosedByInterruptException e) {
        throw new RuntimeException(e);
      } catch (IOException e) {
        logger.error(e.getMessage());
        throw new RuntimeException(e);
      } finally {
        System.out.println("PS stopping");
        logger.info("Shutting down topology server");
      }
    }

    void shutdown() {

    }

    private String createMessage(String content) {
      return "HTTP/1.1 200 OK\n"
              + "Connection: close\n"
              + "Content-Type: text/plain\n"
              + "Content-Length:"
              + content.length()
              + "\n"
              + "\n"
              + content
              + "\n";
    }

//    @Override
//    public void segments(
//        Seg.SegmentsRequest req, StreamObserver<Seg.SegmentsResponse> responseObserver) {
//      String srcIsdAsStr = ScionUtil.toStringIA(req.getSrcIsdAs());
//      String dstIsdAsStr = ScionUtil.toStringIA(req.getDstIsdAs());
//      logger.info("Segment request: {} -> {}", srcIsdAsStr, dstIsdAsStr);
//      callCount.incrementAndGet();
//      awaitBlock(); // for testing timeouts
//
//      if (responses.isEmpty()) {
//        responseObserver.onNext(defaultResponse(req.getSrcIsdAs(), req.getDstIsdAs()));
//      } else {
//        responseObserver.onNext(responses.get(key(req.getSrcIsdAs(), req.getDstIsdAs())));
//      }
//      if (errorToReport.get() != null) {
//        responseObserver.onError(new StatusException(errorToReport.getAndSet(null)));
//      } else {
//        responseObserver.onCompleted();
//      }
//    }

    private Path.ListSegmentsResponse toResponse(long srcIA, long dstIA) {
      Path.ListSegmentsResponse.Builder responseBuilder = Path.ListSegmentsResponse.newBuilder();

      // TODO use controlService.respponses
      Seg.SegmentsResponse cs = responses.get(key(srcIA, dstIA));
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
