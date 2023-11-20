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

public class ByteUtilTest {

  @Test
  public void testWriteIntToByteArray() {
    int i0 = 0xFFFFFFFF;
    byte[] data = new byte[4];
    ByteUtil.writeInt(data, 0, i0);
    assertArrayEquals(new byte[] {-1, -1, -1, -1}, data);
  }

  @Test
  public void testWriteLongToByteArray() {
    long l0 = 0xFFFFFFFFFFFFFFFFL;
    byte[] data = new byte[8];
    ByteUtil.writeLong(data, 0, l0);
    assertArrayEquals(new byte[] {-1, -1, -1, -1, -1, -1, -1, -1}, data);
  }
}
