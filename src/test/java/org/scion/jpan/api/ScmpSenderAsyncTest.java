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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockScmpHandler;

public class ScmpSenderAsyncTest {
  private static final byte[] PING_ERROR_4_51_HK = {
    0, 0, 0, 1, -54, 35, 0, -80,
    1, 0, 0, 0, 0, 64, 0, 2,
    0, 0, 0, 9, 0, 64, 0, 2,
    0, 0, 0, 9, -127, -124, -26, 86,
    -64, -88, 53, 20, 70, 0, 80, -128,
    1, 0, 73, -18, 101, -90, -92, 52,
    1, 0, -95, -22, 101, -90, -91, -103,
    0, 63, 0, 0, 0, 1, -87, -21,
    92, 51, 89, -87, 0, 63, 0, 1,
    0, 2, -74, -117, -26, 74, -47, -107,
    0, 63, 0, 1, 0, 22, -85, 62,
    121, -62, 124, -61, 0, 63, 0, 3,
    0, 8, -96, 93, -5, 72, -22, -58,
    0, 63, 0, 21, 0, 0, 102, 19,
    47, 40, 118, -111, 0, 63, 0, 0,
    0, 5, -70, 106, -60, 125, -23, 98,
    0, 63, 0, 1, 0, 0, 77, -14,
    -37, -102, 103, -90, 4, 51, -35, 103,
    0, 0, 0, 68, 0, 0, 0, 1,
    -54, 38, 0, 16, 1, 48, 0, 0,
    0, 66, 0, 2, 0, 0, 0, 17,
    0, 64, 0, 2, 0, 0, 0, 9,
    0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 1,
    -127, -124, -26, 86, 0, 0, 33, 64,
    0, 0, -95, -22, 101, -90, -91, -103,
    0, 0, 73, -18, 101, -90, -92, 52,
    0, 63, 0, 1, 0, 0, 77, -14,
    -37, -102, 103, -90, 0, 63, 0, 0,
    0, 5, -70, 106, -60, 125, -23, 98,
    0, 63, 0, 21, 0, 0, 102, 19,
    47, 40, 118, -111, 0, 63, 0, 3,
    0, 8, -96, 93, -5, 72, -22, -58,
    0, 63, 0, 1, 0, 22, -85, 62,
    121, -62, 124, -61, 0, 63, 0, 1,
    0, 2, -74, -117, -26, 74, -47, -107,
    0, 63, 0, 0, 0, 1, -87, -21,
    92, 51, 89, -87, -128, 0, 0, 0,
    117, 89, 0, 0, 0, 0, 0, 0,
    0, 0, 117, 89,
  };

  private static final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @AfterEach
  public void afterEach() {
    if (!errors.isEmpty()) {
      for (String s : errors) {
        System.err.println("ERROR: " + s);
      }
      fail(errors.poll());
    }
  }

  @Test
  void echo_error_invalidMAC() throws IOException {
    ScionPacketInspector spi = ScionPacketInspector.readPacket(ByteBuffer.wrap(PING_ERROR_4_51_HK));
    assertEquals(Scmp.TypeCode.TYPE_4_CODE_51, spi.getScmpHeader().getCode());

    // Test that error can be parsed without throwing an exception
    ByteBuffer buffer = ByteBuffer.wrap(PING_ERROR_4_51_HK);
    InetAddress srcAddr = Inet4Address.getByAddress(new byte[] {0, 0, 0, 0});
    InetSocketAddress srcAddress = new InetSocketAddress(srcAddr, 12345);
    ResponsePath path = ScionHeaderParser.extractResponsePath(buffer, srcAddress);
    assertNotNull(path);
  }

  @Test
  void echo() throws IOException {
    testEcho(this::getPathTo112);
  }

  @Test
  void echo_localAS_BR() throws IOException {
    testEcho(this::getPathToLocalAS_BR);
    assertEquals(1, MockNetwork.getAndResetForwardCount()); // 1!
    assertEquals(1, MockScmpHandler.getAndResetAnswerTotal());
  }

  @Test
  void echo_localAS_BR_30041() throws IOException {
    testEcho(this::getPathToLocalAS_BR_30041);
    assertEquals(0, MockNetwork.getAndResetForwardCount()); // 0!
    assertEquals(1, MockScmpHandler.getAndResetAnswerTotal());
  }

  private void testEcho(Supplier<Path> pathSupplier) throws IOException {
    MockNetwork.startTiny();
    MockNetwork.answerNextScmpEchos(1);
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      byte[] data = new byte[] {1, 2, 3, 4, 5};
      Path path = pathSupplier.get();
      int seqId = channel.asyncEcho(path, ByteBuffer.wrap(data));
      Scmp.EchoMessage result = handler.get();
      assertEquals(seqId, result.getSequenceNumber());
      assertEquals(Scmp.TypeCode.TYPE_129, result.getTypeCode());
      assertTrue(result.getNanoSeconds() > 0);
      assertTrue(result.getNanoSeconds() < 100_000_000); // 10 ms
      assertArrayEquals(data, result.getData());
      assertFalse(result.isTimedOut());
      Path returnPath = result.getPath();
      assertEquals(path.getRemoteAddress(), returnPath.getRemoteAddress());
      assertEquals(Constants.SCMP_PORT, returnPath.getRemotePort());
      assertEquals(path.getRemoteIsdAs(), returnPath.getRemoteIsdAs());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void echo_timeout() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      channel.setTimeOut(100);
      MockNetwork.dropNextPackets(1);
      int seqId1 = channel.asyncEcho(path, ByteBuffer.allocate(0));
      Scmp.EchoMessage result1 = handler.get();
      assertTrue(result1.isTimedOut());
      assertEquals(100 * 1_000_000, result1.getNanoSeconds());
      assertEquals(seqId1, result1.getSequenceNumber());

      // try again
      handler.reset();
      MockNetwork.answerNextScmpEchos(1);
      int seqId2 = channel.asyncEcho(path, ByteBuffer.allocate(0));
      Scmp.EchoMessage result2 = handler.get();
      assertEquals(Scmp.TypeCode.TYPE_129, result2.getTypeCode());
      assertFalse(result2.isTimedOut());
      assertTrue(result2.getNanoSeconds() > 0);
      assertTrue(result2.getNanoSeconds() < 10_000_000); // 10 ms
      assertEquals(seqId2, result2.getSequenceNumber());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void echo_IOException() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      MockNetwork.stopTiny();
      channel.asyncEcho(path, ByteBuffer.allocate(0));
      assertThrows(IOException.class, handler::get);
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void echo_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // Router will return SCMP error
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.TypeCode.TYPE_1_CODE_0);
      channel.asyncEcho(getPathTo112(), ByteBuffer.allocate(0));
      Throwable t = assertThrows(IOException.class, handler::get);
      assertEquals(Scmp.TypeCode.TYPE_1_CODE_0, handler.error.getTypeCode());
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_1_CODE_0.getText()));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void traceroute() throws IOException {
    testTraceroute(this::getPathTo112, 2);
  }

  @Test
  void traceroute_localAS_BR() throws IOException {
    testTraceroute(this::getPathToLocalAS_BR, 0);
  }

  private void testTraceroute(Supplier<Path> path, int nHops) throws IOException {
    MockNetwork.startTiny();
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      channel.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      Collection<Scmp.TracerouteMessage> results = channel.sendTracerouteRequest(path.get());

      int n = 0;
      for (Scmp.TracerouteMessage result : results) {
        assertEquals(n++, result.getSequenceNumber());
        assertEquals(Scmp.TypeCode.TYPE_131, result.getTypeCode());
        assertTrue(result.getNanoSeconds() > 0);
        assertTrue(result.getNanoSeconds() < 10_000_000); // 10 ms
        assertFalse(result.isTimedOut());
        if (n == 1) {
          assertEquals(ScionUtil.parseIA("1-ff00:0:112"), result.getIsdAs());
          assertEquals(42, result.getIfID());
        }
        if (n == 2) {
          assertEquals(ScionUtil.parseIA("1-ff00:0:110"), result.getIsdAs());
          assertEquals(42, result.getIfID());
        }
      }

      assertEquals(nHops, results.size());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void traceroute_timeout() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      channel.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      assertEquals(1000, channel.getTimeOut());
      channel.setTimeOut(100);
      assertEquals(100, channel.getTimeOut());
      MockNetwork.dropNextPackets(2);
      Collection<Scmp.TracerouteMessage> results1 = channel.sendTracerouteRequest(path);
      assertEquals(2, results1.size());
      for (Scmp.TracerouteMessage result : results1) {
        assertTrue(result.isTimedOut());
        assertEquals(Scmp.TypeCode.TYPE_130, result.getTypeCode());
        assertEquals(100 * 1_000_000, result.getNanoSeconds());
      }

      // retry
      Collection<Scmp.TracerouteMessage> results2 = channel.sendTracerouteRequest(path);
      assertEquals(2, results2.size());
      for (Scmp.TracerouteMessage result : results2) {
        assertFalse(result.isTimedOut());
        assertEquals(Scmp.TypeCode.TYPE_131, result.getTypeCode());
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void traceroute_IOException() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      channel.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      MockNetwork.stopTiny();
      // IOException because network is down
      assertThrows(IOException.class, () -> channel.sendTracerouteRequest(path));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void traceroute_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // Router will return SCMP error
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.TypeCode.TYPE_1_CODE_0);
      Throwable t = assertThrows(IOException.class, () -> channel.sendTracerouteRequest(path));
      assertTrue(listenerWasTriggered.get());
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_1_CODE_0.getText()));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  private Path getPathTo112() {
    try {
      InetAddress zero = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
      return getPathTo112(zero);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private Path getPathTo112(InetAddress dstAddress) {
    ScionService service = Scion.defaultService();
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    return service.getPaths(dstIA, dstAddress, Constants.SCMP_PORT).get(0);
  }

  private Path getPathToLocalAS_BR() {
    // Border router address
    return getPathToLocalAS(MockNetwork.BORDER_ROUTER_IPV4, MockNetwork.BORDER_ROUTER_PORT1);
  }

  private Path getPathToLocalAS_BR_30041() {
    // Border router address
    return getPathToLocalAS(MockNetwork.BORDER_ROUTER_IPV4, Constants.SCMP_PORT);
  }

  private Path getPathToLocalAS(String addressStr, int port) {
    ScionService service = Scion.defaultService();
    long dstIA = service.getLocalIsdAs();
    try {
      // Service address
      InetAddress addr = InetAddress.getByName(addressStr);
      List<Path> paths = service.getPaths(dstIA, new InetSocketAddress(addr, port));
      return paths.get(0);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void setUpScmpResponder_echo() throws IOException, InterruptedException {
    MockNetwork.startTiny();
    MockScmpHandler.stop(); // Shut down SCMP handler
    Path path = getPathTo112(InetAddress.getLoopbackAddress());
    // sender is in 110; responder is in 112
    Thread responder = null;
    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      sender.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      sender.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      // start responder
      responder = startResponderThread(null);

      // send request
      for (int i = 0; i < 10; i++) {
        Scmp.EchoMessage msg = sender.sendEchoRequest(path, ByteBuffer.allocate(0));
        assertNotNull(msg);
        assertFalse(msg.isTimedOut(), "i=" + i);
        assertEquals(Scmp.TypeCode.TYPE_129, msg.getTypeCode());
      }
    } finally {
      stopResponderThread(responder);
      MockNetwork.stopTiny();
    }
  }

  @Test
  void setUpScmpResponder_echo_blocked() throws IOException, InterruptedException {
    MockNetwork.startTiny();
    MockScmpHandler.stop(); // Shut down SCMP handler
    Path path = getPathTo112(InetAddress.getLoopbackAddress());
    // sender is in 110; responder is in 112
    Thread responder = null;
    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      sender.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      sender.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      // start responder
      AtomicInteger dropCount = new AtomicInteger();
      responder = startResponderThread((echoMsg) -> dropCount.incrementAndGet() < -42);

      // send request
      sender.setTimeOut(100);
      for (int i = 0; i < 2; i++) {
        Scmp.EchoMessage result = sender.sendEchoRequest(path, ByteBuffer.allocate(0));
        assertTrue(result.isTimedOut());
        assertEquals(100 * 1_000_000, result.getNanoSeconds());
        assertEquals(i, result.getSequenceNumber());
      }
      assertEquals(2, dropCount.get());
    } finally {
      stopResponderThread(responder);
      MockNetwork.stopTiny();
    }
  }

  private Thread startResponderThread(Predicate<Scmp.EchoMessage> predicate)
      throws InterruptedException {
    CountDownLatch barrier = new CountDownLatch(1);
    Thread t = new Thread(() -> scmpResponder(barrier, predicate));
    t.start();
    barrier.await();
    Thread.sleep(50);
    return t;
  }

  private void stopResponderThread(Thread responder) throws InterruptedException {
    if (responder != null) {
      responder.join(100);
      responder.interrupt(); // just in case.
    }
  }

  private void scmpResponder(CountDownLatch barrier, Predicate<Scmp.EchoMessage> predicate) {
    try (ScmpResponder responder = Scmp.newResponderBuilder().build()) {
      responder.setScmpErrorListener(
          scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      responder.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      responder.setScmpEchoListener(predicate);
      barrier.countDown();
      responder.start();
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  private abstract static class ScmpHandler<T> implements ScmpSenderAsync.ScmpResponseHandler {
    volatile Scmp.ErrorMessage error = null;
    volatile Throwable exception = null;

    void handleError(Scmp.ErrorMessage msg) {
      synchronized (this) {
        error = msg;
        this.notifyAll();
      }
    }

    void handleException(Throwable t) {
      synchronized (this) {
        exception = t;
        this.notifyAll();
      }
    }

    protected T waitForResult(Supplier<T> checkResult) throws IOException {
      while (true) {
        synchronized (this) {
          try {
            if (error != null) {
              throw new IOException(error.getTypeCode().getText());
            }
            if (exception != null) {
              throw new IOException(exception);
            }
            T result = checkResult.get();
            if (result != null) {
              return result;
            }
            this.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScionRuntimeException(e);
          }
        }
      }
    }

    void reset() {
      error = null;
      exception = null;
    }

    @Override
    public void onError(Scmp.ErrorMessage msg) {
      handleError(msg);
    }

    @Override
    public void onException(Throwable t) {
      handleException(t);
    }
  }

  private static class EchoHandler extends ScmpHandler<Scmp.EchoMessage> {
    Scmp.EchoMessage response = null;

    void handle(Scmp.EchoMessage msg) {
      synchronized (this) {
        response = msg;
        this.notifyAll();
      }
    }

    Scmp.EchoMessage get() throws IOException {
      return super.waitForResult(() -> response);
    }

    @Override
    void reset() {
      super.reset();
      response = null;
    }

    @Override
    public void onResponse(Scmp.TimedMessage msg) {
      if (msg.getTypeCode() == Scmp.TypeCode.TYPE_129) {
        handle((Scmp.EchoMessage) msg);
      } else {
        throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
      }
    }

    @Override
    public void onTimeout(Scmp.TimedMessage msg) {
      if (msg.getTypeCode() == Scmp.TypeCode.TYPE_128) {
        handle((Scmp.EchoMessage) msg);
      } else {
        throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
      }
    }
  }

  private static class TraceHandler extends ScmpHandler<List<Scmp.TracerouteMessage>> {
    private final ArrayList<Scmp.TracerouteMessage> responses = new ArrayList<>();
    private final int count;

    TraceHandler(int count) {
      this.count = count;
    }

    void handle(Scmp.TracerouteMessage msg) {
      synchronized (this) {
        responses.add(msg);
        if (responses.size() >= count) {
          this.notifyAll();
        }
      }
    }

    List<Scmp.TracerouteMessage> get() throws IOException {
      return super.waitForResult(() -> responses.size() >= count ? responses : null);
    }

    @Override
    void reset() {
      super.reset();
      responses.clear();
    }

    @Override
    public void onResponse(Scmp.TimedMessage msg) {
      if (msg.getTypeCode() == Scmp.TypeCode.TYPE_131) {
        handle((Scmp.TracerouteMessage) msg);
      } else {
        throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
      }
    }

    @Override
    public void onTimeout(Scmp.TimedMessage msg) {
      if (msg.getTypeCode() == Scmp.TypeCode.TYPE_130) {
        handle((Scmp.TracerouteMessage) msg);
      } else {
        throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
      }
    }
  }

  private static class ResponseHandler implements ScmpSenderAsync.ScmpResponseHandler {
    private final EchoHandler echoHandler;
    private final TraceHandler traceHandler;

    ResponseHandler() {
      echoHandler = new EchoHandler();
      traceHandler = null;
    }

    ResponseHandler(int count) {
      echoHandler = null;
      traceHandler = new TraceHandler(count);
    }

    @Override
    public void onResponse(Scmp.TimedMessage msg) {
      if (msg.getTypeCode() == Scmp.TypeCode.TYPE_129) {
        echoHandler.handle((Scmp.EchoMessage) msg);
      } else if (msg.getTypeCode() == Scmp.TypeCode.TYPE_131) {
        traceHandler.handle((Scmp.TracerouteMessage) msg);
      } else {
        throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
      }
    }

    @Override
    public void onTimeout(Scmp.TimedMessage msg) {
      if (msg.getTypeCode() == Scmp.TypeCode.TYPE_128) {
        echoHandler.handle((Scmp.EchoMessage) msg);
      } else if (msg.getTypeCode() == Scmp.TypeCode.TYPE_130) {
        traceHandler.handle((Scmp.TracerouteMessage) msg);
      } else {
        throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
      }
    }

    @Override
    public void onError(Scmp.ErrorMessage msg) {
      if (echoHandler != null) {
        echoHandler.handleError(msg);
      }
      if (traceHandler != null) {
        traceHandler.handleError(msg);
      }
    }

    @Override
    public void onException(Throwable t) {
      if (echoHandler != null) {
        echoHandler.handleException(t);
      }
      if (traceHandler != null) {
        traceHandler.handleException(t);
      }
    }
  }
}
