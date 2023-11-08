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

package org.scion.demo.util;

import com.google.protobuf.ByteString;
import org.scion.proto.daemon.Daemon;

import java.util.Arrays;

import static org.scion.demo.util.ByteUtil.*;

public class PathHeaderScion {

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
    private InfoField info0;
    private InfoField info1;
    private InfoField info2;

    private final HopField[] hops = new HopField[64];
    private int nHops;

    private int len;

    public PathHeaderScion() {
        this.info0 = new InfoField();
        this.info1 = new InfoField();
        this.info2 = new InfoField();
        Arrays.setAll(hops, value -> new HopField());
    }

    public int read(byte[] data, int headerOffset) {
        reset();
        // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset calculations below).
        // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset calculations below).
        // 6 bit : RSV
        // Up to 3 Info fields and up to 64 Hop fields
        // The number of hop fields in a given segment. Seg,Len > 0 implies the existence of info field i.
        // 6 bit : Seg0Len
        // 6 bit : Seg1Len
        // 6 bit : Seg2Len

        int offset = headerOffset;

        int i0 = readInt(data, offset);
        offset += 4;
        currINF = readInt(i0, 0, 2);
        currHF = readInt(i0, 2, 6);
        reserved = readInt(i0, 8, 6);
        seg0Len = readInt(i0, 14, 6);
        seg1Len = readInt(i0, 20, 6);
        seg2Len = readInt(i0, 26, 6);

        if (seg0Len > 0) {
            info0.read(data, offset);
            offset += info0.length();
            if (seg1Len > 0) {
                info1.read(data, offset);
                offset += info1.length();
                if (seg2Len > 0) {
                    info2.read(data, offset);
                    offset += info2.length();
                }
            }
        }

        for (int i = 0; i < seg0Len; i++) {
            hops[nHops].read(data, offset);
            offset += hops[nHops].length();
            nHops++;
        }
        for (int i = 0; i < seg1Len; i++) {
            hops[nHops].read(data, offset);
            offset += hops[nHops].length();
            nHops++;
        }
        for (int i = 0; i < seg2Len; i++) {
            hops[nHops].read(data, offset);
            offset += hops[nHops].length();
            nHops++;
        }

        len = offset - headerOffset;
        return offset;
    }

    public int write(byte[] data, int offsetStart) {
        int offset = offsetStart;
        int i0 = 0;

        i0 = writeInt(i0, 0, 2, 0); // CurrINF = 0
        i0 = writeInt(i0, 2, 6, 0); // CurrHF = 0
        i0 = writeInt(i0, 8, 6, 0); // RSV = 0
        i0 = writeInt(i0, 14, 6, seg0Len);
        i0 = writeInt(i0, 20, 6, seg1Len);
        i0 = writeInt(i0, 26, 6, seg2Len);
        offset = writeInt(data, offset, i0);
        offset = info0.write(data, offset);
        if (seg2Len > 0) {
            offset = info1.write(data, offset);
            offset = info2.write(data, offset);
        } else if (seg1Len > 0) {
            offset = info1.write(data, offset);
        }

        for (int i = 0; i < seg0Len + seg1Len + seg2Len; i++) {
            offset = hops[i].write(data, offset);
        }

        return offset;
    }

    public void reverse() {
        currINF = 0;
        currHF = 0;
        if (seg2Len > 0) {
            int dummySegLen = seg0Len;
            seg0Len = seg2Len;
            seg2Len = dummySegLen;
            InfoField dummyInfo = info0;
            info0 = info2;
            info2 = dummyInfo;
        } else if (seg1Len > 0) {
            int dummySegLen = seg0Len;
            seg0Len = seg1Len;
            seg1Len = dummySegLen;
            InfoField dummyInfo = info0;
            info0 = info1;
            info1 = dummyInfo;
        }
        info0.reverse();
        info1.reverse();
        info2.reverse();

        for (int i = 0, j = nHops - 1; i < j; i++, j--) {
            HopField dummy = hops[i];
            hops[i] = hops[j];
            hops[j] = dummy;
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

    public int length() {
        return len;
    }

  private void reset() {
    currINF = 0;
    currHF = 0;
    reserved = 0;
    seg0Len = 0;
    seg1Len = 0;
    seg2Len = 0;
    info0.reset();
    info1.reset();
    info2.reset();
    for (int i = 0; i < hops.length; i++) {
      hops[i].reset();
    }
    nHops = 0;
  }

    public int writePath(byte[] data, int offsetStart, Daemon.Path path) {
        // TODO reset() necessary??? -> info fields !??!?!?
        currINF = 0;
        currHF = 0;

        // write
        ByteString bytes = path.getRaw();
        for (int i = 0; i < bytes.size(); i++) {
            data[offsetStart + i] = bytes.byteAt(i);
        }

        return offsetStart + bytes.size();
    }
}
