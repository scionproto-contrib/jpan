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

import org.scion.ScionException;
import org.scion.ScionPath;
import org.scion.ScionSocketAddress;

/**
 * Class for reading, writing and storing the Common Header and Address Header.
 */
public class ScionHeaderParser {
    public static boolean REPORT_ERROR = true;
    private static final int BYTES = 3 * 4;

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
    // TODO this is a bit weird to have the firstHopAddress here....
    public static ScionSocketAddress readRemoteSocketAddress(ByteBuffer data, InetSocketAddress firstHopAddress) {
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

        // TODO skip this, with equation fro srcHost below.
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
        PathHeaderScionParser.reversePath(path);

        // get remote port from UDP overlay
        data.position(pos + hdrLenBytes);
        int srcPort = Short.toUnsignedInt(data.getShort());

        // rewind to original offset
        data.position(pos);
        return ScionSocketAddress.create(srcIsdAs, addr, srcPort, ScionPath.create(path, dstIsdAs, srcIsdAs, firstHopAddress));
    }

    public static int write(byte[] data, int offset, int userPacketLength, int pathHeaderLength, long srcIsdAs, InetSocketAddress srcAddress, long dstIsdAs, InetSocketAddress dstAddress) {
        int sl = srcAddress.getAddress() instanceof Inet4Address ? 0 : 3;
        int dl = dstAddress.getAddress() instanceof Inet4Address ? 0 : 3;

        int i0 = 0;
        int i1 = 0;
        int i2 = 0;
        i0 = writeInt(i0, 0, 4, 0); // version = 0
        i0 = writeInt(i0, 4, 8, 0); // TrafficClass = 0
        i0 = writeInt(i0, 12, 20, 1); // FlowID = 1
        offset = writeInt(data, offset, i0);
        i1 = writeInt(i1, 0, 8, 17); // NextHdr = 17 // TODO 17 is for UDP PseudoHeader
        int newHdrLen = (calcLen(pathHeaderLength, dl, sl) - 1) / 4 + 1;
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
        byte[] dstBytes = dstAddress.getAddress().getAddress();
        System.arraycopy(dstBytes, 0, data, offset, dstBytes.length);
        offset += dstBytes.length;

        byte[] srcBytes = srcAddress.getAddress().getAddress();
        System.arraycopy(srcBytes, 0, data, offset, srcBytes.length);
        offset += srcBytes.length;

        return offset;
    }

    public static void write(ByteBuffer data, int userPacketLength, int pathHeaderLength, long srcIsdAs, InetAddress srcAddress, long dstIsdAs, InetAddress dstAddress) {
        int sl = srcAddress instanceof Inet4Address ? 0 : 3;
        int dl = dstAddress instanceof Inet4Address ? 0 : 3;

        int i0 = 0;
        int i1 = 0;
        int i2 = 0;
        i0 = writeInt(i0, 0, 4, 0); // version = 0
        i0 = writeInt(i0, 4, 8, 0); // TrafficClass = 0
        i0 = writeInt(i0, 12, 20, 1); // FlowID = 1
        data.putInt(i0);
        i1 = writeInt(i1, 0, 8, 17); // NextHdr = 17 // TODO 17 is for UDP PseudoHeader
        int newHdrLen = (calcLen(pathHeaderLength, dl, sl) - 1) / 4 + 1;
        i1 = writeInt(i1, 8, 8, newHdrLen); // HdrLen = bytes/4
        i1 = writeInt(i1, 16, 16, userPacketLength + 8 ); // PayloadLen  // TODO? hardcoded PseudoHeaderLength....
        data.putInt(i1);
        i2 = writeInt(i2, 0, 8, 1); // PathType : SCION = 1
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
        byte[] dstBytes = dstAddress.getAddress();
        data.put(dstBytes);
        byte[] srcBytes = srcAddress.getAddress();
        data.put(srcBytes);
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
}
