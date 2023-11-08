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

import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;

public class PathHeaderScionParser {

    // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset calculations below).
    private int currINF;
    // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset calculations below).
    private int currHF;
    // 6 bit : RSV
    private int reserved;
    // Up to 3 Info fields and up to 64 Hop fields
    // The number of hop fields in a given segment. Seg,Len > 0 implies the existence of info field i.
    // 6 bit : Seg0Len
    private int seg0Len;
    // 6 bit : Seg1Len
    private int seg1Len;
    // 6 bit : Seg2Len
    private int seg2Len;
    private long info0;
    private long info1;
    private long info2;
    private final int[] hops = new int[64 * 3];  // 3 integer per field
    private int nHops;

    public PathHeaderScionParser() {

    }

    public static void reversePath(ByteBuffer data) {
        PathHeaderScionParser parser = new PathHeaderScionParser();
        int pos = data.position();
        parser.read(data);

        parser.reverse();

        data.position(pos);
        parser.write(data);
    }

    private void read(ByteBuffer data) {
        // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset calculations below).
        // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset calculations below).
        // 6 bit : RSV
        // Up to 3 Info fields and up to 64 Hop fields
        // The number of hop fields in a given segment. Seg,Len > 0 implies the existence of info field i.
        // 6 bit : Seg0Len
        // 6 bit : Seg1Len
        // 6 bit : Seg2Len

        int pos = data.position();

        int i0 = data.getInt();
        currINF = readInt(i0, 0, 2);
        currHF = readInt(i0, 2, 6);
        reserved = readInt(i0, 8, 6);
        seg0Len = readInt(i0, 14, 6);
        seg1Len = readInt(i0, 20, 6);
        seg2Len = readInt(i0, 26, 6);

        if (seg0Len > 0) {
            info0 = data.getLong();
            if (seg1Len > 0) {
                info1 = data.getLong();
                if (seg2Len > 0) {
                    info2 = data.getLong();
                }
            }
        }

        nHops = seg0Len + seg1Len + seg2Len;
        for (int i = 0; i < nHops * 3; i++) {
            hops[i] = data.getInt();
        }

        data.position(pos);
    }

    private void write(ByteBuffer data) {
        int pos = data.position();
        int i0 = 0;

        i0 = writeInt(i0, 0, 2, 0); // CurrINF = 0
        i0 = writeInt(i0, 2, 6, 0); // CurrHF = 0
        i0 = writeInt(i0, 8, 6, 0); // RSV = 0
        i0 = writeInt(i0, 14, 6, seg0Len);
        i0 = writeInt(i0, 20, 6, seg1Len);
        i0 = writeInt(i0, 26, 6, seg2Len);
        data.putInt(i0);
        data.putLong(info0);
        if (seg2Len > 0) {
            data.putLong(info1);
            data.putLong(info2);
        } else if (seg1Len > 0) {
            data.putLong(info1);
        }
        for (int i = 0; i < nHops * 3; i++) {
            data.putInt(hops[i]);
        }

        data.position(pos);
    }

    private void reverse() {
        currINF = 0;
        currHF = 0;
        // reverse order
        if (seg2Len > 0) {
            int dummySegLen = seg0Len;
            seg0Len = seg2Len;
            seg2Len = dummySegLen;
            long dummyInfo = info0;
            info0 = info2;
            info2 = dummyInfo;
        } else if (seg1Len > 0) {
            int dummySegLen = seg0Len;
            seg0Len = seg1Len;
            seg1Len = dummySegLen;
            long dummyInfo = info0;
            info0 = info1;
            info1 = dummyInfo;
        }
        // reverse direction
        long currDirMask = 1L << 56;
        info0 ^= currDirMask;
        if (seg1Len > 0) {
            info1 ^= currDirMask;
        }
        if (seg2Len > 0) {
            info2 ^= currDirMask;
        }

        for (int i = 0, j = nHops - 1; i < j; i++, j--) {
          for (int x = 0; x < 3; x++) {
            int dummy = hops[i * 3 + x];
            hops[i * 3 + x] = hops[j * 3 + x];
            hops[j * 3 + x] = dummy;
          }
        }
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
        if (seg0Len > 0) {
            s += "\n  info0=" + info0;
        }
        if (seg1Len > 0) {
            s += "\n  info1=" + info1;
        }
        if (seg2Len > 0) {
            s += "\n  info2=" + info2;
        }
        for (int i = 0; i < nHops; i++) {
            s += "\n    hop=" + hops[i];
        }
        return s;
    }

    public static void writePath(ByteBuffer data, ByteString path) {
        for (int i = 0; i < path.size(); i++) {
            data.put(path.byteAt(i));
        }
    }

    public static void writePath(ByteBuffer data, byte[] path) {
        data.put(path);
    }
}
