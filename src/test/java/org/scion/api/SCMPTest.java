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
    assertEquals(Scmp.ScmpTypeCode.TYPE_4_CODE_51, spi.getScmpHeader().getCode());

    // Test that error can be parsed without throwing an exception
    ByteBuffer buffer = ByteBuffer.wrap(PING_ERROR_4_51_HK);
    InetAddress srcAddr = Inet4Address.getByAddress(new byte[] {0, 0, 0, 0});
    InetSocketAddress srcAddress = new InetSocketAddress(srcAddr, 12345);
    ResponsePath path = ScionHeaderParser.extractResponsePath(buffer, srcAddress);
    assertNotNull(path);
  }

  @Test
  void echo() throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      byte[] data = new byte[] {1, 2, 3, 4, 5};
      Scmp.EchoMessage result = channel.sendEchoRequest(42, ByteBuffer.wrap(data));
      assertEquals(42, result.getSequenceNumber());
      assertEquals(Scmp.ScmpTypeCode.TYPE_129, result.getTypeCode());
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
      channel.setTimeOut(1_000);
      MockNetwork.dropNextPackets(1);
      Scmp.EchoMessage result = channel.sendEchoRequest(42, ByteBuffer.allocate(0));
      assertTrue(result.isTimedOut());
      assertEquals(1_000 * 1_000_000, result.getNanoSeconds());
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
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.ScmpTypeCode.TYPE_1_CODE_0);
      Throwable t =
          assertThrows(
              IOException.class, () -> channel.sendEchoRequest(42, ByteBuffer.allocate(0)));
      assertTrue(listenerWasTriggered.get());
      assertTrue(t.getMessage().contains(Scmp.ScmpTypeCode.TYPE_1_CODE_0.getText()));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void traceroute() throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      Collection<Scmp.TracerouteMessage> results = channel.sendTracerouteRequest();
      channel.setTimeOut(Integer.MAX_VALUE); // TODO ??

      int n = 0;
      for (Scmp.TracerouteMessage result : results) {
        assertEquals(n++, result.getSequenceNumber());
        assertEquals(Scmp.ScmpTypeCode.TYPE_131, result.getTypeCode());
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

      assertEquals(2, results.size());
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
      MockNetwork.dropNextPackets(2);
      Collection<Scmp.TracerouteMessage> results = channel.sendTracerouteRequest();

      assertEquals(1, results.size());
      for (Scmp.TracerouteMessage result : results) {
        assertTrue(result.isTimedOut());
        assertEquals(Scmp.ScmpTypeCode.TYPE_130, result.getTypeCode());
        assertEquals(1_000 * 1_000_000, result.getNanoSeconds());
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
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.ScmpTypeCode.TYPE_1_CODE_0);
      Throwable t = assertThrows(IOException.class, channel::sendTracerouteRequest);
      assertTrue(listenerWasTriggered.get());
      assertTrue(t.getMessage().contains(Scmp.ScmpTypeCode.TYPE_1_CODE_0.getText()));
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
}
