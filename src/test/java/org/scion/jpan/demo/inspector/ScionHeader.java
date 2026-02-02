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

package org.scion.jpan.demo.inspector;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.testutil.TestUtil;

/** Class for reading, writing and storing the Common Header and Address Header. */
public class ScionHeader {

  // ****************************  Common Header  **********************************
  //  4 bit: Version : Currently, only 0 is supported.
  private int version;
  //  8 bit: TrafficClass / QoS
  private int trafficClass;
  // 20 bit: FlowID
  private int flowId;
  //  8 bit: NextHdr
  private int nextHeader;
  //  8 bit: HdrLen :  Common header + address header + path header. bytes = hdrLen * 4;
  private int hdrLen;
  private int hdrLenBytes;
  // 16 bit: PayloadLen
  private int payLoadLen;
  //  8 bit: PathType  :  Empty (0), SCION (1), OneHopPath (2), EPIC (3) and COLIBRI (4)
  private int pathType;
  //  2 bit: DT
  private int dt;
  //  2 bit: DL : 4 bytes, 8 bytes, 12 bytes and 16 bytes
  private int dl;
  //  2 bit: ST
  private int st;
  //  2 bit: SL : 4 bytes, 8 bytes, 12 bytes and 16 bytes
  private int sl;
  //  8 bit: reserved
  private int reserved;

  // ****************************  Address Header  **********************************
  // 16 bit: DstISD
  // 48 bit: DstAS
  private long dstIsdAs;
  // 16 bit: SrcISD
  // 48 bit: SrcAS
  private long srcIsdAs;
  //  ? bit: DstHostAddr
  private byte[] dstHost;
  //  ? bit: SrcHostAddr
  private byte[] srcHost;

  public void read(ByteBuffer data) {
    //  4 bit: Version
    //  8 bit: TrafficClass
    // 20 bit: FlowID
    //  8 bit: NextHdr
    //  8 bit: HdrLen
    // 16 bit: PayloadLen
    //  8 bit: PathType
    //  2 bit: DT
    //  2 bit: DL
    //  2 bit: ST
    //  2 bit: SL
    //  8 bit: reserved
    int i0 = data.getInt();
    int i1 = data.getInt();
    int i2 = data.getInt();
    version = ByteUtil.readInt(i0, 0, 4);
    trafficClass = ByteUtil.readInt(i0, 4, 8);
    flowId = ByteUtil.readInt(i0, 12, 20);
    nextHeader = ByteUtil.readInt(i1, 0, 8);
    hdrLen = ByteUtil.readInt(i1, 8, 8);
    hdrLenBytes = hdrLen * 4;
    payLoadLen = ByteUtil.readInt(i1, 16, 16);
    pathType = ByteUtil.readInt(i2, 0, 8);
    dt = ByteUtil.readInt(i2, 8, 2);
    dl = ByteUtil.readInt(i2, 10, 2);
    st = ByteUtil.readInt(i2, 12, 2);
    sl = ByteUtil.readInt(i2, 14, 2);
    reserved = ByteUtil.readInt(i2, 16, 16);

    // Address header
    // 16 bit: DstISD
    // 48 bit: DstAS
    // 16 bit: SrcISD
    // 48 bit: SrcAS
    //  ? bit: DstHostAddr
    //  ? bit: SrcHostAddr

    //        long l0 = readLong(data, offset);
    dstIsdAs = data.getLong();
    //        long l1 = readLong(data, offset);
    srcIsdAs = data.getLong();
    //        dstISD = (int) readLong(l0, 0, 16);
    //        dstAS = readLong(l0, 16, 48);
    //        srcISD = (int) readLong(l1, 0, 16);
    //        srcAS = readLong(l1, 16, 48);

    dstHost = new byte[(dl + 1) * 4];
    data.get(dstHost);

    srcHost = new byte[(sl + 1) * 4];
    data.get(srcHost);
  }

  public void reverse() {
    int dummy = dt;
    dt = st;
    st = dummy;
    dummy = dl;
    dl = sl;
    sl = dummy;

    // Address header
    long dummyLong = srcIsdAs;
    srcIsdAs = dstIsdAs;
    dstIsdAs = dummyLong;

    byte[] ba = dstHost;
    dstHost = srcHost;
    srcHost = ba;
  }

  public void write(
      ByteBuffer data, int userPacketLength, int pathHeaderLength, Constants.PathTypes pathType) {
    int hdrTypeId = HeaderConstants.HdrTypes.UDP.code();
    write(data, userPacketLength + 8, pathHeaderLength, pathType, hdrTypeId);
  }

  public void adjustPayloadLength(ByteBuffer data, int payloadLength) {
    payLoadLen = payloadLength;
    data.putShort(6, (short) payloadLength);
  }

  public void write(
      ByteBuffer data,
      int userPacketLength,
      int pathHeaderLength,
      Constants.PathTypes pathType,
      int hdrTypeId) {
    this.pathType = pathType.code();
    int i0 = 0;
    int i1 = 0;
    int i2 = 0;
    i0 = ByteUtil.writeInt(i0, 0, 4, 0); // version = 0
    i0 = ByteUtil.writeInt(i0, 4, 8, 0); // TrafficClass = 0
    i0 = ByteUtil.writeInt(i0, 12, 20, 1); // FlowID = 1
    data.putInt(i0);
    i1 = ByteUtil.writeInt(i1, 0, 8, hdrTypeId); // NextHdr = 17
    int newHdrLen = (calcLen(pathHeaderLength) - 1) / 4 + 1;
    i1 = ByteUtil.writeInt(i1, 8, 8, newHdrLen); // HdrLen = bytes/4
    i1 = ByteUtil.writeInt(i1, 16, 16, userPacketLength); // PayloadLen
    data.putInt(i1);
    i2 = ByteUtil.writeInt(i2, 0, 8, pathHeaderLength > 0 ? 1 : 0); // PathType : SCION = 1
    i2 = ByteUtil.writeInt(i2, 8, 2, 0); // DT
    i2 = ByteUtil.writeInt(i2, 10, 2, dl); // DL
    i2 = ByteUtil.writeInt(i2, 12, 2, 0); // ST
    i2 = ByteUtil.writeInt(i2, 14, 2, sl); // SL
    i2 = ByteUtil.writeInt(i2, 16, 16, 0x0); // RSV
    data.putInt(i2);

    // Address header
    data.putLong(dstIsdAs);
    data.putLong(srcIsdAs);

    // HostAddr
    data.put(dstHost);
    data.put(srcHost);
  }

  private int calcLen(int pathHeaderLength) {
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

  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Common Header: " + "  VER=")
        .append(version)
        .append("  TrafficClass=")
        .append(trafficClass)
        .append("  FlowID=")
        .append(flowId)
        // .append("\n")
        .append("  NextHdr=")
        .append(nextHeader)
        .append("  HdrLen=")
        .append(hdrLen)
        .append("/")
        .append(hdrLenBytes)
        .append("  PayloadLen=")
        .append(payLoadLen)
        // .append("\n")
        .append("  PathType=")
        .append(pathType)
        .append("  DT=")
        .append(dt)
        .append("  DL=")
        .append(dl)
        .append("  ST=")
        .append(st)
        .append("  SL=")
        .append(sl)
        .append("  RSV=")
        .append(reserved);
    sb.append("\n");

    sb.append("Address Header: ");
    sb.append("  dstIsdAs=").append(ScionUtil.toStringIA(dstIsdAs));
    sb.append("  srcIsdAs=").append(ScionUtil.toStringIA(srcIsdAs));
    sb.append("  dstHost=").append(dt).append("/");
    if (dl == 0 || dl == 3) {
      sb.append(TestUtil.toStringIP(dstHost));
    } else {
      sb.append("Format not recognized: ").append(TestUtil.toStringByte(dstHost));
    }
    sb.append("  srcHost=").append(st).append("/");
    if (sl == 0 || sl == 3) {
      sb.append(TestUtil.toStringIP(srcHost));
    } else {
      sb.append("Format not recognized: ").append(TestUtil.toStringByte(srcHost));
    }
    return sb.toString();
  }

  public int hdrLenBytes() {
    return hdrLenBytes;
  }

  public Constants.PathTypes pathType() {
    return Constants.PathTypes.parse(pathType);
  }

  public int getDT() {
    return dt;
  }

  public void setSrcIA(long srcIsdAs) {
    this.srcIsdAs = srcIsdAs;
  }

  public void setDstIA(long dstIsdAs) {
    this.dstIsdAs = dstIsdAs;
  }

  public String getSrcHostString() {
    if (sl == 0 && (st == 0 || st == 1)) {
      return TestUtil.toStringIP(srcHost);
    } else if (sl == 3 && st == 0) {
      return TestUtil.toStringIP(srcHost);
    } else {
      throw new UnsupportedOperationException("Src address not supported: ST/SL=" + st + "/" + sl);
    }
  }

  public InetAddress getSrcHostAddress() throws IOException {
    return InetAddress.getByAddress(srcHost);
  }

  public void setSrcHostAddress(byte[] address) {
    if (address.length == 4) {
      st = 0;
      sl = 0;
    } else if (address.length == 16) {
      st = 0;
      sl = 3;
    } else {
      throw new UnsupportedOperationException(
          "Dst address class not supported: " + address.getClass().getName());
    }
    srcHost = address.clone();
  }

  public InetAddress getDstHostAddress() throws IOException {
    return InetAddress.getByAddress(dstHost);
  }

  public void setDstHostAddress(byte[] address) {
    if (address.length == 4) {
      dt = 0;
      dl = 0;
    } else if (address.length == 16) {
      dt = 0;
      dl = 3;
    } else {
      throw new UnsupportedOperationException(
          "Dst address class not supported: length=" + address.length);
    }
    dstHost = address.clone();
  }

  public int getPayloadLength() {
    return payLoadLen;
  }

  public Constants.HdrTypes nextHeader() {
    return Constants.HdrTypes.parse(nextHeader);
  }

  public int nextHeaderId() {
    return nextHeader;
  }

  public int getTrafficClass() {
    return trafficClass;
  }
}
