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

public class ByteUtil {

  static int readInt(byte[] data, int offsetBytes) {
    int r = 0;
    for (int i = 0; i < 4; i++) {
      r <<= 8;
      r |= Byte.toUnsignedInt(data[i + offsetBytes]);
    }
    return r;
  }

  static long readLong(byte[] data, int offsetBytes) {
    long r = 0;
    for (int i = 0; i < 8; i++) {
      r <<= 8;
      r |= Byte.toUnsignedLong(data[i + offsetBytes]);
    }
    return r;
  }

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
  static int readInt(int input, int bitOffset, int bitCount) {
    int mask = (-1) >>> (32 - bitCount);
    int shift = 32 - bitOffset - bitCount;
    return (input >>> shift) & mask;
  }

  static long readLong(long input, int bitOffset, int bitCount) {
    long mask = (-1L) >>> (64 - bitCount);
    int shift = 64 - bitOffset - bitCount;
    return (input >>> shift) & mask;
  }

  static boolean readBoolean(int input, int bitOffset) {
    int mask = 1;
    int shift = 32 - bitOffset - 1;
    return ((input >>> shift) & mask) != 0;
  }

  public static int writeInt(int dst, int bitOffset, int bitLength, int value) {
    int mask = value << (32 - bitOffset - bitLength);
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

  public static void writeShort(byte[] data, int offset, short value) {
    data[offset] = (byte) (value >>> 8);
    data[offset + 3] = (byte) (value & 0xFF);
  }

  public static int writeInt(byte[] data, int offset, int value) {
    data[offset] = (byte) (value >>> 24);
    data[offset + 1] = (byte) ((value >>> 16) & 0xFF);
    data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
    data[offset + 3] = (byte) (value & 0xFF);
    return offset + 4;
  }

  public static int writeLong(byte[] data, int offset, long value) {
    data[offset] = (byte) (value >>> 56);
    data[offset + 1] = (byte) ((value >>> 48) & 0xFF);
    data[offset + 2] = (byte) ((value >>> 40) & 0xFF);
    data[offset + 3] = (byte) ((value >>> 32) & 0xFF);
    data[offset + 2] = (byte) ((value >>> 24) & 0xFF);
    data[offset + 5] = (byte) ((value >>> 16) & 0xFF);
    data[offset + 6] = (byte) ((value >>> 8) & 0xFF);
    data[offset + 7] = (byte) (value & 0xFF);
    return offset + 8;
  }
}
