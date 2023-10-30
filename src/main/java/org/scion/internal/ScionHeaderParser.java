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

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

import org.scion.ScionException;
import org.scion.ScionPath;
import org.scion.ScionSocketAddress;
import org.scion.ScionUtil;

/**
 * Class for reading, writing and storing the Common Header and Address Header.
 */
public class ScionHeaderParser {
    public static boolean REPORT_ERROR = true;
    private static final int BYTES = 3 * 4;

    // ****************************  Common Header  **********************************
    //  4 bit: Version : Currently, only 0 is supported.
    private int version;
    //  8 bit: TrafficClass / QoS
    private int trafficLClass;
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
    //  8 bit: DstISD
    // 48 bit: DstAS
    private long dstIsdAs;
    //  8 bit: SrcISD
    // 48 bit: SrcAS
    private long srcIsdAs;
    //  ? bit: DstHostAddr
    private int dstHost0;
    private int dstHost1;
    private int dstHost2;
    private int dstHost3;
    //  ? bit: SrcHostAddr
    private int srcHost0;
    private int srcHost1;
    private int srcHost2;
    private int srcHost3;

    private int len = 3 * 4;

    public static boolean readUserData(ByteBuffer data, ByteBuffer userBuffer) {
        int pos = data.position();
        int i0 = data.getInt();
        int i1 = data.getInt();
        int i2 = data.getInt();
        int version = readInt(i0, 0, 4);
        if (!verify(version, 0, "Version must be 0")) {
            return false;
        }
        int trafficLClass = readInt(i0, 4, 8);
        int flowId = readInt(i0, 12, 20);
        int nextHeader = readInt(i1, 0, 8);
        if (!verify(nextHeader, 17, "NextHeader must be 17/UDP")) {
            // TODO allow SCMP?
            return false;
        }
        int hdrLen = readInt(i1, 8, 8);
        int hdrLenBytes = hdrLen * 4;
        int payLoadLen = readInt(i1, 16, 16);
        int pathType = readInt(i2, 0, 8);
        if (!verify(pathType, 1, "Version must be 1")) {
            return false;
        }
        //        int dt = readInt(i2, 8, 2);
        //        int dl = readInt(i2, 10, 2);
        //        int st = readInt(i2, 12, 2);
        //        int sl = readInt(i2, 14, 2);
        //        int reserved = readInt(i2, 16, 16);

        int udpHeaderLength = 8;
        // TODO assert UDP payload_length + 8 = payloadLength = limit()-pos-headerLengthBytes-8
        data.position(pos + hdrLenBytes + udpHeaderLength);
        userBuffer.put(data);
        data.position(pos);
        return true;
    }

    private static boolean verify(int i1, int i2, String msg) {
        if (i1 != i2) {
            if (REPORT_ERROR) {
                throw new ScionException(msg);
            }
            return false;
        }
        return true;
    }

    /**
     *
     * @param data The datagram to read from.
     * @return A new ScionSocketAddress including raw path.
     */
    public static ScionSocketAddress readRemoteSocketAddress(ByteBuffer data) {
        int pos = data.position();

        int i0 = data.getInt();
        int i1 = data.getInt();
        int i2 = data.getInt();
        int version = readInt(i0, 0, 4);
        int trafficLClass = readInt(i0, 4, 8);
        int flowId = readInt(i0, 12, 20);
        int nextHeader = readInt(i1, 0, 8);
        int hdrLen = readInt(i1, 8, 8);
        int hdrLenBytes = hdrLen * 4;
        int payLoadLen = readInt(i1, 16, 16);
        int pathType = readInt(i2, 0, 8);
        int dt = readInt(i2, 8, 2);
        int dl = readInt(i2, 10, 2);
        int st = readInt(i2, 12, 2);
        int sl = readInt(i2, 14, 2);
        int reserved = readInt(i2, 16, 16);

        int offset = BYTES;


        // Address header
        //  8 bit: DstISD
        // 48 bit: DstAS
        //  8 bit: SrcISD
        // 48 bit: SrcAS
        //  ? bit: DstHostAddr
        //  ? bit: SrcHostAddr

//        long l0 = readLong(data, offset);
        long dstIsdAs = data.getLong();
        offset += 8;
//        long l1 = readLong(data, offset);
        long srcIsdAs = data.getLong();
        offset += 8;
//        dstISD = (int) readLong(l0, 0, 16);
//        dstAS = readLong(l0, 16, 48);
//        srcISD = (int) readLong(l1, 0, 16);
//        srcAS = readLong(l1, 16, 48);

        int dstHost0 = data.getInt();
        offset += 4;
        if (dl >= 1) {
            int dstHost1 = data.getInt();
            offset += 4;
        }
        if (dl >= 2) {
            int dstHost2 = data.getInt();
            offset += 4;
        }
        if (dl >= 3) {
            int dstHost3 = data.getInt();
            offset += 4;
        }

//        int srcHost0 = data.getInt();
//        int srcHost1 = 0;
//        int srcHost2 = 0;
//        int srcHost3 = 0;
//        offset += 4;
//        if (sl >= 1) {
//            srcHost1 = data.getInt();
//            offset += 4;
//        }
//        if (sl >= 2) {
//            srcHost2 = data.getInt();
//            offset += 4;
//        }
//        if (sl >= 3) {
//            srcHost3 = data.getInt();
//            offset += 4;
//        }


        // remote address
        byte[] bytes = new byte[(sl + 1) * 4];
        data.get(bytes);
        if (sl == 0 && (st == 0 || st == 1)) {
            // IPv4
        } else if (sl == 3 && st == 0) {
            // IPv6
        } else {
            throw new UnsupportedOperationException("Src address not supported: ST/SL=" + st + "/" + sl);
        }
        InetAddress addr = null;
        try {
            addr = InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            if (REPORT_ERROR) {
                throw new ScionException(e);
            }
            return null;
        }

        // raw path
        byte[] path = new byte[pos + hdrLenBytes - data.position()];
        data.get(path);
        // TODO !!!
        System.out.println("TODO: reverse path!");
        //reverse(path);

        // remote port
        data.position(pos + hdrLenBytes);
        int srcPort = data.getShort();

        // rewind to original offset
        data.position(pos);
        return ScionSocketAddress.create(srcIsdAs, addr, srcPort, ScionPath.create(path, dstIsdAs, srcIsdAs));
    }

    public int read(ByteBuffer data, int headerOffset) {
        int offset = headerOffset;

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
        version = readInt(i0, 0, 4);
        trafficLClass = readInt(i0, 4, 8);
        flowId = readInt(i0, 12, 20);
        nextHeader = readInt(i1, 0, 8);
        hdrLen = readInt(i1, 8, 8);
        hdrLenBytes = hdrLen * 4;
        payLoadLen = readInt(i1, 16, 16);
        pathType = readInt(i2, 0, 8);
        dt = readInt(i2, 8, 2);
        dl = readInt(i2, 10, 2);
        st = readInt(i2, 12, 2);
        sl = readInt(i2, 14, 2);
        reserved = readInt(i2, 16, 16);

        offset += BYTES;


        // Address header
        //  8 bit: DstISD
        // 48 bit: DstAS
        //  8 bit: SrcISD
        // 48 bit: SrcAS
        //  ? bit: DstHostAddr
        //  ? bit: SrcHostAddr

//        long l0 = readLong(data, offset);
        dstIsdAs = data.getLong();
        offset += 8;
//        long l1 = readLong(data, offset);
        srcIsdAs = data.getLong();
        offset += 8;
//        dstISD = (int) readLong(l0, 0, 16);
//        dstAS = readLong(l0, 16, 48);
//        srcISD = (int) readLong(l1, 0, 16);
//        srcAS = readLong(l1, 16, 48);

        dstHost0 = data.getInt();
        offset += 4;
        if (dl >= 1) {
            dstHost1 = data.getInt();
            offset += 4;
        }
        if (dl >= 2) {
            dstHost2 = data.getInt();
            offset += 4;
        }
        if (dl >= 3) {
            dstHost3 = data.getInt();
            offset += 4;
        }

        srcHost0 = data.getInt();
        offset += 4;
        if (sl >= 1) {
            srcHost1 = data.getInt();
            offset += 4;
        }
        if (sl >= 2) {
            srcHost2 = data.getInt();
            offset += 4;
        }
        if (sl >= 3) {
            srcHost3 = data.getInt();
            offset += 4;
        }

        return offset;
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

        int d;
        d = srcHost0;
        srcHost0 = dstHost0;
        dstHost0 = d;

        d = srcHost1;
        srcHost1 = dstHost1;
        dstHost1 = d;

        d = srcHost2;
        srcHost2 = dstHost2;
        dstHost2 = d;

        d = srcHost3;
        srcHost3 = dstHost3;
        dstHost3 = d;
    }

    private static void swap(byte[] data, int p1, int p2) {
        byte b = data[p1];
        data[p1] = data[p2];
        data[p2] = b;
    }
    public static void reverse(byte[] data) {
        int offset = 0;
//        int dummy = dt;
//        dt = st;
//        st = dummy;
//        dummy = dl;
//        dl = sl;
//        sl = dummy;
        // byte 9: dt/dl/st/sl
        byte b9 = data[offset + 9];
        int DL = (b9 >>> 4) & 0x3;
        int SL = b9 & 0x3;
        DL = (DL + 1) * 4;
        SL = (SL + 1) * 4;
        int b9_d = b9 >>> 4;
        int b9_s = (b9 & 0x0F) << 4;
        data[offset + 9] = (byte)(b9_s | b9_d);

        int p = offset + 12 + 16;
        for (int i = 0; i < 4; i++) {
            swap(data, p + i, p + i + DL);
        }
// TODO
        throw new UnsupportedOperationException();

        // Address header
//        long dummyLong = srcIsdAs;
//        srcIsdAs = dstIsdAs;
//        dstIsdAs = dummyLong;
//
//        int d;
//        d = srcHost0;
//        srcHost0 = dstHost0;
//        dstHost0 = d;
//
//        d = srcHost1;
//        srcHost1 = dstHost1;
//        dstHost1 = d;
//
//        d = srcHost2;
//        srcHost2 = dstHost2;
//        dstHost2 = d;
//
//        d = srcHost3;
//        srcHost3 = dstHost3;
//        dstHost3 = d;
    }

    public int write(byte[] data, int offset, int userPacketLength, int pathHeaderLength, Constants.PathTypes pathType) {
        this.pathType = pathType.code();
        int i0 = 0;
        int i1 = 0;
        int i2 = 0;
        i0 = writeInt(i0, 0, 4, 0); // version = 0
        i0 = writeInt(i0, 4, 8, 0); // TrafficClass = 0
        i0 = writeInt(i0, 12, 20, 1); // FlowID = 1
        offset = writeInt(data, offset, i0);
        i1 = writeInt(i1, 0, 8, 17); // NextHdr = 17 // TODO 17 is for UDP PseudoHeader
        int newHdrLen = (calcLen(pathHeaderLength) - 1) / 4 + 1;
        i1 = writeInt(i1, 8, 8, newHdrLen); // HdrLen = bytes/4
        i1 = writeInt(i1, 16, 16, userPacketLength + 8 ); // PayloadLen  // TODO? hardcoded PseudoHeaderLength....
        offset = writeInt(data, offset, i1);
        i2 = writeInt(i2, 0, 8, 1); // PathType : SCION = 1
        i2 = writeInt(i2, 8, 2, 0); // DT
        i2 = writeInt(i2, 10, 2, dl); // DL
        i2 = writeInt(i2, 12, 2, 0); // ST
        i2 = writeInt(i2, 14, 2, sl); // SL
        i2 = writeInt(i2, 16, 16, 0x0); // RSV
        offset = writeInt(data, offset, i2);

        // Address header
        offset = writeLong(data, offset, dstIsdAs);
        offset = writeLong(data, offset, srcIsdAs);

        // HostAddr
        offset = writeInt(data, offset, dstHost0);
        if (dl >= 1) {
            offset = writeInt(data, offset, dstHost1);
        }
        if (dl >= 2) {
            offset = writeInt(data, offset, dstHost2);
        }
        if (dl >= 3) {
            offset = writeInt(data, offset, dstHost3);
        }

        offset = writeInt(data, offset, srcHost0);
        if (sl >= 1) {
            offset = writeInt(data, offset, srcHost1);
        }
        if (sl >= 2) {
            offset = writeInt(data, offset, srcHost2);
        }
        if (sl >= 3) {
            offset = writeInt(data, offset, srcHost3);
        }

        return offset;
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
        sb.append("Common Header: " +
                "  VER=" + version +
                "  TrafficClass=" + trafficLClass +
                "  FlowID=" + flowId +
                // sb.append("\n");
                "  NextHdr=" + nextHeader +
                "  HdrLen=" + hdrLen + "/" + hdrLenBytes +
                "  PayloadLen=" + payLoadLen +
                // sb.append("\n");
                "  PathType=" + pathType +
                "  DT=" + dt +
                "  DL=" + dl +
                "  ST=" + st +
                "  SL=" + sl +
                "  RSV=" + reserved);

        sb.append("\n");

        sb.append("Address Header: ");
        //            sb.append("  DstISD=" + dstISD);
        //            sb.append("  DstAS =" + dstAS);
        //            //sb.append("\n");
        //            sb.append("  SrcISD=" + srcISD);
        //            sb.append("  SrcAS =" + srcAS);
        //            System.out.println(sb);
        sb.append("  dstIsdAs=").append(ScionUtil.toStringIA(dstIsdAs));
        sb.append("  srcIsdAs=").append(ScionUtil.toStringIA(srcIsdAs));
        sb.append("  dstHost=").append(dt).append("/");
        if (dl == 0) {
            sb.append(ScionUtil.toStringIPv4(dstHost0)); // TODO dt 0=IPv$ or 1=Service
        } else if (dl == 3) {
            sb.append(ScionUtil.toStringIPv6(dl + 1, dstHost0, dstHost1, dstHost2, dstHost3));
        } else {
            sb.append("Format not recognized: ").append(ScionUtil.toStringIPv6(dl + 1, dstHost0, dstHost1, dstHost2, dstHost3));
        }
        sb.append("  srcHost=").append(st).append("/");
        if (sl == 0) {
            sb.append(ScionUtil.toStringIPv4(srcHost0)); // TODO dt 0=IPv$ or 1=Service
        } else if (sl == 3) {
            sb.append(ScionUtil.toStringIPv6(sl + 1, srcHost0, srcHost1, srcHost2, srcHost3));
        } else {
            sb.append("Format not recognized: ").append(ScionUtil.toStringIPv6(sl + 1, srcHost0, srcHost1, srcHost2, srcHost3));
        }
        return sb.toString();
    }

    public int length() {
        return len;
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

    public void setDstHostAddress(byte[] address) {
        if (address.length == 4) {
            dt = 0;
            dl = 0;
            dstHost0 = readInt(address, 0);
            dstHost1 = 0;
            dstHost2 = 0;
            dstHost3 = 0;
        } else if (address.length == 16) {
            dt = 0;
            dl = 3;
            dstHost0 = readInt(address, 0);
            dstHost1 = readInt(address, 4);
            dstHost2 = readInt(address, 8);
            dstHost3 = readInt(address, 12);
        } else {
            throw new UnsupportedOperationException("Dst address class not supported: length=" + address.length);
        }
    }

    public void setSrcHostAddress(byte[] address) {
        if (address.length == 4) {
            st = 0;
            sl = 0;
            srcHost0 = readInt(address, 0);
            srcHost1 = 0;
            srcHost2 = 0;
            srcHost3 = 0;
        } else if (address.length == 16) {
            st = 0;
            sl = 3;
            srcHost0 = readInt(address, 0);
            srcHost1 = readInt(address, 4);
            srcHost2 = readInt(address, 8);
            srcHost3 = readInt(address, 12);
        } else {
            throw new UnsupportedOperationException("Dst address class not supported: " + address.getClass().getName());
        }
    }

    // TODO rename to HostString or similar?
    public String getSrcHostName() {
        byte[] bytes = new byte[(sl + 1) * 4];
        if (sl == 0 && (st == 0 || st == 1)) {
            writeInt(bytes, 0, srcHost0);
            return ScionUtil.toStringIPv4(bytes);
        } else if (sl == 3 && st == 0) {
            writeInt(bytes, 0, srcHost0);
            writeInt(bytes, 4, srcHost1);
            writeInt(bytes, 8, srcHost2);
            writeInt(bytes, 12, srcHost3);
            return ScionUtil.toStringIPv6(bytes);
        } else {
            throw new UnsupportedOperationException("Src address not supported: ST/SL=" + st + "/" + sl);
        }
    }

    public InetAddress getSrcHostAddress() throws IOException {
        byte[] bytes = new byte[(sl + 1) * 4];
        if (sl == 0 && (st == 0 || st == 1)) {
            writeInt(bytes, 0, srcHost0);
        } else if (sl == 3 && st == 0) {
            writeInt(bytes, 0, srcHost0);
            writeInt(bytes, 4, srcHost1);
            writeInt(bytes, 8, srcHost2);
            writeInt(bytes, 12, srcHost3);
        } else {
            throw new UnsupportedOperationException("Src address not supported: ST/SL=" + st + "/" + sl);
        }
        return InetAddress.getByAddress(bytes);
    }

    public InetAddress getDstHostAddress() throws IOException {
        byte[] bytes = new byte[(dl + 1) * 4];
        if (dl == 0 && (dt == 0 || dt == 1)) {
            writeInt(bytes, 0, dstHost0);
        } else if (dl == 3 && dt == 0) {
            writeInt(bytes, 0, dstHost0);
            writeInt(bytes, 4, dstHost1);
            writeInt(bytes, 8, dstHost2);
            writeInt(bytes, 12, dstHost3);
        } else {
            throw new UnsupportedOperationException("Dst address not supported: DT/DL=" + dt + "/" + dl);
        }
        return InetAddress.getByAddress(bytes);
    }

    public int getPayloadLength() {
        return payLoadLen;
    }

    public Constants.HdrTypes nextHeader() {
        return Constants.HdrTypes.parse(nextHeader);
    }
}
