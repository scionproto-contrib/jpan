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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ByteUtilTest {

  @Test
  void testReadInt() {
    int i0 = 0xFEDCBA98;
    int i = ByteUtil.readInt(i0, 8, 8);
    assertEquals(0xDC, i);
  }

  @Test
  void testWriteToInt() {
    int i0 = 0;
    i0 = ByteUtil.writeBool(i0, 7, true);
    assertEquals(0x01000000, i0, Integer.toBinaryString(i0));
    i0 = ByteUtil.writeInt(i0, 16, 8, 0x23);
    assertEquals(0x01002300, i0, Integer.toHexString(i0));
  }

  @Test
  void testWriteToLong() {
    long l0 = ByteUtil.writeInt(0L, 16, 16, 0x8585);
    assertEquals(0x0000858500000000L, l0, Long.toHexString(l0));

    long l1 = ByteUtil.writeLong(0L, 16, 48, 0xF1E2D3C4B5A6L);
    assertEquals(0x0000F1E2D3C4B5A6L, l1, Long.toHexString(l1));
  }
}
