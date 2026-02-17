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

import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
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
import org.scion.jpan.proto.endhost.Path;
import org.scion.jpan.proto.endhost.Underlays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockPathService {

  public static final int DEFAULT_PORT_0 = 48080;
  public static final int DEFAULT_PORT_1 = 58080;
  private static final String MIME = "application/proto";
  private static final Logger logger = LoggerFactory.getLogger(MockPathService.class.getName());
  private final AtomicInteger callCount = new AtomicInteger();
  private PathServiceImpl pathService;
  private final Semaphore block = new Semaphore(1);
  private final AtomicReference<NanoHTTPD.Response.Status> errorToReport = new AtomicReference<>();
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
      throw new RuntimeException("port=" + port, e);
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

  public void reportError(NanoHTTPD.Response.Status errorToReport) {
    this.errorToReport.set(errorToReport);
  }

  private class PathServiceImpl extends NanoHTTPD {
    final Map<String, List<Seg.PathSegment>> responsesUP = new ConcurrentHashMap<>();
    final Map<String, List<Seg.PathSegment>> responsesCORE = new ConcurrentHashMap<>();
    final Map<String, List<Seg.PathSegment>> responsesDOWN = new ConcurrentHashMap<>();

    public PathServiceImpl(int port) throws IOException {
      super(port);
      start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
      logger.info("Path service started on port {}", super.getListeningPort());
      callCount.incrementAndGet();
      if (session.getMethod() != Method.POST) {
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "POST expected");
      }

      if (errorToReport.get() != null) {
        NanoHTTPD.Response.Status error = errorToReport.getAndSet(null);
        try {
          // We have to empty the stream to avoid NanoHTTPD throwing an exception.
          if (session.getInputStream().skip(1_000_000) < 1) {
            throw new IOException();
          }
        } catch (IOException e) {
          e.printStackTrace(); // should never happen
        }
        return newFixedLengthResponse(error, NanoHTTPD.MIME_PLAINTEXT, error.getDescription());
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
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "9");
      }
    }

    private Response handlePathRequest(IHTTPSession session) {
      logger.info("Path server serves paths to {}", session.getRemoteIpAddress());
      awaitBlock(); // for testing timeouts

      Path.ListSegmentsRequest request;
      try {
        InputStream inputStream = session.getInputStream();
        ByteBuffer byteBuffer = ByteBuffer.allocate(inputStream.available());
        Channels.newChannel(inputStream).read(byteBuffer);
        byteBuffer.flip();
        request = Path.ListSegmentsRequest.parseFrom(byteBuffer);
      } catch (IOException e) {
        logger.error(e.getMessage());
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, e.getMessage());
      }

      long srcIA = request.getSrcIsdAs();
      long dstIA = request.getDstIsdAs();
      logger.info(
          "Path request: {} -> {}", ScionUtil.toStringIA(srcIA), ScionUtil.toStringIA(dstIA));
      if (srcIA == 0 || dstIA == 0) {
        String src = ScionUtil.toStringIA(srcIA);
        String dst = ScionUtil.toStringIA(dstIA);
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            NanoHTTPD.MIME_PLAINTEXT,
            "Illegal arguments: " + src + " -> " + dst);
      }

      Path.ListSegmentsResponse r = toResponse(srcIA, dstIA);
      // Report error if ISD/AS was not found
      if (r.getUpSegmentsCount() + r.getCoreSegmentsCount() + r.getDownSegmentsCount() == 0) {
        String src = ScionUtil.toStringIA(srcIA);
        String dst = ScionUtil.toStringIA(dstIA);
        return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            NanoHTTPD.MIME_PLAINTEXT,
            "Not found: " + src + " -> " + dst);
      }

      InputStream targetStream = new ByteArrayInputStream(r.toByteArray());
      return newFixedLengthResponse(Response.Status.OK, MIME, targetStream, r.toByteArray().length);
    }

    private Response handleUnderlayRequest(IHTTPSession session) {
      logger.info("Path server serves underlay to {}", session.getRemoteIpAddress());
      awaitBlock(); // for testing timeouts

      Underlays.ListUnderlaysRequest request;
      try {
        InputStream inputStream = session.getInputStream();
        ByteBuffer byteBuffer = ByteBuffer.allocate(inputStream.available());
        Channels.newChannel(inputStream).read(byteBuffer);
        byteBuffer.flip();
        request = Underlays.ListUnderlaysRequest.parseFrom(byteBuffer);
      } catch (IOException e) {
        logger.error(e.getMessage());
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, e.getMessage());
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
        if (ia1 == srcIA) {
          localCORE.add(ia2);
          responseBuilder.addAllUpSegments(e.getValue());
        }
      }
      // Find DOWN
      for (Map.Entry<String, List<Seg.PathSegment>> e : responsesDOWN.entrySet()) {
        String[] s = e.getKey().split(" -> ");
        long ia1 = Long.parseLong(s[0]);
        long ia2 = Long.parseLong(s[1]);
        if (ia2 == dstIA) {
          remoteCORE.add(ia1);
          responseBuilder.addAllDownSegments(e.getValue());
        }
      }
      // Find CORE
      for (Map.Entry<String, List<Seg.PathSegment>> e : responsesCORE.entrySet()) {
        String[] s = e.getKey().split(" -> ");
        long ia1 = Long.parseLong(s[0]);
        long ia2 = Long.parseLong(s[1]);
        if ((localCORE.contains(ia1) && remoteCORE.contains(ia2))
            || localCORE.contains(ia2) && remoteCORE.contains(ia1)) {
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
  }
}
