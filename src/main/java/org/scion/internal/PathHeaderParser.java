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

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class PathHeaderParser {
  private PathHeaderParser() {}

  private static int getSegmentCount(byte[] raw) {
    ByteBuffer data = ByteBuffer.wrap(raw);
    int i0 = data.getInt();
    // int seg0Len = readInt(i0, 14, 6);
    int seg1Len = readInt(i0, 20, 6);
    int seg2Len = readInt(i0, 26, 6);

    int count = 1;
    count += seg1Len > 0 ? 1 : 0;
    count += seg2Len > 0 ? 1 : 0;
    return count;
  }

  public static class Node {
    public final int id;
    public final boolean constDirFlag;
    public final int posHopFlags;
    public final byte hopFlags;

    public Node(int id, boolean constDirFlag, int posHopFlags, int hopFlags) {
      this.id = id;
      this.constDirFlag = constDirFlag;
      this.posHopFlags = posHopFlags;
      this.hopFlags = (byte) hopFlags;
    }
  }

  public static ArrayList<Node> getTraceNodes(byte[] raw) {
    ArrayList<Node> nodes = new ArrayList<>();
    if (raw == null || raw.length == 0) {
      return nodes;
    }

    ByteBuffer data = ByteBuffer.wrap(raw);
    int i0 = data.getInt();
    int seg0Len = readInt(i0, 14, 6);
    int seg1Len = readInt(i0, 20, 6);
    int seg2Len = readInt(i0, 26, 6);
    int segCount = getSegmentCount(raw);

    int nodeId = 0;

    byte flagsS0 = raw[4 + 0 * 8];
    boolean constDirFlag0 = (flagsS0 & 0x1) == 1;
    int posHopFlags = 4 + segCount * 8;
    for (int i = 0; i < seg0Len; i++) {
      if (i > 0) {
        nodes.add(new Node(nodeId++, constDirFlag0, posHopFlags, constDirFlag0 ? 2 : 1));
      }
      if (i < seg0Len - 1) {
        nodes.add(new Node(nodeId++, constDirFlag0, posHopFlags, constDirFlag0 ? 1 : 2));
      }
      posHopFlags += 12;
    }

    byte flagsS1 = raw[4 + 1 * 8];
    boolean constDirFlag1 = (flagsS1 & 0x1) == 1;
    for (int i = 0; i < seg1Len; i++) {
      if (i > 0) {
        nodes.add(new Node(nodeId++, constDirFlag1, posHopFlags, constDirFlag1 ? 2 : 1));
      }
      if (i < seg1Len - 1) {
        nodes.add(new Node(nodeId++, constDirFlag1, posHopFlags, constDirFlag1 ? 1 : 2));
      }
      posHopFlags += 12;
    }

    byte flagsS2 = raw[4 + 2 * 8];
    boolean constDirFlag2 = (flagsS2 & 0x1) == 1;
    for (int i = 0; i < seg2Len; i++) {
      if (i > 0) {
        nodes.add(new Node(nodeId++, constDirFlag2, posHopFlags, 2));
      }
      if (i < seg2Len - 1) {
        nodes.add(new Node(nodeId++, constDirFlag2, posHopFlags, 1));
      }
      posHopFlags += 12;
    }

    return nodes;
  }
}
