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

package org.scion.jpan.demo.inspector;

import java.nio.ByteBuffer;

public class ByteUtil extends org.scion.jpan.internal.ByteUtil {

  private ByteUtil() {
    super();
  }

  public static String printHeader(ByteBuffer b) {
    String newLine = System.lineSeparator();
    StringBuilder sb = new StringBuilder();
    int pos = b.position();
    sb.append("Common Header").append(newLine);
    printLine(sb, b);
    printLine(sb, b);
    printLine(sb, b);
    sb.append("Address Header").append(newLine);
    printLine(sb, b);
    printLine(sb, b);
    printLine(sb, b);
    printLine(sb, b);
    int dlsl = b.getInt(pos + 9);
    int dl = readInt(dlsl << 16, 10, 2);
    int sl = readInt(dlsl << 16, 14, 2);
    sb.append("  DstHostAddr").append(newLine);
    for (int i = 0; i < dl + 1; i++) {
      printLine(sb, b);
    }
    sb.append("  SrcHostAddr").append(newLine);
    for (int i = 0; i < sl + 1; i++) {
      printLine(sb, b);
    }
    sb.append("Path Header").append(newLine);
    int pathHeader = b.getInt();
    b.position(b.position() - 4);
    int segLen0 = readInt(pathHeader, 14, 6);
    int segLen1 = readInt(pathHeader, 20, 6);
    int segLen2 = readInt(pathHeader, 26, 6);
    printLine(sb, b);
    if (segLen0 > 0) {
      sb.append("  SegInfo0").append(newLine);
      printLine(sb, b);
      printLine(sb, b);
    }
    if (segLen1 > 0) {
      sb.append("  SegInfo1").append(newLine);
      printLine(sb, b);
      printLine(sb, b);
    }
    if (segLen2 > 0) {
      sb.append("  SegInfo2").append(newLine);
      printLine(sb, b);
      printLine(sb, b);
    }
    for (int i = 0; i < segLen0 + segLen1 + segLen2; i++) {
      sb.append("  HopField ").append(i).append(newLine);
      printLine(sb, b);
      printLine(sb, b);
      printLine(sb, b);
    }

    return sb.toString();
  }

  private static void printLine(StringBuilder sb, ByteBuffer b) {
    int pos = b.position();
    String newLine = System.lineSeparator();
    sb.append(String.format("%02d", pos))
        .append("-")
        .append(String.format("%02d", pos + 3))
        .append("  ");
    for (int i = 0; i < 4; i++) {
      sb.append(String.format("%02x", Byte.toUnsignedInt(b.get())));
      sb.append(" ");
    }
    sb.append(newLine);
  }
}
