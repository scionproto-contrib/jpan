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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockScmpHandler;

public class SCMPTest {
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

  // TODO test that we use these properly!
  // SCMP External Interface Down
  // demo.runDemo(ScionUtil.parseIA("71-2:0:3b")); // "KREONET Seoul"  ERROR: SCMP error: 5:0:''
  byte[] raw_5_0_a = {
    0, 0, 0, 1, -54, 35, 0, -72, 1, 0, 0, 0, 0, 64, 0, 2, 0, 0, 0, 9, 0, 71, 0, 2, 0, 0, 0, 62, 10,
    6, 36, 123, 10, 10, 0, 1, 70, 0, 80, -128, 1, 0, -54, -50, 102, -71, -54, -15, 1, 0, 72, 100,
    102, -70, 29, 16, 0, 63, 0, 0, 0, 4, 12, 41, 65, 8, 9, 50, 0, 63, 0, 4, 0, 2, 115, 60, -94,
    -115, -93, 51, 0, 63, 0, 6, 0, 1, 34, 46, 31, -95, 73, 44, 0, 63, 0, 13, 0, 5, -5, -44, 3, 3, 7,
    -109, 0, 63, 0, 30, 0, 0, -127, -68, 18, 79, -55, -81, 0, 63, 0, 0, 0, 5, -21, 85, -1, 110,
    -108, -21, 0, 63, 0, 1, 0, 0, -39, 8, 31, 113, -62, 56, 5, 0, -69, -99, 0, 71, 0, 2, 0, 0, 0,
    62, 0, 0, 0, 0, 0, 0, 0, 6, 0, 0, 0, 1, -54, 35, 0, 24, 1, 0, 0, 0, 0, 71, 0, 2, 0, 0, 0, 59, 0,
    64, 0, 2, 0, 0, 0, 9, 1, 2, 3, 4, 10, 6, 36, 123, 68, 0, 33, 64, 0, 0, -93, 49, 102, -70, 29,
    16, 0, 0, 19, 52, 102, -71, -54, -15, 0, 63, 0, 1, 0, 0, -39, 8, 31, 113, -62, 56, 0, 63, 0, 0,
    0, 5, -21, 85, -1, 110, -108, -21, 0, 63, 0, 30, 0, 0, -127, -68, 18, 79, -55, -81, 0, 63, 0,
    13, 0, 5, -5, -44, 3, 3, 7, -109, 2, 63, 0, 6, 0, 1, 34, 46, 31, -95, 73, 44, 0, 63, 0, 4, 0, 2,
    115, 60, -94, -115, -93, 51, 0, 63, 0, 0, 0, 4, 12, 41, 65, 8, 9, 50, -126, 0, 0, 0, 117, 89, 0,
    6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  // TODO test that we use these properly!
  // SCMP External Interface Down
  // demo.runDemo(ScionUtil.parseIA("71-2:0:3f")); // "KREONET Chicago"  ERROR: SCMP error: 5:0:''
  byte[] raw_5_0_b = {
    0, 0, 0, 1, -54, 32, 0, -84, 1, 0, 0, 0, 0, 64, 0, 2, 0, 0, 0, 9, 0, 71, 0, 2, 0, 0, 0, 62, 10,
    6, 36, 123, 10, 10, 0, 1, 69, 0, 64, -128, 1, 0, -112, 22, 102, -71, -54, -6, 1, 0, -128, 47,
    102, -70, 29, -88, 0, 63, 0, 0, 0, 1, -91, -81, -105, -4, 59, -75, 0, 63, 0, 5, 0, 2, -6, -23,
    -80, 22, -48, -2, 0, 63, 0, 14, 0, 6, -8, -36, 1, -36, -125, 75, 0, 63, 0, 29, 0, 0, 42, -58,
    43, 111, 62, -108, 0, 63, 0, 0, 0, 5, 31, 77, -19, -20, -38, -122, 0, 63, 0, 1, 0, 0, 99, -15,
    -86, -76, 51, -86, 5, 0, 71, 21, 0, 71, 0, 2, 0, 0, 0, 62, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 1,
    -54, 32, 0, 24, 1, 0, 0, 0, 0, 71, 0, 2, 0, 0, 0, 63, 0, 64, 0, 2, 0, 0, 0, 9, 1, 2, 3, 4, 10,
    6, 36, 123, 68, 0, 33, 0, 0, 0, -97, 98, 102, -70, 29, -88, 0, 0, -110, 35, 102, -71, -54, -6,
    0, 63, 0, 1, 0, 0, 99, -15, -86, -76, 51, -86, 0, 63, 0, 0, 0, 5, 31, 77, -19, -20, -38, -122,
    0, 63, 0, 29, 0, 0, 42, -58, 43, 111, 62, -108, 0, 63, 0, 14, 0, 6, -8, -36, 1, -36, -125, 75,
    2, 63, 0, 5, 0, 2, -6, -23, -80, 22, -48, -2, 0, 63, 0, 0, 0, 1, -91, -81, -105, -4, 59, -75,
    -126, 0, 0, 0, 117, 89, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
  };

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
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
    try (ScmpChannel channel = Scmp.createChannel()) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      byte[] data = new byte[] {1, 2, 3, 4, 5};
      Path path = pathSupplier.get();
      Scmp.EchoMessage result = channel.sendEchoRequest(path, 42, ByteBuffer.wrap(data));
      assertEquals(42, result.getSequenceNumber());
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
    try (ScmpChannel channel = Scmp.createChannel()) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      channel.setTimeOut(100);
      MockNetwork.dropNextPackets(1);
      Scmp.EchoMessage result1 = channel.sendEchoRequest(path, 42, ByteBuffer.allocate(0));
      assertTrue(result1.isTimedOut());
      assertEquals(100 * 1_000_000, result1.getNanoSeconds());
      assertEquals(42, result1.getSequenceNumber());

      // try again
      MockNetwork.answerNextScmpEchos(1);
      Scmp.EchoMessage result2 = channel.sendEchoRequest(path, 43, ByteBuffer.allocate(0));
      assertEquals(Scmp.TypeCode.TYPE_129, result2.getTypeCode());
      assertFalse(result2.isTimedOut());
      assertTrue(result2.getNanoSeconds() > 0);
      assertTrue(result2.getNanoSeconds() < 10_000_000); // 10 ms
      assertEquals(43, result2.getSequenceNumber());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void echo_IOException() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    try (ScmpChannel channel = Scmp.createChannel()) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      MockNetwork.stopTiny();
      // Exception because network is down.
      assertThrows(
          IOException.class, () -> channel.sendEchoRequest(path, 42, ByteBuffer.allocate(0)));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void echo_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel()) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // Router will return SCMP error
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.TypeCode.TYPE_1_CODE_0);
      Throwable t =
          assertThrows(
              IOException.class,
              () -> channel.sendEchoRequest(getPathTo112(), 42, ByteBuffer.allocate(0)));
      assertTrue(listenerWasTriggered.get());
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
    try (ScmpChannel channel = Scmp.createChannel()) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
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
    try (ScmpChannel channel = Scmp.createChannel()) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      assertEquals(1000, channel.getTimeOut());
      channel.setTimeOut(100);
      assertEquals(100, channel.getTimeOut());
      MockNetwork.dropNextPackets(1);
      Collection<Scmp.TracerouteMessage> results1 = channel.sendTracerouteRequest(path);
      assertEquals(1, results1.size());
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
    try (ScmpChannel channel = Scmp.createChannel()) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
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
    try (ScmpChannel channel = Scmp.createChannel()) {
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
    try (ScmpChannel sender = Scmp.createChannel()) {
      sender.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      sender.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      // start responder
      CountDownLatch barrier = new CountDownLatch(1);
      Thread t = new Thread(() -> scmpResponder(barrier, null));
      t.start();
      barrier.await();
      Thread.sleep(50);

      // send request
      for (int i = 0; i < 10; i++) {
        Scmp.EchoMessage msg = sender.sendEchoRequest(path, 1, ByteBuffer.allocate(0));
        assertNotNull(msg);
        assertFalse(msg.isTimedOut());
        assertEquals(Scmp.TypeCode.TYPE_129, msg.getTypeCode());
      }

      // finish
      t.join(100);
      t.interrupt(); // just in case.
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void setUpScmpResponder_echo_blocked() throws IOException, InterruptedException {
    MockNetwork.startTiny();
    MockScmpHandler.stop(); // Shut down SCMP handler
    Path path = getPathTo112(InetAddress.getLoopbackAddress());
    // sender is in 110; responder is in 112
    try (ScmpChannel sender = Scmp.createChannel()) {
      sender.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      sender.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      // start responder
      CountDownLatch barrier = new CountDownLatch(1);
      AtomicInteger dropCount = new AtomicInteger();
      Thread t =
          new Thread(() -> scmpResponder(barrier, (echoMsg) -> dropCount.incrementAndGet() < -42));
      t.start();
      barrier.await();
      Thread.sleep(50);

      // send request
      sender.setTimeOut(100);
      for (int i = 0; i < 2; i++) {
        Scmp.EchoMessage result = sender.sendEchoRequest(path, 42, ByteBuffer.allocate(0));
        assertTrue(result.isTimedOut());
        assertEquals(100 * 1_000_000, result.getNanoSeconds());
        assertEquals(42, result.getSequenceNumber());
      }
      assertEquals(2, dropCount.get());

      // finish
      t.join(100);
      t.interrupt(); // just in case.
    } finally {
      MockNetwork.stopTiny();
    }
  }

  private void scmpResponder(CountDownLatch barrier, Predicate<Scmp.EchoMessage> predicate) {
    try (ScmpChannel responder = Scmp.createChannel(Constants.SCMP_PORT)) {
      responder.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      responder.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      responder.setScmpEchoListener(predicate);
      barrier.countDown();
      responder.setUpScmpEchoResponder();
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }
}
