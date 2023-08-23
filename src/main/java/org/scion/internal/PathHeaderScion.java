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

public class PathHeaderScion {ScionCommonHeader common;

    // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset calculations below).
    int currINF;
    // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset calculations below).
    int currHF;
    // 6 bit : RSV
    int reserved;
    // Up to 3 Info fields and up to 64 Hop fields
    // The number of hop fields in a given segment. Seg,Len > 0 implies the existence of info field i.
    // 6 bit : Seg0Len
    int seg0Len;
    // 6 bit : Seg1Len
    int seg1Len;
    // 6 bit : Seg2Len
    int seg2Len;
    private InfoField info0;
    private InfoField info1;
    private InfoField info2;

    private HopField[] hops = new HopField[64];
    private int nHops;

    int len;

    PathHeaderScion(ScionCommonHeader common) {
        this.common = common;
    }

    public static PathHeaderScion read(byte[] data, int headerOffset, ScionCommonHeader commonHeader) {
        // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset calculations below).
        // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset calculations below).
        // 6 bit : RSV
        // Up to 3 Info fields and up to 64 Hop fields
        // The number of hop fields in a given segment. Seg,Len > 0 implies the existence of info field i.
        // 6 bit : Seg0Len
        // 6 bit : Seg1Len
        // 6 bit : Seg2Len

        int offset = headerOffset;
        PathHeaderScion header = new PathHeaderScion(commonHeader);

        int i0 = readInt(data, offset);
        offset += 4;
        header.currINF = readInt(i0, 0, 2);
        header.currHF = readInt(i0, 2, 6);
        header.reserved = readInt(i0, 8, 6);
        header.seg0Len = readInt(i0, 14, 6);
        header.seg1Len = readInt(i0, 20, 6);
        header.seg2Len = readInt(i0, 26, 6);

        if (header.seg0Len > 0) {
            header.info0 = InfoField.read(data, offset);
            offset += header.info0.length();
            if (header.seg1Len > 0) {
                header.info1 = InfoField.read(data, offset);
                offset += header.info1.length();
                if (header.seg2Len > 0) {
                    header.info2 = InfoField.read(data, offset);
                    offset += header.info2.length();
                }
            }
        }

        for (int i = 0; i < header.seg0Len; i++) {
            header.hops[header.nHops] = HopField.read(data, offset);
            offset += header.hops[header.nHops].length();
            header.nHops++;
        }
        for (int i = 0; i < header.seg1Len; i++) {
            header.hops[header.nHops] = HopField.read(data, offset);
            offset += header.hops[header.nHops].length();
            header.nHops++;
        }
        for (int i = 0; i < header.seg2Len; i++) {
            header.hops[header.nHops] = HopField.read(data, offset);
            offset += header.hops[header.nHops].length();
            header.nHops++;
        }

        header.len = offset - headerOffset;
        return header;
    }

    @Override
    public String toString() {
        String s = "SCION path header: " +
                "  currINF=" + currINF +
                "  currHP=" + currHF +
                "  reserved=" + reserved +
                "  seg0Len=" + seg0Len +
                "  seg1Len=" + seg1Len +
                "  seg2Len=" + seg2Len;
        if (info0 != null) {
            s += "\n  info0=" + info0;
        }
        if (info1 != null) {
            s += "\n  info1=" + info1;
        }
        if (info2 != null) {
            s += "\n  info2=" + info2;
        }
        for (HopField hop : hops) {
            if (hop != null) {
                s += "\n    hop=" + hop;
            }
        }
        return s;
    }

    public int length() {
        return len;
    }
}
