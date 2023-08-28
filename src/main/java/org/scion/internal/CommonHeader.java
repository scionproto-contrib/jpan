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

import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;

import static org.scion.internal.ByteUtil.*;

public class CommonHeader {
    static final int BYTES = 3 * 4;
    //  4 bit: Version : Currently, only 0 is supported.
    int version;
    //  8 bit: TrafficClass / QoS
    int trafficLClass;
    // 20 bit: FlowID
    int flowId;
    //  8 bit: NextHdr
    int nextHeader;
    //  8 bit: HdrLen :  Common header + address header + path header. bytes = hdrLen * 4;
    int hdrLen;
    int hdrLenBytes;
    // 16 bit: PayloadLen
    int payLoadLen;
    //  8 bit: PathType  :  Empty (0), SCION (1), OneHopPath (2), EPIC (3) and COLIBRI (4)
    int pathType;
    //  2 bit: DT
    int dt;
    //  2 bit: DL : 4 bytes, 8 bytes, 12 bytes and 16 bytes
    int dl;
    //  2 bit: ST
    int st;
    //  2 bit: SL : 4 bytes, 8 bytes, 12 bytes and 16 bytes
    int sl;
    //  8 bit: reserved
    int reserved;
    int len = 3 * 4;

    public int read(byte[] data, int offset) {
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
        int i0 = readInt(data, offset);
        int i1 = readInt(data, offset + 4);
        int i2 = readInt(data, offset + 8);
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
        return BYTES;
    }

    public static int write(byte[] data, DatagramPacket input, InetAddress localAddress) {
        int offset = 0;
        int i0 = 0;
        int i1 = 0;
        int i2 = 0;
        i0 = writeInt(i0, 0, 4, 0); // version = 0
        i0 = writeInt(i0, 4, 8, 0); // TrafficClass = 0
        i0 = writeInt(i0, 0, 4, 1); // FlowID = 1
        writeInt(data, offset, i0);
        offset += 4;
        i1 = writeInt(i1, 0, 8, 17); // NextHdr = 17 // TODO
        i1 = writeInt(i1, 8, 8, 21); // HdrLen = 84/4=21 // TODO?
        i1 = writeInt(i1, 16, 16, input.getLength()); // PayloadLen
        writeInt(data, offset, i1);
        offset += 4;
        i2 = writeInt(i2, 0, 8, 1); // PathType : SCION = 1
        int dl = input.getAddress() instanceof Inet4Address ? 0 : 3;
        i2 = writeInt(i2, 8, 2, 0); // DT
        i2 = writeInt(i2, 10, 2, dl); // DL
        i2 = writeInt(i2, 12, 2, 0); // ST
        int sl = localAddress instanceof Inet4Address ? 0 : 3;
        i2 = writeInt(i2, 14, 2, sl); // SL
        i2 = writeInt(i2, 16, 16, 0x0); // RSV
        writeInt(data, offset + 8, i2);
        offset += 4;
        return offset;
    }

    public String toString() {
        return "Common Header: " +
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
                "  RSV=" + reserved;
    }

    public int length() {
        return len;
    }

    public int hdrLenBytes() {
        return hdrLenBytes;
    }

    public int pathType() {
        return pathType;
    }

    public int getDT() {
        return dt;
    }
}
