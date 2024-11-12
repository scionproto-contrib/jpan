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
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.internal.Shim;
import org.scion.jpan.testutil.MockDatagramChannel;
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

  @BeforeEach
  void beforeEach() {
    System.setProperty(Constants.PROPERTY_SHIM, "false");
    Shim.uninstall();
  }

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
    System.clearProperty(Constants.PROPERTY_SHIM);
  }

  @AfterEach
  public void afterEach() {
    MockNetwork.stopTiny();
    if (!errors.isEmpty()) {
      for (String s : errors) {
        System.err.println("ERROR: " + s);
      }
      fail(errors.poll());
    }
  }

  @Test
  void parse_error_invalidMAC() throws IOException {
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
  void getHandler() throws IOException {
    MockNetwork.startTiny();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      assertEquals(handler, channel.getHandler());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void getOption() throws IOException {
    MockNetwork.startTiny();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      assertFalse(channel.getOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE));
      assertEquals(handler, channel.getHandler());
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      assertTrue(channel.getOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendEcho() throws IOException {
    testEcho(this::getPathTo112, 5);
  }

  @Test
  void sendEcho_localAS_BR() throws IOException {
    // Q: does this test make sense? We send a ping to port 30555.... should that really work?
    int n = 5;
    testEcho(this::getPathToLocalAS_BR, n);
    // These are sent to the daemon port 31004 which is in the unmapped port range.
    assertEquals(2 * n, MockNetwork.getAndResetForwardCount());
    assertEquals(n, MockScmpHandler.getAndResetAnswerTotal());
  }

  @Test
  void sendEcho_localAS_BR_30041() throws IOException {
    int n = 5;
    testEcho(this::getPathToLocalAS_BR_30041, n);
    assertEquals(0, MockNetwork.getAndResetForwardCount());
    assertEquals(n, MockScmpHandler.getAndResetAnswerTotal());
  }

  private void testEcho(Supplier<Path> pathSupplier, int n) throws IOException {
    MockNetwork.startTiny();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      byte[] data = new byte[] {1, 2, 3, 4, 5};
      Path path = pathSupplier.get();
      for (int i = 0; i < n; i++) {
        int seqId = channel.sendEcho(path, ByteBuffer.wrap(data));
        assertEquals(i, seqId);
        Scmp.EchoMessage result = handler.get();
        assertEquals(seqId, result.getSequenceNumber(), "i=" + i);
        assertEquals(Scmp.TypeCode.TYPE_129, result.getTypeCode(), "T/O=" + result.isTimedOut());
        assertTrue(result.getNanoSeconds() > 0);
        assertTrue(result.getNanoSeconds() < 100_000_000); // 10 ms
        assertArrayEquals(data, result.getData());
        assertFalse(result.isTimedOut());
        Path returnPath = result.getPath();
        assertEquals(path.getRemoteAddress(), returnPath.getRemoteAddress());
        assertEquals(Constants.SCMP_PORT, returnPath.getRemotePort());
        assertEquals(path.getRemoteIsdAs(), returnPath.getRemoteIsdAs());
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendEcho_async() throws IOException {
    testEchoAsync(this::getPathTo112, 25);
  }

  @Test
  void sendEcho_localAS_BR_async() throws IOException {
    int n = 25;
    testEchoAsync(this::getPathToLocalAS_BR, n);
    // These are sent to the daemon port 31004 which is in the unmapped port range.
    assertEquals(2 * n, MockNetwork.getAndResetForwardCount()); // 1!
    assertEquals(n, MockScmpHandler.getAndResetAnswerTotal());
  }

  @Test
  void sendEcho_localAS_BR_30041_async() throws IOException {
    int n = 25;
    testEchoAsync(this::getPathToLocalAS_BR_30041, n);
    assertEquals(0, MockNetwork.getAndResetForwardCount());
    assertEquals(n, MockScmpHandler.getAndResetAnswerTotal());
  }

  private void testEchoAsync(Supplier<Path> pathSupplier, int n) throws IOException {
    MockNetwork.startTiny();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      byte[] data = new byte[] {1, 2, 3, 4, 5};
      Path path = pathSupplier.get();
      HashSet<Integer> seqIDs = new HashSet<>();
      for (int i = 0; i < n; i++) {
        seqIDs.add(channel.sendEcho(path, ByteBuffer.wrap(data)));
      }

      List<Scmp.EchoMessage> results = handler.get(n);
      long nTimedOut = results.stream().filter(Scmp.TimedMessage::isTimedOut).count();
      assertEquals(0, nTimedOut);
      for (int i = 0; i < n; i++) {
        Scmp.EchoMessage result = results.get(i);
        assertTrue(seqIDs.contains(result.getSequenceNumber()));
        seqIDs.remove(result.getSequenceNumber());
        assertEquals(Scmp.TypeCode.TYPE_129, result.getTypeCode(), "T/O=" + result.isTimedOut());
        assertTrue(result.getNanoSeconds() > 0);
        assertTrue(result.getNanoSeconds() < 500_000_000); // 10 ms
        assertArrayEquals(data, result.getData());
        assertFalse(result.isTimedOut());
        Path returnPath = result.getPath();
        assertEquals(path.getRemoteAddress(), returnPath.getRemoteAddress());
        assertEquals(Constants.SCMP_PORT, returnPath.getRemotePort());
        assertEquals(path.getRemoteIsdAs(), returnPath.getRemoteIsdAs());
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendEcho_timeout() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      channel.setTimeOut(100);
      MockNetwork.dropNextPackets(1);
      int seqId1 = channel.sendEcho(path, ByteBuffer.allocate(0));
      Scmp.EchoMessage result1 = handler.get();
      assertTrue(result1.isTimedOut());
      assertEquals(100 * 1_000_000, result1.getNanoSeconds());
      assertEquals(seqId1, result1.getSequenceNumber());

      // try again
      handler.reset();
      int seqId2 = channel.sendEcho(path, ByteBuffer.allocate(0));
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
  void sendEcho_IOException() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = exceptionSender(handler)) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      channel.sendEcho(path, ByteBuffer.allocate(0));
      // IOException thrown by MockChannel
      assertThrows(IOException.class, handler::get);
      assertEquals(1, handler.exceptionCounter.getAndSet(0));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendEcho_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    EchoHandler handler = new EchoHandler();
    try (ScmpSenderAsync channel = errorSender(handler)) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // MockChannel will return SCMP error
      channel.sendEcho(getPathTo112(), ByteBuffer.allocate(0));
      Throwable t = assertThrows(IOException.class, handler::get);
      assertEquals(1, handler.errorCounter.getAndSet(0));
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_4_CODE_51.getText()));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTraceroute() throws IOException {
    testTraceroute(this::getPathTo112, 2);
  }

  @Test
  void sendTraceroute_localAS_BR() throws IOException {
    testTraceroute(this::getPathToLocalAS_BR, 0);
  }

  private void testTraceroute(Supplier<Path> path, int nHops) throws IOException {
    MockNetwork.startTiny();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      List<Integer> ids = channel.sendTraceroute(path.get());
      Collection<Scmp.TracerouteMessage> results = handler.get(ids.size());
      for (Scmp.TracerouteMessage result : results) {
        assertEquals(Scmp.TypeCode.TYPE_131, result.getTypeCode(), "T/O=" + result.isTimedOut());
        assertTrue(result.getNanoSeconds() > 0);
        long ms = result.getNanoSeconds() / 1_000_000;
        assertTrue(ms < 20, "ms=" + ms); // 10 ms
        assertFalse(result.isTimedOut());
        if (result.getSequenceNumber() == 0) {
          assertEquals(ScionUtil.parseIA("1-ff00:0:112"), result.getIsdAs());
          assertEquals(42, result.getIfID());
        }
        if (result.getSequenceNumber() == 1) {
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
  void sendTraceroute_async() throws IOException {
    testTracerouteAsync(this::getPathTo112, 25);
  }

  private void testTracerouteAsync(Supplier<Path> pathSupplier, int nRepeat) throws IOException {
    MockNetwork.startTiny();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      Path path = pathSupplier.get();
      HashSet<Integer> seqIDs = new HashSet<>();
      for (int i = 0; i < nRepeat; i++) {
        seqIDs.addAll(channel.sendTraceroute(path));
      }

      List<Scmp.TracerouteMessage> results = handler.get(seqIDs.size());
      long nTimedOut = results.stream().filter(Scmp.TimedMessage::isTimedOut).count();
      assertEquals(0, nTimedOut);
      for (int i = 0; i < nRepeat; i++) {
        Scmp.TracerouteMessage result = results.get(i);
        assertTrue(seqIDs.contains(result.getSequenceNumber()));
        seqIDs.remove(result.getSequenceNumber());
        assertEquals(Scmp.TypeCode.TYPE_131, result.getTypeCode(), "T/O=" + result.isTimedOut());
        assertTrue(result.getNanoSeconds() > 0);
        assertTrue(result.getNanoSeconds() < 500_000_000); // 10 ms
        assertFalse(result.isTimedOut());
        Path returnPath = result.getPath();
        assertEquals(path.getRemoteAddress(), returnPath.getRemoteAddress());
        assertEquals(Constants.SCMP_PORT, returnPath.getRemotePort());
        assertEquals(path.getRemoteIsdAs(), returnPath.getRemoteIsdAs());
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTraceroute_timeout() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      assertEquals(1000, channel.getTimeOut());
      channel.setTimeOut(100);
      assertEquals(100, channel.getTimeOut());
      MockNetwork.dropNextPackets(2);
      List<Integer> ids1 = channel.sendTraceroute(path);
      Collection<Scmp.TracerouteMessage> results1 = handler.get(ids1.size());
      assertEquals(2, results1.size());
      for (Scmp.TracerouteMessage result : results1) {
        assertTrue(result.isTimedOut());
        assertEquals(Scmp.TypeCode.TYPE_130, result.getTypeCode());
        assertEquals(100 * 1_000_000, result.getNanoSeconds());
      }

      // retry
      handler.reset();
      List<Integer> ids2 = channel.sendTraceroute(path);
      Collection<Scmp.TracerouteMessage> results2 = handler.get(ids2.size());
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
  void sendTraceroute_IOException() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = exceptionSender(handler)) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      List<Integer> ids = channel.sendTraceroute(path);
      // IOException thrown by MockChannel
      assertThrows(IOException.class, () -> handler.get(ids.size()));
      assertEquals(1, handler.exceptionCounter.getAndSet(0));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTraceroute_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = errorSender(handler)) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // MockChannel will return SCMP error
      List<Integer> ids = channel.sendTraceroute(path);
      Throwable t = assertThrows(IOException.class, () -> handler.get(ids.size()));
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_4_CODE_51.getText()), t.getMessage());
      assertEquals(1, handler.errorCounter.getAndSet(0));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTracerouteLast() throws IOException {
    testTracerouteLast(this::getPathTo112, true);
  }

  @Test
  void sendTracerouteLast_localAS_BR() throws IOException {
    testTracerouteLast(this::getPathToLocalAS_BR, false);
  }

  private void testTracerouteLast(Supplier<Path> pathSupplier, boolean success) throws IOException {
    MockNetwork.startTiny();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      Path path = pathSupplier.get();
      int seqId = channel.sendTracerouteLast(path);
      if (seqId >= 0) {
        List<Scmp.TracerouteMessage> results = handler.get(1);
        assertEquals(1, results.size());
        Scmp.TracerouteMessage result = results.get(0);
        assertEquals(seqId, result.getSequenceNumber());
        assertEquals(Scmp.TypeCode.TYPE_131, result.getTypeCode());
        assertTrue(result.getNanoSeconds() > 0);
        assertTrue(result.getNanoSeconds() < 100_000_000); // 10 ms
        assertFalse(result.isTimedOut());
        Path returnPath = result.getPath();
        assertEquals(path.getRemoteAddress(), returnPath.getRemoteAddress());
        assertEquals(Constants.SCMP_PORT, returnPath.getRemotePort());
        assertEquals(path.getRemoteIsdAs(), returnPath.getRemoteIsdAs());
        assertTrue(success);
      } else {
        assertFalse(success);
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTracerouteLast_async() throws IOException {
    testTracerouteLastAsync(this::getPathTo112, 25);
  }

  private void testTracerouteLastAsync(Supplier<Path> pathSupplier, int nRepeat)
      throws IOException {
    MockNetwork.startTiny();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      Path path = pathSupplier.get();
      HashSet<Integer> seqIDs = new HashSet<>();
      for (int i = 0; i < nRepeat; i++) {
        seqIDs.add(channel.sendTracerouteLast(path));
      }

      List<Scmp.TracerouteMessage> results = handler.get(seqIDs.size());
      long nTimedOut = results.stream().filter(Scmp.TimedMessage::isTimedOut).count();
      assertEquals(0, nTimedOut);
      for (int i = 0; i < nRepeat; i++) {
        Scmp.TracerouteMessage result = results.get(i);
        assertTrue(seqIDs.contains(result.getSequenceNumber()));
        seqIDs.remove(result.getSequenceNumber());
        assertEquals(Scmp.TypeCode.TYPE_131, result.getTypeCode(), "T/O=" + result.isTimedOut());
        assertTrue(result.getNanoSeconds() > 0);
        assertTrue(result.getNanoSeconds() < 500_000_000); // 10 ms
        assertFalse(result.isTimedOut());
        Path returnPath = result.getPath();
        assertEquals(path.getRemoteAddress(), returnPath.getRemoteAddress());
        assertEquals(Constants.SCMP_PORT, returnPath.getRemotePort());
        assertEquals(path.getRemoteIsdAs(), returnPath.getRemoteIsdAs());
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTracerouteLast_timeout() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = Scmp.newSenderAsyncBuilder(handler).build()) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      channel.setTimeOut(100);
      MockNetwork.dropNextPackets(1);
      int seqId1 = channel.sendTracerouteLast(path);
      List<Scmp.TracerouteMessage> results1 = handler.get(1);
      assertEquals(1, results1.size());
      Scmp.TracerouteMessage result1 = results1.get(0);
      assertTrue(result1.isTimedOut());
      assertEquals(100 * 1_000_000, result1.getNanoSeconds());
      assertEquals(seqId1, result1.getSequenceNumber());

      // try again
      handler.reset();
      int seqId2 = channel.sendTracerouteLast(path);
      List<Scmp.TracerouteMessage> results2 = handler.get(1);
      assertEquals(1, results2.size());
      Scmp.TracerouteMessage result2 = results2.get(0);
      assertEquals(Scmp.TypeCode.TYPE_131, result2.getTypeCode());
      assertFalse(result2.isTimedOut());
      assertTrue(result2.getNanoSeconds() > 0);
      assertTrue(result2.getNanoSeconds() < 10_000_000); // 10 ms
      assertEquals(seqId2, result2.getSequenceNumber());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTracerouteLast_IOException() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = exceptionSender(handler)) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      channel.sendTracerouteLast(path);
      // IOException thrown by MockChannel
      assertThrows(IOException.class, () -> handler.get(1));
      assertEquals(1, handler.exceptionCounter.getAndSet(0));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTracerouteLast_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    TraceHandler handler = new TraceHandler();
    try (ScmpSenderAsync channel = errorSender(handler)) {
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // MockChannel will return SCMP error
      channel.sendTracerouteLast(getPathTo112());
      Throwable t = assertThrows(IOException.class, () -> handler.get(1));
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_4_CODE_51.getText()));
      assertEquals(1, handler.errorCounter.getAndSet(0));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  private Path getPathTo112() {
    return getPathTo112(MockScmpHandler.getAddress().getAddress());
  }

  private Path getPathTo112(InetAddress dstAddress) {
    ScionService service = Scion.defaultService();
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    return service.getPaths(dstIA, dstAddress, Constants.SCMP_PORT).get(0);
  }

  private Path getPathToLocalAS_BR() {
    // Border router address
    return getPathToLocalAS(MockNetwork.getBorderRouterAddress1());
  }

  private Path getPathToLocalAS_BR_30041() {
    // Border router address
    InetSocketAddress address =
        new InetSocketAddress(
            MockNetwork.getBorderRouterAddress1().getAddress(), Constants.SCMP_PORT);
    return getPathToLocalAS(address);
  }

  private Path getPathToLocalAS(InetSocketAddress address) {
    ScionService service = Scion.defaultService();
    long dstIA = service.getLocalIsdAs();
    // Service address
    List<Path> paths = service.getPaths(dstIA, address);
    return paths.get(0);
  }

  private ScmpSenderAsync exceptionSender(ScmpHandler<?> handler) throws IOException {
    MockDatagramChannel errorChannel = MockDatagramChannel.open();
    errorChannel.setSendCallback((byteBuffer, socketAddress) -> 0);
    // This selector throws an Exception when activated.
    MockDatagramChannel.MockSelector selector = MockDatagramChannel.MockSelector.open();
    selector.setConnectCallback(
        () -> {
          throw new IOException();
        });
    return Scmp.newSenderAsyncBuilder(handler)
        .setDatagramChannel(errorChannel)
        .setSelector(selector)
        .build();
  }

  private ScmpSenderAsync errorSender(ScmpHandler<?> handler) throws IOException {
    MockDatagramChannel errorChannel = MockDatagramChannel.open();
    errorChannel.setSendCallback((byteBuffer, socketAddress) -> 0);
    errorChannel.setReceiveCallback(
        buffer -> {
          buffer.put(PING_ERROR_4_51_HK);
          return new InetSocketAddress(MockNetwork.getBorderRouterAddress1().getAddress(), 30041);
        });
    // This selector throws an Exception when activated.
    MockDatagramChannel.MockSelector selector = MockDatagramChannel.MockSelector.open();
    return Scmp.newSenderAsyncBuilder(handler)
        .setDatagramChannel(errorChannel)
        .setSelector(selector)
        .build();
  }

  private abstract static class ScmpHandler<T> implements ScmpSenderAsync.ResponseHandler {
    final AtomicInteger errorCounter = new AtomicInteger();
    final AtomicInteger exceptionCounter = new AtomicInteger();
    volatile Scmp.ErrorMessage error = null;
    volatile Throwable exception = null;
    final List<T> responses = new CopyOnWriteArrayList<>();

    protected List<T> waitForResult(int count) throws IOException {
      try {
        while (true) {
          synchronized (this) {
            if (error != null) {
              throw new IOException(error.getTypeCode().getText());
            }
            if (exception != null) {
              throw new IOException(exception);
            }
            if (responses.size() >= count) {
              ArrayList<T> result = new ArrayList<>(responses);
              responses.clear();
              return result;
            }
            this.wait();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ScionRuntimeException(e);
      }
    }

    protected synchronized void reset() {
      error = null;
      exception = null;
      responses.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public final synchronized void onResponse(Scmp.TimedMessage msg) {
      responses.add((T) msg);
      this.notifyAll();
    }

    @Override
    public final synchronized void onTimeout(Scmp.TimedMessage msg) {
      onResponse(msg);
    }

    @Override
    public final synchronized void onError(Scmp.ErrorMessage msg) {
      error = msg;
      errorCounter.incrementAndGet();
      this.notifyAll();
    }

    @Override
    public final synchronized void onException(Throwable t) {
      exception = t;
      exceptionCounter.incrementAndGet();
      this.notifyAll();
    }
  }

  private static class EchoHandler extends ScmpHandler<Scmp.EchoMessage> {
    Scmp.EchoMessage get() throws IOException {
      return super.waitForResult(1).get(0);
    }

    List<Scmp.EchoMessage> get(int count) throws IOException {
      return super.waitForResult(count);
    }
  }

  private static class TraceHandler extends ScmpHandler<Scmp.TracerouteMessage> {
    /**
     * @param count Number of SCMP response that should be registered before get() returns.
     * @return 'Count' or more messages.
     * @throws IOException in case of a problem
     */
    List<Scmp.TracerouteMessage> get(int count) throws IOException {
      return super.waitForResult(count);
    }
  }
}
