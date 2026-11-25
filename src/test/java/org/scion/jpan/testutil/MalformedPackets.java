// Copyright 2026 ETH Zurich
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Crafted SCION packets with inconsistent headers. None of these packets may cause an exception
 * when received; they must be dropped (or reported via ScionException if configured).
 */
public class MalformedPackets {

  public static final String GOOD_MSG = "Hello scion";

  private static final int UDP = 17;
  private static final int SCMP = 202;
  private static final int SRC_PORT = 12345;
  private static final int DST_PORT = 12345;

  private MalformedPackets() {}

  /**
   * @return a valid intra-AS UDP packet (empty path) containing {@link #GOOD_MSG}.
   */
  public static byte[] goodUdpPacket() {
    byte[] msg = GOOD_MSG.getBytes(StandardCharsets.UTF_8);
    return build(UDP, 0, 0, new byte[0], udp(msg), -1, -1);
  }

  /**
   * @return named malformed packets.
   */
  public static Map<String, byte[]> all() {
    Map<String, byte[]> map = new LinkedHashMap<>();
    byte[] udp = udp(new byte[0]);

    // IPv6 address types, but the packet is too short to contain the addresses.
    map.put("addressHeaderTruncated", build(UDP, 3, 3, new byte[0], new byte[0], 1, 36));
    // HdrLen = 0, i.e. the header does not even contain the common header.
    map.put("hdrLenZero", build(UDP, 0, 0, new byte[0], udp, 0, -1));
    // HdrLen is too short to contain the address header.
    map.put("hdrLenTooShort", build(UDP, 0, 0, new byte[0], udp, 8, -1));
    // UDP without any L4 header.
    map.put("udpHeaderMissing", build(UDP, 0, 0, new byte[0], new byte[0], -1, -1));
    // UDP with only the port numbers but no length/checksum.
    map.put("udpHeaderTruncated", build(UDP, 0, 0, new byte[0], new byte[] {0, 1, 0, 1}, -1, -1));
    // Path meta header says Seg0Len=5 but the path consists only of the path meta header.
    map.put("pathTruncated", build(UDP, 0, 0, pathMeta(5, 0, 0, 0), udp, -1, -1));
    // Path with Seg0Len = 0.
    map.put("pathSeg0Empty", build(UDP, 0, 0, pathMeta(0, 0, 0, 1 * 8 + 1 * 12), udp, -1, -1));
    // Path with Seg1Len = 0 but Seg2Len > 0.
    map.put("pathSeg1Empty", build(UDP, 0, 0, pathMeta(1, 0, 1, 2 * 8 + 2 * 12), udp, -1, -1));
    // Path is longer than the segment lengths say.
    map.put("pathTooLong", build(UDP, 0, 0, pathMeta(1, 0, 0, 1 * 8 + 2 * 12), udp, -1, -1));
    // SCMP without any SCMP header.
    map.put("scmpHeaderMissing", build(SCMP, 0, 0, new byte[0], new byte[0], -1, -1));
    // SCMP with unassigned type 3.
    map.put("scmpType3", build(SCMP, 0, 0, new byte[0], new byte[] {3, 0, 0, 0}, -1, -1));
    // SCMP echo reply with truncated path.
    byte[] echoReply = {(byte) 129, 0, 0, 0, 0x30, 0x39, 0, 1};
    map.put("scmpPathTruncated", build(SCMP, 0, 0, pathMeta(5, 0, 0, 0), echoReply, -1, -1));
    return map;
  }

  private static byte[] udp(byte[] payload) {
    ByteBuffer bb = ByteBuffer.allocate(8 + payload.length);
    bb.putShort((short) SRC_PORT);
    bb.putShort((short) DST_PORT);
    bb.putShort((short) (8 + payload.length));
    bb.putShort((short) 0); // checksum
    bb.put(payload);
    return bb.array();
  }

  /** Path meta header with the given segment lengths followed by `extraBytes` zero bytes. */
  private static byte[] pathMeta(int seg0Len, int seg1Len, int seg2Len, int extraBytes) {
    ByteBuffer bb = ByteBuffer.allocate(4 + extraBytes);
    bb.putInt((seg0Len << 12) | (seg1Len << 6) | seg2Len);
    return bb.array();
  }

  /**
   * Build a SCION packet.
   *
   * @param nextHdr next header
   * @param dl DL field (0 = IPv4, 3 = IPv6)
   * @param sl SL field (0 = IPv4, 3 = IPv6)
   * @param path raw path (may be empty)
   * @param l4 L4 header + payload
   * @param hdrLenOverride HdrLen field (in units of 4 bytes), or -1 to compute it
   * @param totalLength total packet length (truncates the packet), or -1 for the full packet
   * @return packet bytes
   */
  private static byte[] build(
      int nextHdr, int dl, int sl, byte[] path, byte[] l4, int hdrLenOverride, int totalLength) {
    int hdrBytes = 12 + 16 + (dl + 1) * 4 + (sl + 1) * 4 + path.length;
    int hdrLen = hdrLenOverride >= 0 ? hdrLenOverride : hdrBytes / 4;
    int length = totalLength >= 0 ? totalLength : hdrBytes + l4.length;
    ByteBuffer bb = ByteBuffer.allocate(Math.max(length, hdrBytes + l4.length));
    bb.putInt(0); // version, traffic class, flow ID
    bb.putInt((nextHdr << 24) | (hdrLen << 16) | (length - hdrLen * 4)); // payload length
    bb.putInt(((path.length > 0 ? 1 : 0) << 24) | (dl << 20) | (sl << 16));
    bb.putLong(ExamplePacket.DST_IA);
    bb.putLong(ExamplePacket.SRC_IA);
    byte[] dst = new byte[(dl + 1) * 4];
    dst[0] = 127;
    dst[dst.length - 1] = 1;
    bb.put(dst);
    byte[] src = new byte[(sl + 1) * 4];
    src[0] = 127;
    src[src.length - 1] = 1;
    bb.put(src);
    bb.put(path);
    bb.put(l4);
    byte[] result = new byte[length];
    System.arraycopy(bb.array(), 0, result, 0, length);
    return result;
  }
}
