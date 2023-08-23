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

public class PseudoHeader {

    // 16 bit
    private int srcPort;
    // 16 bit
    private int dstPort;
    // 16 bit
    private int packetLength;
    // 16 bit
    private int checkSum;

    public static PseudoHeader read(byte[] data, int offset) {
        PseudoHeader field = new PseudoHeader();
        field.readData(data, offset);
        return field;
    }

    private void readData(byte[] data, int offset) {
        int i0 = readInt(data, offset);
        int i1 = readInt(data, offset + 4);
        srcPort = readInt(i0, 0, 16);
        dstPort = readInt(i0, 16, 16);
        packetLength = readInt(i1, 0, 16);
        checkSum = readInt(i1, 16, 16);
    }

    @Override
    public String toString() {
        return "UdpPseudoHeader{" +
                "srcPort=" + srcPort +
                ", dstPort=" + dstPort +
                ", length=" + packetLength +
                ", checkSum=" + checkSum +
                '}';
    }

    public int length() {
        return 8;
    }

    public int getSrcPort() {
        return srcPort;
    }
}
