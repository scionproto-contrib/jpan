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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.PathHeaderScion;
import org.scion.jpan.demo.inspector.ScionHeader;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.demo.inspector.ScmpHeader;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.internal.header.ScionHeaderParser;
import org.scion.jpan.internal.header.ScmpParser;
import org.scion.jpan.internal.util.ByteUtil;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MalformedPackets;

class ScionHeaderParserTest {

  // Original incoming packet
  private static final byte[] packetBytes = ExamplePacket.PACKET_BYTES_SERVER_E2E_PING;

  // reversed packet
  private static final byte[] reversedBytes = ExamplePacket.PACKET_BYTES_SERVER_E2E_PONG;

  @AfterAll
  static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  /** Parse a packet and create a response packet with reversed path. */
  @Test
  void testParseAndReply() {
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

  // 300 bytes SCMP error 5 with UDP payload
  private static final byte[] baError5 = {
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

  private static final byte[] PAYLOAD_UDP = ExamplePacket.PACKET_BYTES_SERVER_E2E_PING;
  // We just use the SCMP error message as payload for another SCMP error
  private static final byte[] PAYLOAD_SCMP_ERROR = baError5;
  private static final byte[] PAYLOAD_BAD = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16};

  @Test
  void extractDestination_SCMP_error_5() {
    // Example with actual error from the PRODUCTION network
    ByteBuffer bb = ByteBuffer.wrap(baError5);
    InetSocketAddress addr = ScionHeaderParser.extractDestinationSocketAddress(bb);
    assertNotNull(addr);
    assertEquals(31000, addr.getPort());
  }

  @Test
  void extractDstPort_SCMP_129() {
    ByteBuffer data = createScmpResponse(12348, Scmp.TypeCode.TYPE_129);
    InetSocketAddress addr = ScionHeaderParser.extractDestinationSocketAddress(data);
    assertNotNull(addr);
    assertEquals(12348, addr.getPort());
  }

  @Test
  void extractDstPort_SCMP_131() {
    ByteBuffer data = createScmpResponse(12344, Scmp.TypeCode.TYPE_131);
    InetSocketAddress addr = ScionHeaderParser.extractDestinationSocketAddress(data);
    assertNotNull(addr);
    assertEquals(12344, addr.getPort());
  }

  @Test
  void extractDstPort_SCMP_5_payload_UDP() {
    ByteBuffer data = createScmpResponse(12345, Scmp.TypeCode.TYPE_5, PAYLOAD_UDP, false);
    InetSocketAddress addr = ScionHeaderParser.extractDestinationSocketAddress(data);
    assertNotNull(addr);
    assertEquals(44444, addr.getPort());
  }

  @Test
  void extractDstPort_SCMP_5_payload_UDP_truncated() {
    ByteBuffer data = createScmpResponse(12345, Scmp.TypeCode.TYPE_5, PAYLOAD_UDP, true);
    assertNull(ScionHeaderParser.extractDestinationSocketAddress(data));
  }

  @Test
  void extractDstPort_SCMP_5_payload_SCMP() {
    ByteBuffer data = createScmpResponse(12345, Scmp.TypeCode.TYPE_5, PAYLOAD_SCMP_ERROR, false);
    InetSocketAddress addr = ScionHeaderParser.extractDestinationSocketAddress(data);
    assertNotNull(addr);
    assertEquals(0, addr.getPort());
  }

  @Test
  void extractDstPort_SCMP_5_payload_SCMP_truncated() {
    ByteBuffer data = createScmpResponse(12345, Scmp.TypeCode.TYPE_5, PAYLOAD_SCMP_ERROR, true);
    assertNull(ScionHeaderParser.extractDestinationSocketAddress(data));
  }

  @Test
  void extractDstPort_SCMP_5_payload_BAD() {
    ByteBuffer data = createScmpResponse(12345, Scmp.TypeCode.TYPE_5, PAYLOAD_BAD, false);
    assertNull(ScionHeaderParser.extractDestinationSocketAddress(data));
  }

  @Test
  void extractTypeCode() {
    ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_4_CODE_51);
    assertEquals(4, ScmpParser.extractTypeCode(data).type().id());
    assertEquals(51, ScmpParser.extractTypeCode(data).code());
  }

  @Test
  void validate_goodPackets() {
    assertNull(ScionHeaderParser.validate(ByteBuffer.wrap(packetBytes)));
    assertNull(ScionHeaderParser.validate(ByteBuffer.wrap(MalformedPackets.goodUdpPacket())));
  }

  @Test
  void validate_version() {
    byte[] packet = packetBytes.clone();
    packet[0] = (byte) 0x10; // version = 1
    assertValidationFails(packet, "version: expected 0, got 1");
  }

  @Test
  void validate_nextHeader() {
    byte[] packet = packetBytes.clone();
    packet[4] = 6; // TCP
    assertValidationFails(packet, "nextHeader: expected {17, 200, 201, 202}, got 6");
  }

  @Test
  void validate_extensionHeader_HopByHop() {
    byte[] packet = packetBytes.clone();
    packet[4] = (byte) HeaderConstants.HdrTypes.HOP_BY_HOP.code();
    assertValidationFails(packet, "Unsupported header type: 200");
  }

  @Test
  void validate_extensionHeader_EndToEnd() {
    byte[] packet = packetBytes.clone();
    packet[4] = (byte) HeaderConstants.HdrTypes.END_TO_END.code();
    assertValidationFails(packet, "Unsupported header type: 201");
  }

  @Test
  void validate_pathType() {
    byte[] packet = packetBytes.clone();
    packet[8] = 2; // path type 2 = OneHopPath (not supported)
    assertValidationFails(packet, "Invalid path type: expected 0 or 1, got 2");
  }

  @Test
  void validate_dstAddressType() {
    byte[] packet = packetBytes.clone();
    packet[9] = (byte) ((packet[9] & 0x3F) | 0x40); // DT = 1
    assertValidationFails(packet, "Invalid destination address type");
  }

  @Test
  void validate_srcAddressType() {
    byte[] packet = packetBytes.clone();
    packet[9] = (byte) ((packet[9] & 0xF3) | 0x04); // ST = 1
    assertValidationFails(packet, "Invalid source address type");
  }

  @Test
  void validate_reservedField() {
    byte[] packet = packetBytes.clone();
    packet[11] = 1;
    assertValidationFails(packet, "Invalid reserved field");
  }

  @Test
  void validate_emptyPathWithPathTypeScion() {
    byte[] packet = MalformedPackets.goodUdpPacket();
    assertEquals(0, packet[8]); // path type EMPTY
    packet[8] = 1; // path type SCION
    assertValidationFails(packet, "Path is empty but path type is: 1");
  }

  @Test
  void validate_nonEmptyPathWithPathTypeEmpty() {
    byte[] packet = packetBytes.clone();
    assertEquals(1, packet[8]); // path type SCION
    packet[8] = 0; // path type EMPTY
    assertValidationFails(packet, "Path is not empty but path type is: 0");
  }

  @Test
  void validate_pathWithThreeSegments() {
    // 3 info fields + 1+2+3 hop fields
    int validLength = 3 * 8 + 6 * 12;
    byte[] valid =
        MalformedPackets.udpPacketWithPath(MalformedPackets.pathMeta(1, 2, 3, validLength));
    assertNull(ScionHeaderParser.validate(ByteBuffer.wrap(valid)));

    byte[] tooShort = MalformedPackets.pathMeta(1, 2, 3, validLength - 12);
    assertValidationFails(
        MalformedPackets.udpPacketWithPath(tooShort), "Invalid path length: expected 100, got 88");
  }

  @Test
  void validate_udpSrcPortZero() {
    byte[] packet = packetBytes.clone();
    int udpPos = ScionHeaderParser.extractHeaderLength(ByteBuffer.wrap(packet));
    packet[udpPos] = 0;
    packet[udpPos + 1] = 0;
    assertValidationFails(packet, "Invalid source port: 0");
  }

  @Test
  void validate_udpDstPortZero() {
    byte[] packet = packetBytes.clone();
    int udpPos = ScionHeaderParser.extractHeaderLength(ByteBuffer.wrap(packet));
    packet[udpPos + 2] = 0;
    packet[udpPos + 3] = 0;
    assertValidationFails(packet, "Invalid destination port: 0");
  }

  private static void assertValidationFails(byte[] packet, String expectedMessage) {
    ByteBuffer buffer = ByteBuffer.wrap(packet);
    String result = ScionHeaderParser.validate(buffer);
    assertNotNull(result);
    assertTrue(result.contains(expectedMessage), result);
    assertEquals(0, buffer.position());
  }

  @Test
  void validateScmpInfo() {
    {
      // Echo: minimum 8 bytes
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_128);
      adjustPacketLength(data, data.limit() - 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Echo: exact 1232 bytes
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_128);
      adjustPacketLength(data, 1232);
      assertNull(ScionHeaderParser.validate(data)); // Works!
    }
    {
      // Echo: exceed 1232 bytes
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_128);
      adjustPacketLength(data, 1232 + 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Echo: minimum 8 bytes
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_129);
      adjustPacketLength(data, data.limit() - 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Echo: exact 1232 bytes
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_129);
      adjustPacketLength(data, 1232);
      assertNull(ScionHeaderParser.validate(data)); // Works!
    }
    {
      // Echo: exceed 1232 bytes
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_129);
      adjustPacketLength(data, 1232 + 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Traceroute: no payload!
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_130);
      adjustPacketLength(data, data.limit() + 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Traceroute: no payload!
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_131);
      adjustPacketLength(data, data.limit() + 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Custom SCMP INFO: 200
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_200);
      assertNull(ScionHeaderParser.validate(data)); // Works!
    }
  }

  @Test
  void validateScmpError() {
    {
      // Error 1: max length
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_1_CODE_0);
      adjustPacketLength(data, 1232);
      assertNull(ScionHeaderParser.validate(data));
    }
    {
      // Error 1: max length + 1
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_1_CODE_0);
      adjustPacketLength(data, 1232 + 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Error 1: too short
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_1_CODE_0);
      adjustPacketLength(data, data.limit() - 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Error 2: too short
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_2);
      adjustPacketLength(data, data.limit() - 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Error 4: too short
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_4_CODE_0);
      adjustPacketLength(data, data.limit() - 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Error 5: too short
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_5);
      adjustPacketLength(data, data.limit() - 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Error 6: too short
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_6);
      adjustPacketLength(data, data.limit() - 1);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Error: invalid SCMP type, e.g. 42
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_131);
      // Just double-check that we have the correct location
      assertEquals(131, ByteUtil.toUnsigned(data.get(data.limit() - 24)));
      data.put(data.limit() - 24, (byte) 42);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Error: invalid error code, e.g. 1:11
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_131);
      // Just double-check that we have the correct location
      assertEquals(131, ByteUtil.toUnsigned(data.get(data.limit() - 24)));
      data.put(data.limit() - 24, (byte) 1);
      data.put(data.limit() - 23, (byte) 11);
      assertNotNull(ScionHeaderParser.validate(data));
    }
    {
      // Custom Error: 100
      ByteBuffer data = createScmpResponse(Scmp.TypeCode.TYPE_100);
      // Just double-check that we have the correct location
      assertNull(ScionHeaderParser.validate(data)); // Works!
    }
  }

  private void adjustPacketLength(ByteBuffer data, int newLength) {
    int hdrLen = ByteUtil.toUnsigned(data.get(5)) * 4;
    // Update payload length
    data.putShort(6, ByteUtil.toShort(newLength - hdrLen));
    data.limit(newLength);
  }

  private ByteBuffer createScmpResponse(Scmp.TypeCode type) {
    return createScmpResponse(12345, type, new byte[0], false);
  }

  private ByteBuffer createScmpResponse(int dstPort, Scmp.TypeCode type) {
    return createScmpResponse(dstPort, type, new byte[0], false);
  }

  private ByteBuffer createScmpResponse(
      int dstPort, Scmp.TypeCode type, byte[] payload, boolean truncatePayload) {
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
    switch (type) {
      case TYPE_131:
      case TYPE_130:
      case TYPE_129:
      case TYPE_128:
        scmpHeader.setIdentifier(dstPort);
        break;
      case TYPE_5:
        if (truncatePayload) {
          scmpHeader.setErrorPayload(Arrays.copyOf(payload, payload.length - 1));
        } else {
          scmpHeader.setErrorPayload(payload);
        }
        break;
      default:
        // Nothing
    }

    ByteBuffer data = ByteBuffer.allocate(2000);
    spi.writePacketSCMP(data);
    data.flip();
    return data;
  }
}
