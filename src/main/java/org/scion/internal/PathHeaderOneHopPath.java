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

    // A OneHopPath has exactly one info field and two hop fields
    private InfoField info;
    private HopField hop0;
    private HopField hop1;

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

        header.info = InfoField.read(data, offset);
        offset += header.info.length();
        header.hop0 = HopField.read(data, offset);
        offset += header.hop0.length();
        header.hop1 = HopField.read(data, offset);
        offset += header.hop1.length();
        return header;
    }

    @Override
    public String toString() {
        return "OneHopPath header: " +
                "\n  info=" + info +
                "\n    hop0=" + hop0 +
                "\n    hop1=" + hop1;
    }

    public int length() {
    return info.length() + hop0.length() + hop1.length();
    }
}
