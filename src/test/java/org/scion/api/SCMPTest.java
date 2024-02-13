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
import org.junit.jupiter.api.Disabled;
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
    System.out.println(spi);
    assertEquals(Scmp.ScmpTypeCode.TYPE_4_CODE_51, spi.getScmpHeader().getCode());

    // Test that error can be parsed without throwing an exception
    ByteBuffer buffer = ByteBuffer.wrap(PING_ERROR_4_51_HK);
    InetAddress srcAddr = Inet4Address.getByAddress(new byte[] {0, 0, 0, 0});
    InetSocketAddress srcAddress = new InetSocketAddress(srcAddr, 12345);
    ResponsePath path = ScionHeaderParser.extractResponsePath(buffer, srcAddress);
    assertNotNull(path);
  }

  @Test
  void test_110_221() {
    // Constructed:
    byte[] rawC = {
      0, 0, 48, -128, 0, 0, -20, -120, 101, -89, -1, 40, 1, 0, 1, -128, 101, -89, -1, 35, 0, 63, 0,
      1, 0, 0, 92, 14, 25, -45, -114, -2, 0, 63, 0, -46, 0, 10, 80, 102, -98, 105, -104, 63, 0, 63,
      0, 0, 0, 105, -73, 104, 23, -123, -124, -5, 0, 63, 0, 0, 1, -62, -76, 53, 48, -6, 108, -46, 0,
      63, 1, -9, 0, 0, 122, -101, -88, 24, 66, 53
    };
    //  Path: Path header:   currINF=0  currHP=0  reserved=0  seg0Len=3  seg1Len=2  seg2Len=0
    //  info0=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false,
    // P=false, C=false, reserved=0, segID=60552, timestamp=1705508648}
    //  info1=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false,
    // P=false, C=true, reserved=0, segID=384, timestamp=1705508643}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=1, consEgress=0, mac=101215632592638}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=210, consEgress=10, mac=88401674606655}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=0, consEgress=105, mac=201657699108091}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=0, consEgress=450, mac=198140547984594}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=503, consEgress=0, mac=134808958681653}

    //  byte[] raw = {0, 0, 48, -128, 0, 0, 74, -34, 101, -88, 0, 98, 1, 0, 57, -71, 101, -88, 0,
    // 71, 0, 63, 0, 1, 0, 0, 32, 82, 48, 71, 92, -89, 0, 63, 0, -46, 0, 10, 19, 19, -32, 44, 94,
    // -46, 0, 63, 0, 0, 0, 105, 3, 10, -115, -95, 123, -38, 0, 63, 0, 0, 1, -62, 60, -60, -72, 1,
    // 68, 115, 0, 63, 1, -9, 0, 0, -120, 37, -109, 93, -34, 113}
    //  Path: Path header:   currINF=0  currHP=0  reserved=0  seg0Len=3  seg1Len=2  seg2Len=0
    //  info0=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false,
    // P=false, C=false, reserved=0, segID=19166, timestamp=1705508962}
    //  info1=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false,
    // P=false, C=true, reserved=0, segID=14777, timestamp=1705508935}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=1, consEgress=0, mac=35537369390247}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=210, consEgress=10, mac=20976086310610}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=0, consEgress=105, mac=3343860726746}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=0, consEgress=450, mac=66815598347379}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=503, consEgress=0, mac=149694967570033}

    // Daemon:
    byte[] rawD = {
      0, 0, 48, -128, 0, 0, -116, -7, 101, -89, -1, -82, 1, 0, 1, -128, 101, -89, -1, 35, 0, 63, 0,
      1, 0, 0, 94, 121, 68, 75, 63, 93, 0, 63, 0, -46, 0, 10, -95, -112, 64, 86, 104, -11, 0, 63, 0,
      0, 0, 105, 106, 123, 120, -36, 116, -79, 0, 63, 0, 0, 1, -62, -76, 53, 48, -6, 108, -46, 0,
      63, 1, -9, 0, 0, 122, -101, -88, 24, 66, 53
    };
    //  Path: Path header:   currINF=0  currHP=0  reserved=0  seg0Len=3  seg1Len=2  seg2Len=0
    //  info0=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false,
    // P=false, C=false, reserved=0, segID=36089, timestamp=1705508782}
    //  info1=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false,
    // P=false, C=true, reserved=0, segID=384, timestamp=1705508643}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=1, consEgress=0, mac=103874929835869}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=210, consEgress=10, mac=177640926767349}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=0, consEgress=105, mac=117078541235377}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=0, consEgress=450, mac=198140547984594}
    //  hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false,
    // E=false, expiryTime=63, consIngress=503, consEgress=0, mac=134808958681653}
  }

  @Test
  void echo() throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      channel.setScmpErrorListener(scmpMessage -> fail(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      byte[] data = new byte[] {1, 2, 3, 4, 5};
      Scmp.Result<Scmp.ScmpEcho> result = channel.sendEchoRequest(42, ByteBuffer.wrap(data));
      assertEquals(42, result.getMessage().getSequenceNumber());
      assertEquals(Scmp.ScmpTypeCode.TYPE_129, result.getMessage().getTypeCode());
      assertTrue(result.getNanoSeconds() > 0);
      assertTrue(result.getNanoSeconds() < 10_000_000); // 10 ms
      assertArrayEquals(data, result.getMessage().getData());
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
      Scmp.Result<Scmp.ScmpEcho> result = channel.sendEchoRequest(42, ByteBuffer.allocate(0));
      assertNull(result.getMessage());
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

  @Disabled
  @Test
  void echo_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    try (ScmpChannel channel = Scmp.createChannel(getPathTo112())) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.ScmpTypeCode.TYPE_1_CODE_0);
      // Exception because network is down.
      Throwable t =
          assertThrows(
              IOException.class, () -> channel.sendEchoRequest(42, ByteBuffer.allocate(0)));
      assertTrue(listenerWasTriggered.get());
      t.printStackTrace();
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
      Collection<Scmp.Result<Scmp.ScmpTraceroute>> results = channel.sendTracerouteRequest();
      channel.setTimeOut(Integer.MAX_VALUE);

      int n = 0;
      for (Scmp.Result<Scmp.ScmpTraceroute> result : results) {
        assertEquals(n++, result.getMessage().getSequenceNumber());
        assertEquals(Scmp.ScmpTypeCode.TYPE_131, result.getMessage().getTypeCode());
        assertTrue(result.getNanoSeconds() > 0);
        assertTrue(result.getNanoSeconds() < 10_000_000); // 10 ms
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
      Collection<Scmp.Result<Scmp.ScmpTraceroute>> results = channel.sendTracerouteRequest();

      assertEquals(1, results.size());
      for (Scmp.Result<Scmp.ScmpTraceroute> result : results) {
        assertNull(result.getMessage());
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

  private RequestPath getPathTo112() {
    ScionService service = Scion.defaultService();
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    List<RequestPath> paths = service.getPaths(dstIA, new byte[] {0, 0, 0, 0}, 12345);
    return paths.get(0);
  }

  @Disabled
  @Test
  void error_WrongSrcIsdAs() {
    // TODO
  }

  @Disabled
  @Test
  void error_WrongPacketSize() {
    // TODO
  }

  @Disabled
  @Test
  void error_WrongPacketTooLarge() {
    // TODO
  }

  @Disabled
  @Test
  void testProcessingRules() {
    // https://scion.docs.anapaya.net/en/latest/protocols/scmp.html
  }
}
