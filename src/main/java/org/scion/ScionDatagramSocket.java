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
        DatagramPacket incoming = new DatagramPacket(new byte[1], 1);
        socket.receive(incoming);
        packet.setData(incoming.getData());
        packet.setPort(incoming.getPort());
        packet.setAddress(incoming.getAddress());
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
        int offset = 0;
        readCommonHeader(data);
        offset += 3 * 4;
        readAddressHeader(data, offset);
        offset += 6 * 4;
        readPathHeader(data, offset);
        //offset += ???;
        //readExtensionHeader(data, offset);
    }

    private void readCommonHeader(byte[] data) {
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
        StringBuilder sb = new StringBuilder();
        sb.append("Common Header: ");
        sb.append(" VER=" + readInt(i0, 0, 4));
        sb.append(" TC =" + readInt(i0, 4, 8));
        sb.append(" FlowID=" + readInt(i0, 12, 20));
        sb.append("\n");
        sb.append(" NextHdr=" + readInt(i1, 0, 8));
        sb.append(" HdrLen=" + readInt(i1, 8, 8));
        sb.append(" PayloadLen=" + readInt(i1, 16, 16));
        sb.append("\n");
        sb.append(" PathType=" + readInt(i2, 0, 8));
        sb.append(" DT" + readInt(i2, 8, 2));
        sb.append(" DL" + readInt(i2, 10, 2));
        sb.append(" ST" + readInt(i2, 12, 2));
        sb.append(" SL" + readInt(i2, 14, 2));
        sb.append(" DT" + readInt(i2, 16, 16));
        System.out.println(sb);
    }

    private void readAddressHeader(byte[] data, int offset) {
        //  8 bit: DstISD
        // 48 bit: DstAS
        //  8 bit: SrcISD
        // 48 bit: SrcAS
        //  ? bit: DstHostAddr
        //  ? bit: SrcHostAddr

        long l0 = readLong(data, offset);
        long l1 = readLong(data, offset + 8);
        StringBuilder sb = new StringBuilder();
        sb.append("Address Header: ");
        sb.append(" DstISD=" + readLong(l0, 0, 16));
        sb.append(" DstAS =" + readLong(l0, 4, 48));
        sb.append("\n");
        sb.append(" SrcISD=" + readLong(l1, 0, 16));
        sb.append(" SrcAS =" + readLong(l1, 4, 48));
        System.out.println(sb);
        System.out.println("Address Header (2): " + Util.toStringIA(l0) + " <- " + Util.toStringIA(l1));
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
