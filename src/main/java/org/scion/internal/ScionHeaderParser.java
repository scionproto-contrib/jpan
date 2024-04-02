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

package org.scion.internal;

import static org.scion.internal.ByteUtil.*;

import java.net.*;
import java.nio.ByteBuffer;
import org.scion.ResponsePath;

/** Utility methods for reading and writing the Common Header and Address Header. */
public class ScionHeaderParser {

  private ScionHeaderParser() {}

  /**
   * Extract the user payload data without changing the buffer's position.
   *
   * @param data The datagram to read from.
   * @param userBuffer Buffer in which to write the user payload.
   */
  public static void extractUserPayload(ByteBuffer data, ByteBuffer userBuffer) {
    int i1 = data.getInt(4);
    int hdrLen = readInt(i1, 8, 8);
    int hdrLenBytes = hdrLen * 4;

    int udpHeaderLength = 8;
    int payLoadStart = hdrLenBytes + udpHeaderLength;
    int pos = data.position();
    data.position(payLoadStart);
    int maxUserLen = userBuffer.remaining();
    if (data.limit() - payLoadStart <= maxUserLen) {
      userBuffer.put(data);
    } else {
      int oldLimit = data.limit();
      data.limit(payLoadStart + maxUserLen);
      userBuffer.put(data);
      data.limit(oldLimit);
    }
    data.position(pos);
  }

  /**
   * Extract the remote socket address and path without changing the buffer's position.
   *
   * @param data The datagram to read from.
   * @return A new ScionSocketAddress including raw path.
   */
  public static ResponsePath extractResponsePath(
      ByteBuffer data, InetSocketAddress firstHopAddress) {
    int pos = data.position();

    int i1 = data.getInt(4);
    int i2 = data.getInt(8);
    int hdrLen = readInt(i1, 8, 8);
    int hdrLenBytes = hdrLen * 4;
    int dl = readInt(i2, 10, 2);
    int sl = readInt(i2, 14, 2);

    // Address header
    //  8 bit: DstISD
    // 48 bit: DstAS
    //  8 bit: SrcISD
    // 48 bit: SrcAS
    //  ? bit: DstHostAddr
    //  ? bit: SrcHostAddr

    long dstIsdAs = data.getLong(12);
    long srcIsdAs = data.getLong(20);

    // dstAddress
    data.position(28);
    byte[] bytesDst = new byte[(dl + 1) * 4];
    data.get(bytesDst);

    // remote address
    byte[] bytesSrc = new byte[(sl + 1) * 4];
    data.get(bytesSrc);

    // raw path
    byte[] path = new byte[hdrLenBytes - data.position()];
    if (path.length > 0) {
      // raw path may be empty for local AS
      data.get(path);
      reversePathInPlace(ByteBuffer.wrap(path));
    }

    // get remote port from UDP overlay
    data.position(hdrLenBytes);
    int srcPort = Short.toUnsignedInt(data.getShort());
    int dstPort = Short.toUnsignedInt(data.getShort());

    // rewind to original offset
    data.position(pos);
    // Swap src and dst.
    return ResponsePath.create(
        path, dstIsdAs, bytesDst, dstPort, srcIsdAs, bytesSrc, srcPort, firstHopAddress);
  }

  /**
   * Extract the next header type without changing the buffer's position.
   *
   * @param data The datagram to read from.
   * @return The type of the next header.
   */
  public static InternalConstants.HdrTypes extractNextHeader(ByteBuffer data) {
    int nextHeader = ByteUtil.toUnsigned(data.get(4));
    return InternalConstants.HdrTypes.parse(nextHeader);
  }

  public static InetSocketAddress extractDestinationSocketAddress(ByteBuffer data)
      throws UnknownHostException {
    int start = data.position();

    int i1 = data.getInt(start + 4); // nextHeader, hdrLen, payLoadLen
    int i2 = data.getInt(start + 8); // pathType, dt, dl, st, sl
    int hdrLen = readInt(i1, 8, 8);
    int hdrLenBytes = hdrLen * 4;
    int dl = readInt(i2, 10, 2);

    // Address header
    //  8 bit: DstISD
    // 48 bit: DstAS
    //  8 bit: SrcISD
    // 48 bit: SrcAS
    //  ? bit: DstHostAddr
    //  ? bit: SrcHostAddr

    data.position(start + 28);
    byte[] bytesDst = new byte[(dl + 1) * 4];
    data.get(bytesDst);
    InetAddress dstIP = InetAddress.getByAddress(bytesDst);

    // get remote port from UDP overlay
    data.position(start + hdrLenBytes + 2);
    int dstPort = Short.toUnsignedInt(data.getShort());

    // rewind to original offset
    data.position(start);
    return new InetSocketAddress(dstIP, dstPort);
  }

  /**
   * Extract the header length without changing the buffer's position.
   *
   * @param data The packet buffer
   * @return the length of the SCION common + address + path header in bytes
   */
  public static int extractHeaderLength(ByteBuffer data) {
    int hdrLen = ByteUtil.toUnsigned(data.get(5));
    return hdrLen * 4;
  }

  /**
   * Extract the position of the path header.
   *
   * @param data The packet buffer
   * @return the position of the path header or -1 if no path is available
   */
  public static int extractPathHeaderPosition(ByteBuffer data) {
    int nextHeader = ByteUtil.toUnsigned(data.get(4));
    if (nextHeader != InternalConstants.HdrTypes.UDP.code()
        && nextHeader != InternalConstants.HdrTypes.SCMP.code()) {
      throw new UnsupportedOperationException("This method UDP ans SCMP headers");
    }

    int pathType = ByteUtil.toUnsigned(data.get(8));
    if (pathType == InternalConstants.PathTypes.Empty.code()) {
      return -1;
    }

    int i2 = data.getInt(8); // pathType, dt, dl, st, sl
    int dl = readInt(i2, 10, 2);
    int sl = readInt(i2, 14, 2);
    int dstLen = (dl + 1) * 4;
    int srcLen = (sl + 1) * 4;

    return 28 + dstLen + srcLen;
  }

  public static String validate(ByteBuffer data) {
    // TODO this approach to error handling is not ideal.
    //   Flooding a receiver with bad packets may cause unnecessary CPU and
    //   garbage collection cost due to creation of error messages.
    //   --> return isDropBadPackets ? "" : report(String ... args);

    final String PRE = "SCION packet validation failed: ";
    int start = data.position();
    if (data.limit() - start < 12 + 16 + 8) {
      return PRE + "Invalid packet length: packet too short: " + (data.limit() - start);
    }

    int i0 = data.getInt();
    int i1 = data.getInt();
    int i2 = data.getInt();
    int version = readInt(i0, 0, 4);
    if (version != 0) {
      return PRE + "version: expected 0, got " + version;
    }
    // int trafficLClass = readInt(i0, 4, 8);
    // int flowId = readInt(i0, 12, 20);
    int nextHeader = readInt(i1, 0, 8);
    if (nextHeader != InternalConstants.HdrTypes.UDP.code()
        && nextHeader != InternalConstants.HdrTypes.HOP_BY_HOP.code()
        && nextHeader != InternalConstants.HdrTypes.END_TO_END.code()
        && nextHeader != InternalConstants.HdrTypes.SCMP.code()) {
      return PRE + "nextHeader: expected {17, 200, 201, 202}, got " + nextHeader;
    }
    int hdrLen = readInt(i1, 8, 8);
    int hdrLenBytes = hdrLen * 4;
    int payLoadLen = readInt(i1, 16, 16);
    if (hdrLenBytes + payLoadLen != data.limit() - start) {
      return PRE
          + "Invalid packet length: length = "
          + (data.limit() - start)
          + ", header says "
          + (hdrLenBytes + payLoadLen);
    }
    int pathType = readInt(i2, 0, 8);
    if (pathType != 1 && pathType != 0) {
      // Validation against path length happens further down.
      return PRE + "Invalid path type: expected 0 or 1, got " + pathType;
    }
    int dt = readInt(i2, 8, 2);
    int dl = readInt(i2, 10, 2);
    int st = readInt(i2, 12, 2);
    int sl = readInt(i2, 14, 2);
    int dtdl = dt << 2 | dl;
    int stsl = st << 2 | sl;
    if (dtdl != 0b0000 && dtdl != 0b0011) { // Allow also IPv4SVC=0b0100 ?
      return PRE
          + "Invalid destination address type: expected 0b000 or 0b111, got "
          + Integer.toBinaryString(dtdl);
    }
    if (stsl != 0b0000 && stsl != 0b0011) { // Allow also IPv4SVC=0b0100 ?
      return PRE
          + "Invalid source address type: expected 0b000 or 0b111, got "
          + Integer.toBinaryString(dtdl);
    }
    int reserved = readInt(i2, 16, 16);
    if (reserved != 0) {
      return PRE
          + "Invalid reserved field: expected '0b0000_0000_0000_0000', got "
          + Integer.toBinaryString(reserved);
    }

    // Address header
    //  8 bit: DstISD
    // 48 bit: DstAS
    //  8 bit: SrcISD
    // 48 bit: SrcAS
    //  ? bit: DstHostAddr
    //  ? bit: SrcHostAddr

    long dstIsdAs = data.getLong(); // TODO compare to local IsdAs?
    long srcIsdAs = data.getLong();

    byte[] bytesDst = new byte[(dl + 1) * 4];
    data.get(bytesDst);

    byte[] bytesSrc = new byte[(sl + 1) * 4];
    data.get(bytesSrc);

    try {
      InetAddress.getByAddress(bytesDst);
    } catch (UnknownHostException e) {
      return PRE + "Error decoding destination IP address: " + e.getMessage();
    }

    try {
      InetAddress.getByAddress(bytesSrc);
    } catch (UnknownHostException e) {
      return PRE + "Error decoding source IP address: " + e.getMessage();
    }

    // raw path
    byte[] path = new byte[start + hdrLenBytes - data.position()];
    data.get(path);
    if (path.length == 0 && pathType != 0) {
      return PRE + "Path is empty but path type is: " + pathType;
    }
    if (path.length > 0 && pathType != 1) {
      return PRE + "Path is not empty but path type is: " + pathType;
    }
    // TODO validate path

    if (nextHeader == InternalConstants.HdrTypes.UDP.code()) {
      // get remote port from UDP overlay
      data.position(start + hdrLenBytes);
      int srcPort = Short.toUnsignedInt(data.getShort());
      int dstPort = Short.toUnsignedInt(data.getShort());
      if (srcPort == 0) {
        return PRE + "Invalid source port: " + srcPort;
      }
      if (dstPort == 0) {
        return PRE + "Invalid destination port: " + dstPort; // can this happen?
      }
    } else {
      // TODO validate SCMP etc
    }

    // rewind to original offset
    data.position(start);
    return null;
  }

  public static void write(
      ByteBuffer data,
      int userPacketLength,
      int pathHeaderLength,
      long srcIsdAs,
      byte[] srcAddress,
      long dstIsdAs,
      byte[] dstAddress,
      InternalConstants.HdrTypes hdrType,
      int trafficClass) {
    int sl = srcAddress.length / 4 - 1;
    int dl = dstAddress.length / 4 - 1;

    int i0 = 0;
    int i1 = 0;
    int i2 = 0;
    i0 = writeInt(i0, 0, 4, 0); // version = 0
    i0 = writeInt(i0, 4, 8, trafficClass); // TrafficClass = 0
    i0 = writeInt(i0, 12, 20, 1); // FlowID = 1
    data.putInt(i0);
    i1 = writeInt(i1, 0, 8, hdrType.code); // NextHdr = 17 is for UDP OverlayHeader
    int newHdrLen = (calcLen(pathHeaderLength, sl, dl) - 1) / 4 + 1;
    i1 = writeInt(i1, 8, 8, newHdrLen); // HdrLen = bytes/4
    i1 = writeInt(i1, 16, 16, userPacketLength); // PayloadLen (+ overlay!)
    data.putInt(i1);
    i2 = writeInt(i2, 0, 8, pathHeaderLength > 0 ? 1 : 0); // PathType : SCION = 1
    i2 = writeInt(i2, 8, 2, 0); // DT
    i2 = writeInt(i2, 10, 2, dl); // DL
    i2 = writeInt(i2, 12, 2, 0); // ST
    i2 = writeInt(i2, 14, 2, sl); // SL
    i2 = writeInt(i2, 16, 16, 0x0); // RSV
    data.putInt(i2);

    // Address header
    data.putLong(dstIsdAs);
    data.putLong(srcIsdAs);

    // HostAddr
    data.put(dstAddress);
    data.put(srcAddress);
  }

  private static int calcLen(int pathHeaderLength, int sl, int dl) {
    // Common header
    int len = 12;

    // Address header
    len += 16;
    len += (dl + 1) * 4;
    len += (sl + 1) * 4;

    // Path header
    len += pathHeaderLength;
    return len;
  }

  public static void reversePathInPlace(ByteBuffer data) {
    int pos = data.position();
    int i0 = data.getInt();
    int seg0Len = readInt(i0, 14, 6);
    int seg1Len = readInt(i0, 20, 6);
    int seg2Len = readInt(i0, 26, 6);

    // set CurrINF=0; CurrHF=0; RSV=0
    int i0R = 0;
    i0R = writeInt(i0R, 0, 2, 0); // CurrINF = 0
    i0R = writeInt(i0R, 2, 6, 0); // CurrHF = 0
    i0R = writeInt(i0R, 8, 6, 0); // RSV = 0
    // reverse segLen
    int seg0LenR = seg2Len > 0 ? seg2Len : seg1Len > 0 ? seg1Len : seg0Len;
    int seg1LenR = seg2Len > 0 ? seg1Len : seg1Len > 0 ? seg0Len : seg1Len;
    int seg2LenR = seg2Len > 0 ? seg0Len : seg2Len;
    i0R = writeInt(i0R, 14, 6, seg0LenR);
    i0R = writeInt(i0R, 20, 6, seg1LenR);
    i0R = writeInt(i0R, 26, 6, seg2LenR);
    data.putInt(pos, i0R);

    // info fields
    int posInfo = data.position();
    long info0 = data.getLong();
    long info1 = seg1Len > 0 ? data.getLong() : 0;
    long info2 = seg2Len > 0 ? data.getLong() : 0;
    long info0R = seg2Len > 0 ? info2 : seg1Len > 0 ? info1 : info0;
    long info1R = seg2Len > 0 ? info1 : seg1Len > 0 ? info0 : info1;
    long info2R = seg2Len > 0 ? info0 : info2;

    data.position(posInfo);
    long currDirMask = 1L << 56; // For inverting the CurrDir flag.
    data.putLong(info0R ^ currDirMask);
    if (seg1LenR > 0) {
      data.putLong(info1R ^ currDirMask);
    }
    if (seg2LenR > 0) {
      data.putLong(info2R ^ currDirMask);
    }

    // hop fields
    int posHops = data.position();
    int nHops = seg0Len + seg1Len + seg2Len;
    for (int i = 0, j = nHops - 1; i < j; i++, j--) {
      int posI = posHops + i * 3 * 4; // 3 int per HopField and 4 bytes per int
      int posJ = posHops + j * 3 * 4;
      for (int x = 0; x < 3; x++) {
        int dummy = data.getInt(posI + x * 4);
        data.putInt(posI + x * 4, data.getInt(posJ + x * 4));
        data.putInt(posJ + x * 4, dummy);
      }
    }

    data.position(pos);
  }

  public static void writePath(ByteBuffer data, byte[] path) {
    data.put(path);
  }

  public static void writeUdpOverlayHeader(
      ByteBuffer data, int packetLength, int srcPort, int dstPort) {
    int i0 = 0;
    int i1 = 0;
    i0 = writeInt(i0, 0, 16, srcPort);
    i0 = writeInt(i0, 16, 16, dstPort);
    i1 = writeInt(i1, 0, 16, packetLength + 8);
    int checkSum = 0; // We do not check it.
    i1 = write16(i1, 16, checkSum);
    data.putInt(i0);
    data.putInt(i1);
  }
}
