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

package org.scion.jpan.internal;

import static org.scion.jpan.internal.ByteUtil.readBoolean;
import static org.scion.jpan.internal.ByteUtil.readInt;

import java.nio.ByteBuffer;

/** A lightweight stateless parser for raw paths. */
public class PathRawParserLight {
  private static final int PATH_META_LEN = 4;
  private static final int PATH_INFO_LEN = 8;
  private static final int HOP_FIELD_LEN = 12;

  private PathRawParserLight() {}

  public static int[] getSegments(ByteBuffer data) {
    int i0 = data.getInt(data.position());
    int[] segLen = new int[3];
    segLen[0] = readInt(i0, 14, 6);
    segLen[1] = readInt(i0, 20, 6);
    segLen[2] = readInt(i0, 26, 6);
    return segLen;
  }

  public static boolean extractInfoFlagC(ByteBuffer data, int segID) {
    int i0 = data.getInt(PATH_META_LEN + segID * PATH_INFO_LEN);
    return readBoolean(i0, 7);
  }

  public static int extractHopCount(int[] segLen) {
    int nHops = segLen[0] + segLen[1] + segLen[2];
    return nHops - calcSegmentCount(segLen);
  }

  public static int extractHopFieldIngress(ByteBuffer data, int segCount, int hopID) {
    int hfOffset = PATH_META_LEN + segCount * PATH_INFO_LEN + hopID * HOP_FIELD_LEN;
    return ByteUtil.toUnsigned(data.getShort(data.position() + hfOffset + 2));
  }

  public static int extractHopFieldEgress(ByteBuffer data, int segCount, int hopID) {
    int hfOffset = PATH_META_LEN + segCount * PATH_INFO_LEN + hopID * HOP_FIELD_LEN;
    return ByteUtil.toUnsigned(data.getShort(data.position() + hfOffset + 4));
  }

  public static int extractSegmentCount(ByteBuffer data) {
    int i0 = data.getInt(data.position());
    int segmentCount = 0;
    segmentCount += readInt(i0, 14, 6) > 0 ? 1 : 0;
    segmentCount += readInt(i0, 20, 6) > 0 ? 1 : 0;
    segmentCount += readInt(i0, 26, 6) > 0 ? 1 : 0;
    return segmentCount;
  }

  public static int calcSegmentCount(int[] segLen) {
    int segmentCount = 1;
    segmentCount += segLen[1] > 0 ? 1 : 0;
    segmentCount += segLen[2] > 0 ? 1 : 0;
    return segmentCount;
  }
}
