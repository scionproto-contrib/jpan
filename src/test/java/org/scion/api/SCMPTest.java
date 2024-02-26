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

package org.scion.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.demo.inspector.ScionPacketInspector;
import org.scion.internal.ScionHeaderParser;
import org.scion.testutil.MockNetwork;

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
  void echo_localAS() throws IOException {
    testEcho(this::getPathToLocalAS);
  }

  private void testEcho(Supplier<RequestPath> path) throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel(path.get())) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      byte[] data = new byte[] {1, 2, 3, 4, 5};
      Scmp.EchoMessage result = channel.sendEchoRequest(42, ByteBuffer.wrap(data));
      assertEquals(42, result.getSequenceNumber());
      assertEquals(Scmp.TypeCode.TYPE_129, result.getTypeCode());
      assertTrue(result.getNanoSeconds() > 0);
      assertTrue(result.getNanoSeconds() < 10_000_000); // 10 ms
      assertArrayEquals(data, result.getData());
      assertFalse(result.isTimedOut());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void echo_timeout() throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      channel.setTimeOut(100);
      MockNetwork.dropNextPackets(1);
      Scmp.EchoMessage result1 = channel.sendEchoRequest(42, ByteBuffer.allocate(0));
      assertTrue(result1.isTimedOut());
      assertEquals(100 * 1_000_000, result1.getNanoSeconds());
      assertEquals(42, result1.getSequenceNumber());

      // try again
      Scmp.EchoMessage result2 = channel.sendEchoRequest(43, ByteBuffer.allocate(0));
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
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      MockNetwork.stopTiny();
      // Exception because network is down.
      assertThrows(IOException.class, () -> channel.sendEchoRequest(42, ByteBuffer.allocate(0)));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void echo_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      // Router will return SCMP error
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.TypeCode.TYPE_1_CODE_0);
      Throwable t =
          assertThrows(
              IOException.class, () -> channel.sendEchoRequest(42, ByteBuffer.allocate(0)));
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
  void traceroute_localAS() throws IOException {
    testTraceroute(this::getPathToLocalAS, 0);
  }

  private void testTraceroute(Supplier<RequestPath> path, int nHops) throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel(path.get())) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      Collection<Scmp.TracerouteMessage> results = channel.sendTracerouteRequest();

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
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      assertEquals(1000, channel.getTimeOut());
      channel.setTimeOut(100);
      assertEquals(100, channel.getTimeOut());
      MockNetwork.dropNextPackets(1);
      Collection<Scmp.TracerouteMessage> results1 = channel.sendTracerouteRequest();
      assertEquals(1, results1.size());
      for (Scmp.TracerouteMessage result : results1) {
        assertTrue(result.isTimedOut());
        assertEquals(Scmp.TypeCode.TYPE_130, result.getTypeCode());
        assertEquals(100 * 1_000_000, result.getNanoSeconds());
      }

      // retry
      Collection<Scmp.TracerouteMessage> results2 = channel.sendTracerouteRequest();
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
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      MockNetwork.stopTiny();
      // IOException because network is down
      assertThrows(IOException.class, channel::sendTracerouteRequest);
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void traceroute_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      // Router will return SCMP error
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.TypeCode.TYPE_1_CODE_0);
      Throwable t = assertThrows(IOException.class, channel::sendTracerouteRequest);
      assertTrue(listenerWasTriggered.get());
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_1_CODE_0.getText()));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  private RequestPath getPathTo112() {
    ScionService service = Scion.defaultService();
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    List<RequestPath> paths = service.getPaths(dstIA, new byte[] {0, 0, 0, 0}, 12345);
    return paths.get(0);
  }

  private RequestPath getPathToLocalAS() {
    ScionService service = Scion.defaultService();
    long dstIA = service.getLocalIsdAs();
    try {
      InetAddress addr = InetAddress.getByName(MockNetwork.BORDER_ROUTER_HOST);
      int port = MockNetwork.BORDER_ROUTER_PORT1;
      List<RequestPath> paths = service.getPaths(dstIA, new InetSocketAddress(addr, port));
      return paths.get(0);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }
}
