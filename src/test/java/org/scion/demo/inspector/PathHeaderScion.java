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

package org.scion.demo.inspector;

import static org.scion.demo.inspector.ByteUtil.*;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class PathHeaderScion {

  // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset
  // calculations below).
  private int currINF;
  // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset
  // calculations below).
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

  public void read(ByteBuffer data) {
    reset();
    int start = data.position();
    // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset
    // calculations below).
    // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset
    // calculations below).
    // 6 bit : RSV
    // Up to 3 Info fields and up to 64 Hop fields
    // The number of hop fields in a given segment. Seg,Len > 0 implies the existence of info field
    // i.
    // 6 bit : Seg0Len
    // 6 bit : Seg1Len
    // 6 bit : Seg2Len

    int i0 = data.getInt();
    currINF = readInt(i0, 0, 2);
    currHF = readInt(i0, 2, 6);
    reserved = readInt(i0, 8, 6);
    seg0Len = readInt(i0, 14, 6);
    seg1Len = readInt(i0, 20, 6);
    seg2Len = readInt(i0, 26, 6);

    if (seg0Len > 0) {
      info0.read(data);
      info0.length();
      if (seg1Len > 0) {
        info1.read(data);
        info1.length();
        if (seg2Len > 0) {
          info2.read(data);
          info2.length();
        }
      }
    }

    nHops = seg0Len + seg1Len + seg2Len;
    for (int i = 0; i < nHops; i++) {
      hops[i].read(data);
      hops[i].length();
    }

    len = data.position() - start;
  }

  public void write(ByteBuffer data) {
    if (seg0Len == 0) {
      // empty path
      return;
    }
    int i0 = 0;
    i0 = writeInt(i0, 0, 2, 0); // CurrINF = 0
    i0 = writeInt(i0, 2, 6, 0); // CurrHF = 0
    i0 = writeInt(i0, 8, 6, 0); // RSV = 0
    i0 = writeInt(i0, 14, 6, seg0Len);
    i0 = writeInt(i0, 20, 6, seg1Len);
    i0 = writeInt(i0, 26, 6, seg2Len);
    data.putInt(i0);
    info0.write(data);
    if (seg2Len > 0) {
      info1.write(data);
      info2.write(data);
    } else if (seg1Len > 0) {
      info1.write(data);
    }

    for (int i = 0; i < seg0Len + seg1Len + seg2Len; i++) {
      hops[i].write(data);
    }
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
    String s =
        "Path header: "
            + "  currINF="
            + currINF
            + "  currHP="
            + currHF
            + "  reserved="
            + reserved
            + "  seg0Len="
            + seg0Len
            + "  seg1Len="
            + seg1Len
            + "  seg2Len="
            + seg2Len;
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

  public void writePath(ByteBuffer data, byte[] path) {
    currINF = 0;
    currHF = 0;
    data.put(path);
  }

  public InfoField getInfoField(int i) {
    switch (i) {
      case 0:
        return info0;
      case 1:
        return info1;
      case 2:
        return info2;
      default:
        throw new IllegalArgumentException("i=" + i);
    }
  }

  public int getHopCount() {
    return nHops;
  }

  public HopField getHopField(int i) {
    return hops[i];
  }

  public int getSegLen(int i) {
    switch (i) {
      case 0:
        return seg0Len;
      case 1:
        return seg1Len;
      case 2:
        return seg2Len;
      default:
        throw new IllegalArgumentException("i=" + i);
    }
  }
}
