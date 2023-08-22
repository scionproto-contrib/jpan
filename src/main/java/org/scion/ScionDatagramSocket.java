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

package org.scion;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class ScionDatagramSocket {
    /*
     * Design:
     * We use delegation rather than inheritance. Inheritance is more difficult to handle if future version of
     * java.net.DatagramSocket get additional methods that need special SCION handling. These methods would
     * behave incorrectly until adapted, and, once adapted, would not compile anymore with earlier Java releases.
    */

    private final DatagramSocket socket;

    public ScionDatagramSocket(int port) throws SocketException {
        this.socket = new DatagramSocket(port);
    }

    public void receive(DatagramPacket packet) throws IOException {
//        System.out.println("receive - 1"); // TODO
        byte[] buf = new byte[65536];
        DatagramPacket incoming = new DatagramPacket(buf, buf.length);
//        System.out.println("receive - 2"); // TODO
        socket.receive(incoming);
        System.out.println("received: len=" + incoming.getLength()); // TODO
//        for (int i = 0; i < incoming.getLength(); i++) {
//            System.out.print("  " + incoming.getData()[i]);
//        }
//        System.out.println();
        if (incoming.getLength() == 1) {
            System.out.println("Aborting");
            return;
        }
//        System.out.println("receive - 3b : " + incoming.getLength()); // TODO
        readScionHeader(incoming);
//        System.out.println("receive - g"); // TODO
        packet.setData(incoming.getData());
//        System.out.println("receive - 4"); // TODO
        packet.setPort(incoming.getPort());
//        System.out.println("receive - 5"); // TODO
        packet.setAddress(incoming.getAddress());
//        System.out.println("receive - 6"); // TODO
        // Not necessary: packet.setSocketAddress(incoming.getSocketAddress());
    }

    public void send(DatagramPacket packet) throws IOException {
        DatagramPacket outgoing = new DatagramPacket(new byte[1], 1);
        outgoing.setData(packet.getData());
        outgoing.setPort(packet.getPort());
        outgoing.setAddress(packet.getAddress());
        socket.send(outgoing);
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    public int getPort() {
        return socket.getPort();
    }

    private void readScionHeader(DatagramPacket p) {
        byte[] data = p.getData();
        ScionCommonHeader common = new ScionCommonHeader();
        readCommonHeader(data, common);
        System.out.println("Common header: " + common);
        int offset = ScionCommonHeader.BYTES;
        AddressHeader address = new AddressHeader(common);
        readAddressHeader(data, offset, address);
        System.out.println("Address header: " + address);
        offset += 6 * 4;
        readPathHeader(data, offset);
        //offset += ???;
        //readExtensionHeader(data, offset);
    }


    static class ScionCommonHeader {
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

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Common Header: ");
            sb.append("  VER=" + version);
            sb.append("  TrafficClass=" + trafficLClass);
            sb.append("  FlowID=" + flowId);
            // sb.append("\n");
            sb.append("  NextHdr=" + nextHeader + "/" + hdrLenBytes);
            sb.append("  HdrLen=" + hdrLen);
            sb.append("  PayloadLen=" + payLoadLen);
            // sb.append("\n");
            sb.append("  PathType=" + pathType);
            sb.append("  DT=" + dt);
            sb.append("  DL=" + dl);
            sb.append("  ST=" + st);
            sb.append("  SL=" + sl);
            sb.append("  RSV=" + reserved);
            return sb.toString();
        }
    }

    static class AddressHeader {
        ScionCommonHeader common;
        //  8 bit: DstISD
        int dstISD;
        // 48 bit: DstAS
        long dstAS;
        //  8 bit: SrcISD
        int srcISD;
        // 48 bit: SrcAS
        long srcAS;
        //  ? bit: DstHostAddr
        int dstHost0;
        int dstHost1;
        int dstHost2;
        int dstHost3;
        //  ? bit: SrcHostAddr
        int srcHost0;
        int srcHost1;
        int srcHost2;
        int srcHost3;

        int len;

        AddressHeader(ScionCommonHeader common) {
            this.common = common;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Address Header: ");
            //            sb.append("  DstISD=" + dstISD);
            //            sb.append("  DstAS =" + dstAS);
            //            //sb.append("\n");
            //            sb.append("  SrcISD=" + srcISD);
            //            sb.append("  SrcAS =" + srcAS);
            //            System.out.println(sb);
            sb.append("  dstIsdAs=").append(Util.toStringIA(dstISD, dstAS));
            sb.append("  srcIsdAs=").append(Util.toStringIA(srcISD, srcAS));
            sb.append("  dstHost=" + common.dt + "/");
            if (common.dl == 0) {
                sb.append(Util.toStringIPv4(dstHost0)); // TODO dt 0=IPv$ or 1=Service
            } else if (common.dl == 3) {
                sb.append(Util.toStringIPv6(common.dl + 1, dstHost0, dstHost1, dstHost2, dstHost3));
            } else {
                sb.append("Format not recognized: " + Util.toStringIPv6(common.dl + 1, dstHost0, dstHost1, dstHost2, dstHost3));
            }
//            switch (common.dl) {
//                case 0: sb.append(Util.toStringIPv4(dstHost0)); break;
//                case 1: sb.append(Util.toStringIPv4(dstHost0)).append(Util.toStringIPv4(dstHost1)); break;
//                case 2: sb.append(Util.toStringIPv4(dstHost0)).append(Util.toStringIPv4(dstHost1)).append(Util.toStringIPv4(dstHost2)); break;
//                case 3: sb.append(Util.toStringIPv4(dstHost0)).append(Util.toStringIPv4(dstHost1)).append(Util.toStringIPv4(dstHost2)).append(Util.toStringIPv4(dstHost3)); break;
//                default:
//                    throw new IllegalArgumentException("DL=" + common.dl);
//            }
            sb.append("  srcHost=" + common.st + "/");
            if (common.sl == 0) {
                sb.append(Util.toStringIPv4(srcHost0)); // TODO dt 0=IPv$ or 1=Service
            } else if (common.sl == 3) {
                sb.append(Util.toStringIPv6(common.sl + 1, srcHost0, srcHost1, srcHost2, srcHost3));
            } else {
                sb.append("Format not recognized: " + Util.toStringIPv6(common.sl + 1, srcHost0, srcHost1, srcHost2, srcHost3));
            }
//            switch (common.sl) {
//                case 0: sb.append(Util.toStringIPv4(srcHost0)); break;
//                case 1: sb.append(Util.toStringIPv4(srcHost0)).append(Util.toStringIPv4(srcHost1)); break;
//                case 2: sb.append(Util.toStringIPv4(srcHost0)).append(Util.toStringIPv4(srcHost1)).append(Util.toStringIPv4(srcHost2)); break;
//                case 3: sb.append(Util.toStringIPv4(srcHost0)).append(Util.toStringIPv4(srcHost1)).append(Util.toStringIPv4(srcHost2)).append(Util.toStringIPv4(srcHost3)); break;
//                default:
//                    throw new IllegalArgumentException("SL=" + common.sl);
//            }
            return sb.toString();
        }
    }

    private void readCommonHeader(byte[] data, ScionCommonHeader header) {
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
        int i0 = readInt(data, 0);
        int i1 = readInt(data, 4);
        int i2 = readInt(data, 8);
        header.version = readInt(i0, 0, 4);
        header.trafficLClass = + readInt(i0, 4, 8);
        header.flowId = readInt(i0, 12, 20);
        header.nextHeader = readInt(i1, 0, 8);
        header.hdrLen = readInt(i1, 8, 8);
        header.hdrLenBytes = header.hdrLen * 4;
        header.payLoadLen = readInt(i1, 16, 16);
        header.pathType = readInt(i2, 0, 8);
        header.dt = readInt(i2, 8, 2);
        header.dl = readInt(i2, 10, 2);
        header.st = readInt(i2, 12, 2);
        header.sl = readInt(i2, 14, 2);
        header.reserved = readInt(i2, 16, 16);
        header.len = 3 * 4;
    }

    private void readAddressHeader(byte[] data, int headerOffset, AddressHeader header) {
        //  8 bit: DstISD
        // 48 bit: DstAS
        //  8 bit: SrcISD
        // 48 bit: SrcAS
        //  ? bit: DstHostAddr
        //  ? bit: SrcHostAddr

        int offset = headerOffset;

        long l0 = readLong(data, offset);
        offset += 8;
        long l1 = readLong(data, offset);
        offset += 8;
        header.dstISD = (int) readLong(l0, 0, 16);
        header.dstAS = readLong(l0, 16, 48);
        header.srcISD = (int) readLong(l1, 0, 16);
        header.srcAS = readLong(l1, 16, 48);

        header.dstHost0 = readInt(data, offset);
        offset += 4;
        if (header.common.dl >= 1) {
            header.dstHost1 = readInt(data, offset);
            offset += 4;
        }
        if (header.common.dl >= 2) {
            header.dstHost2 = readInt(data, offset);
            offset += 4;
        }
        if (header.common.dl >= 3) {
            header.dstHost3 = readInt(data, offset);
            offset += 4;
        }

        header.srcHost0 = readInt(data, offset);
        offset += 4;
        if (header.common.sl >= 1) {
            header.srcHost1 = readInt(data, offset);
            offset += 4;
        }
        if (header.common.sl >= 2) {
            header.srcHost2 = readInt(data, offset);
            offset += 4;
        }
        if (header.common.sl >= 3) {
            header.srcHost3 = readInt(data, offset);
            offset += 4;
        }

        header.len = offset - headerOffset;
    }

    private void readPathHeader(byte[] data, int offset) {

    }

    private void readExtensionHeader(byte[] data, int offset) {

    }

    private int readInt(byte[] data, int offset) {
        int r = 0;
        for (int i = 0; i < 4; i++) {
            r <<= 8;
            r |= data[i + offset];
        }
        return r;
    }

    private long readLong(byte[] data, int offset) {
        long r = 0;
        for (int i = 0; i < 8; i++) {
            r <<= 8;
            r |= data[i + offset];
        }
        return r;
    }

    /**
     * Reads some bits from an integer and returns them as another integer, shifted right such that the least
     * significant extracted bit becomes the least significant bit in the output.
     * 
     * @param input input
     * @param bitOffset First bit to read. 0 is the most significant bit, 31 is the least significant bit
     * @param bitCount number of bits to read
     * @return extracted bits as int.
     */
    private int readInt(int input, int bitOffset, int bitCount) {
        int mask = (-1) >>> (32 - bitCount);
        int shift = 32 - bitOffset - bitCount;
        return (input >>> shift) & mask;
    }

    private long readLong(long input, int bitOffset, int bitCount) {
        long mask = (-1L) >>> (64 - bitCount);
        int shift = 64 - bitOffset - bitCount;
        return (input >>> shift) & mask;
    }
}
