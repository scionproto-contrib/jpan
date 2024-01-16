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

import java.nio.ByteBuffer;

public class ByteUtil {

  /**
   * Reads some bits from an integer and returns them as another integer, shifted right such that
   * the least significant extracted bit becomes the least significant bit in the output.
   *
   * @param input input
   * @param bitOffset First bit to read. 0 is the most significant bit, 31 is the least significant
   *     bit
   * @param bitCount number of bits to read
   * @return extracted bits as int.
   */
  public static int readInt(int input, int bitOffset, int bitCount) {
    int mask = (-1) >>> (32 - bitCount);
    int shift = 32 - bitOffset - bitCount;
    return (input >>> shift) & mask;
  }

  public static long readLong(long input, int bitOffset, int bitCount) {
    long mask = (-1L) >>> (64 - bitCount);
    int shift = 64 - bitOffset - bitCount;
    return (input >>> shift) & mask;
  }

  public static boolean readBoolean(int input, int bitOffset) {
    int mask = 1;
    int shift = 32 - bitOffset - 1;
    return ((input >>> shift) & mask) != 0;
  }

  public static int writeInt(int dst, int bitOffset, int bitLength, int value) {
    int mask = value << (32 - bitOffset - bitLength);
    return dst | mask;
  }

  public static int write16(int dst, int bitOffset, int value) {
    int mask = (value & 0xFFFF) << (32 - bitOffset - 16);
    return dst | mask;
  }

  public static int writeBool(int dst, int bitOffset, boolean value) {
    int mask = value ? 1 << (32 - 1 - bitOffset) : 0;
    return dst | mask;
  }

  public static long writeInt(long dst, int bitOffset, int bitLength, int value) {
    long mask = Integer.toUnsignedLong(value) << (64 - bitOffset - bitLength);
    return dst | mask;
  }

  public static long writeLong(long dst, int bitOffset, int bitLength, long value) {
    long mask = value << (64 - bitOffset - bitLength);
    return dst | mask;
  }

  public static long writeBool(long dst, int bitOffset, boolean value) {
    long mask = value ? 1L << (32 - 1 - bitOffset) : 0L;
    return dst | mask;
  }

  public static String printHeader(ByteBuffer b) {
    String NL = System.lineSeparator();
    StringBuilder sb = new StringBuilder();
    int pos = b.position();
    sb.append("Common Header").append(NL);
    printLine(sb, b);
    printLine(sb, b);
    printLine(sb, b);
    sb.append("Address Header").append(NL);
    printLine(sb, b);
    printLine(sb, b);
    printLine(sb, b);
    printLine(sb, b);
    int dlsl = b.getInt(pos + 9);
    int dl = readInt(dlsl << 16, 10, 2);
    int sl = readInt(dlsl << 16, 14, 2);
    sb.append("  DstHostAddr").append(NL);
    for (int i = 0; i < dl + 1; i++) {
      printLine(sb, b);
    }
    sb.append("  SrcHostAddr").append(NL);
    for (int i = 0; i < sl + 1; i++) {
      printLine(sb, b);
    }
    sb.append("Path Header").append(NL);
    int pathHeader = b.getInt();
    b.position(b.position() - 4);
    int segLen0 = readInt(pathHeader, 14, 6);
    int segLen1 = readInt(pathHeader, 20, 6);
    int segLen2 = readInt(pathHeader, 26, 6);
    printLine(sb, b);
    if (segLen0 > 0) {
      sb.append("  SegInfo0").append(NL);
      printLine(sb, b);
      printLine(sb, b);
    }
    if (segLen1 > 0) {
      sb.append("  SegInfo1").append(NL);
      printLine(sb, b);
      printLine(sb, b);
    }
    if (segLen2 > 0) {
      sb.append("  SegInfo2").append(NL);
      printLine(sb, b);
      printLine(sb, b);
    }
    for (int i = 0; i < segLen0 + segLen1 + segLen2; i++) {
      sb.append("  HopField ").append(i).append(NL);
      printLine(sb, b);
      printLine(sb, b);
      printLine(sb, b);
    }

    return sb.toString();
  }

  private static void printLine(StringBuilder sb, ByteBuffer b) {
    int pos = b.position();
    String NL = System.lineSeparator();
    sb.append(String.format("%02d", pos))
        .append("-")
        .append(String.format("%02d", pos + 3))
        .append("  ");
    for (int i = 0; i < 4; i++) {
      sb.append(String.format("%02x", Byte.toUnsignedInt(b.get())));
      sb.append(" ");
    }
    sb.append(NL);
  }
}
