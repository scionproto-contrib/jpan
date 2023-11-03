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
import java.util.Arrays;
import org.scion.proto.daemon.Daemon;

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
    private InfoField info0;
    private InfoField info1;
    private InfoField info2;

    private final HopField[] hops = new HopField[64];
    private int nHops;

    private int len;

    public PathHeaderScionParser() {
        this.info0 = new InfoField();
        this.info1 = new InfoField();
        this.info2 = new InfoField();
        Arrays.setAll(hops, value -> new HopField());
    }

    public static boolean reversePath(byte[] data) {
        PathHeaderScion parser = new PathHeaderScion();
        parser.read(data, 0);
        parser.reverse();
        parser.write(data, 0);
        // TODO
        return true;
    }

    public static boolean reversePath(ByteBuffer data) {
        PathHeaderScionParser parser = new PathHeaderScionParser();
        parser.read(data);
        parser.reverse();
        parser.write(data);
        // TODO
        return true;
    }

    public void read(ByteBuffer data) {
        // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset calculations below).
        // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset calculations below).
        // 6 bit : RSV
        // Up to 3 Info fields and up to 64 Hop fields
        // The number of hop fields in a given segment. Seg,Len > 0 implies the existence of info field i.
        // 6 bit : Seg0Len
        // 6 bit : Seg1Len
        // 6 bit : Seg2Len

        int pos = data.position();
        int offset = 0;

        int i0 = data.getInt();
        offset += 4;
        currINF = readInt(i0, 0, 2);
        currHF = readInt(i0, 2, 6);
        reserved = readInt(i0, 8, 6);
        seg0Len = readInt(i0, 14, 6);
        seg1Len = readInt(i0, 20, 6);
        seg2Len = readInt(i0, 26, 6);

        if (seg0Len > 0) {
            info0.read(data);
            offset += info0.length();
            if (seg1Len > 0) {
                info1.read(data);
                offset += info1.length();
                if (seg2Len > 0) {
                    info2.read(data);
                    offset += info2.length();
                }
            }
        }

        for (int i = 0; i < seg0Len; i++) {
            hops[nHops].read(data);
            offset += hops[nHops].length();
            nHops++;
        }
        for (int i = 0; i < seg1Len; i++) {
            hops[nHops].read(data);
            offset += hops[nHops].length();
            nHops++;
        }
        for (int i = 0; i < seg2Len; i++) {
            hops[nHops].read(data);
            offset += hops[nHops].length();
            nHops++;
        }

        len = offset;
        data.position(pos);
    }

//    public int write(byte[] data, int offsetStart) {
//        int offset = offsetStart;
//        int i0 = 0;
//
//        // TODO simplify
//        i0 = writeInt(i0, 0, 2, 0); // CurrINF = 0
//        i0 = writeInt(i0, 2, 6, 0); // CurrHF = 0
//        i0 = writeInt(i0, 8, 6, 0); // RSV = 0
//        if (seg2Len > 0) {
//            i0 = writeInt(i0, 14, 6, seg0Len);
//            i0 = writeInt(i0, 20, 6, seg1Len);
//            i0 = writeInt(i0, 26, 6, seg2Len);
//            offset = writeInt(data, offset, i0);
//            offset = info0.write(data, offset);
//            offset = info1.write(data, offset);
//            offset = info2.write(data, offset);
//        } else if (seg1Len > 0) {
//            i0 = writeInt(i0, 14, 6, seg0Len);
//            i0 = writeInt(i0, 20, 6, seg1Len);
//            i0 = writeInt(i0, 26, 6, 0);
//            offset = writeInt(data, offset, i0);
//            offset = info0.write(data, offset);
//            offset = info1.write(data, offset);
//        } else {
//            i0 = writeInt(i0, 14, 6, seg0Len);
//            i0 = writeInt(i0, 20, 6, 0);
//            i0 = writeInt(i0, 26, 6, 0);
//            offset = writeInt(data, offset, i0);
//            offset = info0.write(data, offset);
//        }
//
//        for (int i = 0; i < seg0Len + seg1Len + seg2Len; i++) {
//            offset = hops[i].write(data, offset);
//        }
//
//        return offset;
//    }


    public void write(ByteBuffer data) {
        int pos = data.position();
        int i0 = 0;

        // TODO simplify
        i0 = writeInt(i0, 0, 2, 0); // CurrINF = 0
        i0 = writeInt(i0, 2, 6, 0); // CurrHF = 0
        i0 = writeInt(i0, 8, 6, 0); // RSV = 0
        if (seg2Len > 0) {
            i0 = writeInt(i0, 14, 6, seg0Len);
            i0 = writeInt(i0, 20, 6, seg1Len);
            i0 = writeInt(i0, 26, 6, seg2Len);
            data.putInt(i0);
            info0.write(data);
            info1.write(data);
            info2.write(data);
        } else if (seg1Len > 0) {
            i0 = writeInt(i0, 14, 6, seg0Len);
            i0 = writeInt(i0, 20, 6, seg1Len);
            i0 = writeInt(i0, 26, 6, 0);
            data.putInt(i0);
            info0.write(data);
            info1.write(data);
        } else {
            i0 = writeInt(i0, 14, 6, seg0Len);
            i0 = writeInt(i0, 20, 6, 0);
            i0 = writeInt(i0, 26, 6, 0);
            data.putInt(i0);
            info0.write(data);
        }

        for (int i = 0; i < seg0Len + seg1Len + seg2Len; i++) {
            hops[i].write(data);
        }

        data.position(pos);
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

    boolean hasConstructionDirection() {
        return info0.hasConstructionDirection();
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

    public static int writePath(byte[] data, int offsetStart, ByteString path) {
        for (int i = 0; i < path.size(); i++) {
            data[offsetStart + i] = path.byteAt(i);
        }
        return offsetStart + path.size();
    }

    public static int writePath(byte[] data, int offsetStart, byte[] path) {
        for (int i = 0; i < path.length; i++) {
            data[offsetStart + i] = path[i]; // TODO System.arrayCopy()
        }
        return offsetStart + path.length;
    }
}
