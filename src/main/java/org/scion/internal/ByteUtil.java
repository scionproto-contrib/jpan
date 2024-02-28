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

  /** Mutable long integer. */
  public static class MutLong {
    public long v;

    MutLong(long v) {
      this.v = v;
    }

    public long get() {
      return v;
    }

    public void set(long l) {
      this.v = l;
    }
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

  public static byte toByte(int code) {
    return (byte) (code <= 127 ? code : code - 256);
  }

  public static short toShort(long code) {
    return (short) (code <= Short.MAX_VALUE ? code : (code - (1 << Short.SIZE)));
  }

  public static int toInt(long code) {
    return (int) (code <= Integer.MAX_VALUE ? code : (code - (1L << Integer.SIZE)));
  }

  public static int toUnsigned(byte code) {
    return code >= 0 ? code : ((int) code) + (1 << 8);
  }

  public static int toUnsigned(short code) {
    return code >= 0 ? code : ((int) code) + (1 << 16);
  }
}
