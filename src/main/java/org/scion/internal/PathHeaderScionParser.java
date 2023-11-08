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

import static org.scion.internal.ByteUtil.readInt;
import static org.scion.internal.ByteUtil.writeInt;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;

public class PathHeaderScionParser {

  public static void reversePathInPlace(ByteBuffer data) {
    int pos = data.position();
    int i0 = data.getInt();
    int seg0Len = readInt(i0, 14, 6);
    int seg1Len = readInt(i0, 20, 6);
    int seg2Len = readInt(i0, 26, 6);

    // set CurrINF=0; CurrHF=0; RSV=0
    int i0R = 0;
    i0R = writeInt(i0R, 0, 2, 0); // CurrINF = 0
    i0R = writeInt(i0R, 2, 6, 0); // CurrHF = 0
    i0R = writeInt(i0R, 8, 6, 0); // RSV = 0
    // reverse segLen
    int seg0LenR = seg2Len > 0 ? seg2Len : seg1Len > 0 ? seg1Len : seg0Len;
    int seg1LenR = seg2Len > 0 ? seg1Len : seg1Len > 0 ? seg0Len : seg1Len;
    int seg2LenR = seg2Len > 0 ? seg0Len : seg2Len;
    i0R = writeInt(i0R, 14, 6, seg0LenR);
    i0R = writeInt(i0R, 20, 6, seg1LenR);
    i0R = writeInt(i0R, 26, 6, seg2LenR);
    data.putInt(pos, i0R);

    // info fields
    int posInfo = data.position();
    long info0 = data.getLong();
    long info1 = seg1Len > 0 ? data.getLong() : 0;
    long info2 = seg2Len > 0 ? data.getLong() : 0;
    long info0R = seg2Len > 0 ? info2 : seg1Len > 0 ? info1 : info0;
    long info1R = seg2Len > 0 ? info1 : seg1Len > 0 ? info0 : info1;
    long info2R = seg2Len > 0 ? info0 : info2;

    data.position(posInfo);
    long currDirMask = 1L << 56; // For inverting the CurrDir flag.
    data.putLong(info0R ^ currDirMask);
    if (seg1LenR > 0) {
      data.putLong(info1R ^ currDirMask);
    }
    if (seg1LenR > 0) {
      data.putLong(info2R ^ currDirMask);
    }

    // hop fields
    int posHops = data.position();
    int nHops = seg0Len + seg1Len + seg2Len;
    for (int i = 0, j = nHops - 1; i < j; i++, j--) {
      int posI = posHops + i * 3 * 4; // 3 int per HopField and 4 bytes per int
      int posJ = posHops + j * 3 * 4;
      for (int x = 0; x < 3; x++) {
        int dummy = data.getInt(posI + x * 4);
        data.putInt(posI + x * 4, data.getInt(posJ + x * 4));
        data.putInt(posJ + x * 4, dummy);
      }
    }

    data.position(pos);
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
