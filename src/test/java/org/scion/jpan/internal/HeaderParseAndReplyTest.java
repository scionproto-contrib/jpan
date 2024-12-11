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

package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.PathHeaderScion;
import org.scion.jpan.demo.inspector.ScionHeader;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.demo.inspector.ScmpHeader;
import org.scion.jpan.testutil.ExamplePacket;

class HeaderParseAndReplyTest {

  // Original incoming packet
  private static final byte[] packetBytes = ExamplePacket.PACKET_BYTES_SERVER_E2E_PING;

  // reversed packet
  private static final byte[] reversedBytes = ExamplePacket.PACKET_BYTES_SERVER_E2E_PONG;

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  /** Parse a packet and create a response packet with reversed path. */
  @Test
  void testParseAndReply() throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(packetBytes);
    InetSocketAddress firstHop = new InetSocketAddress("127.0.0.42", 23456);

    ByteBuffer userRcvBuffer = ByteBuffer.allocate(10000);
    ScionHeaderParser.extractUserPayload(buffer, userRcvBuffer);
    Path remoteAddr = ScionHeaderParser.extractResponsePath(buffer, firstHop);
    userRcvBuffer.flip();

    // payload
    byte[] payloadBytes = new byte[userRcvBuffer.remaining()];
    userRcvBuffer.get(payloadBytes);
    String payload = new String(payloadBytes);
    assertEquals("Hello scion", payload);

    // remote address
    assertEquals(44444, remoteAddr.getRemotePort());
    assertEquals("1-ff00:0:110", ScionUtil.toStringIA(remoteAddr.getRemoteIsdAs()));
    assertEquals(firstHop, remoteAddr.getFirstHopAddress());

    // path
    byte[] path = remoteAddr.getRawPath();
    assertEquals(36, path.length);
    for (int i = 0; i < path.length; i++) {
      assertEquals(reversedBytes[i + 48], path[i], "At position:" + i);
    }
  }

  // 0/300
  byte[] baError5 = {
    0, 0, 0, 1, -54, 32, 0, -84, 1, 0, 0, 0, 0, 64, 0, 2, 0, 0, 0, 9, 0, 64, 0, 0, 0, 0, 26, 74,
    -127, -124, -26, 73, 10, -60, 8, 67, 69, 0, 64, -128, 1, 0, -118, 102, 103, 88, 25, 99, 1, 0,
    -71, -86, 103, 88, 54, -103, 0, 63, 0, 0, 0, 20, -80, 106, 76, -30, -66, -86, 0, 63, 0, 12, 0,
    16, 28, 58, -71, 95, -105, -75, 0, 63, 0, 27, 0, 10, 53, 71, 34, 63, -87, -53, 0, 63, 0, 16, 0,
    0, 63, -75, -65, 36, 116, 26, 0, 63, 0, 0, 0, 5, -78, -9, 46, -124, -74, -126, 0, 63, 0, 1, 0,
    0, -118, 38, -58, 52, 32, -31, 5, 0, 16, 83, 0, 64, 0, 0, 0, 0, 26, 74, 0, 0, 0, 0, 0, 0, 0, 27,
    0, 0, 0, 1, -54, 32, 0, 24, 1, 0, 0, 0, 0, 64, 0, 0, 0, 0, 12, -25, 0, 64, 0, 2, 0, 0, 0, 9, 1,
    2, 3, 4, -127, -124, -26, 73, 67, 0, 33, 0, 0, 0, 11, 93, 103, 88, 54, -103, 0, 0, -65, 33, 103,
    88, 25, 99, 0, 63, 0, 1, 0, 0, -118, 38, -58, 52, 32, -31, 0, 63, 0, 0, 0, 5, -78, -9, 46, -124,
    -74, -126, 0, 63, 0, 16, 0, 0, 63, -75, -65, 36, 116, 26, 2, 63, 0, 27, 0, 10, 53, 71, 34, 63,
    -87, -53, 0, 63, 0, 12, 0, 16, 28, 58, -71, 95, -105, -75, 0, 63, 0, 0, 0, 20, -80, 106, 76,
    -30, -66, -86, -126, 0, 0, 0, 121, 24, 1, 116, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
  };

  @Test
  void extractDestination_SCMP_error_5() {
    // Example with actual error from the PRODUCTION network
    ByteBuffer bb = ByteBuffer.wrap(baError5);
    try {
      assertNull(ScionHeaderParser.extractDestinationSocketAddress(bb));
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void extractDstPort_SCMP_129() throws UnknownHostException {
    ByteBuffer data = createScmpResponse(12345, Scmp.TypeCode.TYPE_129, false);
    InetSocketAddress addr = ScionHeaderParser.extractDestinationSocketAddress(data);
    assertNotNull(addr);
    assertEquals(12345, addr.getPort());
  }

  @Test
  void extractDstPort_SCMP_131() throws UnknownHostException {
    ByteBuffer data = createScmpResponse(12345, Scmp.TypeCode.TYPE_131, false);
    InetSocketAddress addr = ScionHeaderParser.extractDestinationSocketAddress(data);
    assertNotNull(addr);
    assertEquals(12345, addr.getPort());
  }

  @Test
  void extractDstPort_SCMP_5() throws UnknownHostException {
    ByteBuffer data = createScmpResponse(12345, Scmp.TypeCode.TYPE_5, false);
    InetSocketAddress addr = ScionHeaderParser.extractDestinationSocketAddress(data);
    assertNotNull(addr);
    assertEquals(44444, addr.getPort());
  }

  @Test
  void extractDstPort_SCMP_5_truncated() throws UnknownHostException {
    ByteBuffer data = createScmpResponse(12345, Scmp.TypeCode.TYPE_5, true);
    assertNull(ScionHeaderParser.extractDestinationSocketAddress(data));
  }

  private ByteBuffer createScmpResponse(int dstPort, Scmp.TypeCode type, boolean truncatePayload) {
    ScionPacketInspector spi = ScionPacketInspector.createEmpty();
    ScionHeader scionHeader = spi.getScionHeader();
    scionHeader.setSrcHostAddress(new byte[] {127, 0, 0, 1});
    scionHeader.setDstHostAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
    scionHeader.setSrcIA(ScionUtil.parseIA("1-ff00:0:110"));
    scionHeader.setDstIA(ScionUtil.parseIA("1-ff00:0:112"));

    byte[] path = ExamplePacket.PATH_RAW_TINY_110_112;
    PathHeaderScion pathHeader = spi.getPathHeaderScion();
    pathHeader.read(ByteBuffer.wrap(path)); // Initialize path

    ScmpHeader scmpHeader = spi.getScmpHeader();
    scmpHeader.setCode(type);
    if (type == Scmp.TypeCode.TYPE_131 || type == Scmp.TypeCode.TYPE_129) {
      scmpHeader.setIdentifier(dstPort);
    } else if (type == Scmp.TypeCode.TYPE_5) {
      byte[] payload = ExamplePacket.PACKET_BYTES_SERVER_E2E_PING;
      if (truncatePayload) {
        payload = Arrays.copyOf(payload, payload.length - 1);
      }
      spi.setPayLoad(payload);
    } else {
      throw new UnsupportedOperationException();
    }

    ByteBuffer data = ByteBuffer.allocate(1000);
    spi.writePacketSCMP(data);
    data.flip();
    return data;
  }
}
