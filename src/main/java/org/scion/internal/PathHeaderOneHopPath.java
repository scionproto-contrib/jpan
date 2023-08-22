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

public class PathHeaderOneHopPath {ScionCommonHeader common;

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

    int len;

    PathHeaderOneHopPath(ScionCommonHeader common) {
        this.common = common;
    }

    public static PathHeaderOneHopPath read(byte[] data, int headerOffset, ScionCommonHeader commonHeader) {
        // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset calculations below).
        // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset calculations below).
        // 6 bit : RSV
        // Up to 3 Info fields and up to 64 Hop fields
        // The number of hop fields in a given segment. Seg,Len > 0 implies the existence of info field i.
        // 6 bit : Seg0Len
        // 6 bit : Seg1Len
        // 6 bit : Seg2Len

        int offset = headerOffset;
        PathHeaderOneHopPath header = new PathHeaderOneHopPath(commonHeader);

        int i0 = readInt(data, offset);
        offset += 4;
        header.currINF = readInt(i0, 0, 2);
        header.currHF = readInt(i0, 2, 6);
        header.reserved = readInt(i0, 8, 6);
        header.seg0Len = readInt(i0, 14, 6);
        header.seg1Len = readInt(i0, 20, 6);
        header.seg2Len = readInt(i0, 26, 6);

        header.len = offset - headerOffset;
        return header;
    }

    @Override
    public String toString() {
        return "OneHopPath header: " +
                "  currINF=" + currINF +
                "  currHP=" + currHF +
                "  reserved=" + reserved +
                "  seg0Len=" + seg0Len +
                "  seg1Len=" + seg1Len +
                "  seg2Len=" + seg2Len;
    }

    public int length() {
        return len;
    }
}
