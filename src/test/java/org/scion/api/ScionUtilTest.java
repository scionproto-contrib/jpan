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

package org.scion.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.scion.ScionUtil;

class ScionUtilTest {

  @Test
  void testParseIA() {
    Assertions.assertEquals(0, ScionUtil.parseIA("0-0"));
    assertEquals(0, ScionUtil.parseIA("0-0:0:0"));
    assertEquals(42L << 48, ScionUtil.parseIA("42-0:0:0"));
    assertEquals(0xfedcL << 32, ScionUtil.parseIA("0-fedc:0:0"));
    assertEquals(0xfedcL << 16, ScionUtil.parseIA("0-0:fedc:0"));
    assertEquals(0xfedcL, ScionUtil.parseIA("0-0:0:fedc"));
    assertEquals(42L << 48 | 0xfedcba987654L, ScionUtil.parseIA("42-fedc:ba98:7654"));
    assertEquals(65000L << 48 | 0xfedcba987654L, ScionUtil.parseIA("65000-fedc:ba98:7654"));
  }

  @Test
  void testToStringIA() {
    assertEquals("0-0:0:0", ScionUtil.toStringIA(0));
    assertEquals("42-0:0:0", ScionUtil.toStringIA(42L << 48));
    assertEquals("0-fedc:0:0", ScionUtil.toStringIA(0xfedcL << 32));
    assertEquals("0-0:fedc:0", ScionUtil.toStringIA(0xfedcL << 16));
    assertEquals("0-0:0:fedc", ScionUtil.toStringIA(0xfedcL));
    assertEquals("42-fedc:ba98:7654", ScionUtil.toStringIA(42L << 48 | 0xfedcba987654L));
    assertEquals("65000-fedc:ba98:7654", ScionUtil.toStringIA(65000L << 48 | 0xfedcba987654L));
  }

  @Test
  void testToStringIA2() {
    assertEquals("0-0:0:0", ScionUtil.toStringIA(0, 0));
    assertEquals("42-0:0:0", ScionUtil.toStringIA(42, 0));
    assertEquals("0-fedc:0:0", ScionUtil.toStringIA(0, 0xfedcL << 32));
    assertEquals("0-0:fedc:0", ScionUtil.toStringIA(0, 0xfedcL << 16));
    assertEquals("0-0:0:fedc", ScionUtil.toStringIA(0, 0xfedcL));
    assertEquals("42-fedc:ba98:7654", ScionUtil.toStringIA(42, 0xfedcba987654L));
    assertEquals("65000-fedc:ba98:7654", ScionUtil.toStringIA(65000, 0xfedcba987654L));
  }

  @Test
  void testToStringIA2_fails() {
    Exception exception;
    exception = assertThrows(IllegalArgumentException.class, () -> ScionUtil.toStringIA(-1, 0));
    assertTrue(exception.getMessage().contains("ISD out of range"));
    exception = assertThrows(IllegalArgumentException.class, () -> ScionUtil.toStringIA(66000, 0));
    assertTrue(exception.getMessage().contains("ISD out of range"));
    exception = assertThrows(IllegalArgumentException.class, () -> ScionUtil.toStringIA(45, -1));
    assertTrue(exception.getMessage().contains("AS out of range"));
    exception =
        assertThrows(
            IllegalArgumentException.class, () -> ScionUtil.toStringIA(45, 0x1FFFFFFFFFFFFL));
    assertTrue(exception.getMessage().contains("AS out of range"));
  }

  @Test
  void testExtractAS() {
    assertEquals(0L, ScionUtil.extractAs(0));
    assertEquals(0L, ScionUtil.extractAs((42L << 48)));
    assertEquals(0xfedcba987654L, ScionUtil.extractAs((42L << 48) + 0xfedcba987654L));
    assertEquals(0xfedcba987654L, ScionUtil.extractAs((65000L << 48) + 0xfedcba987654L));
  }

  @Test
  void testExtractISD() {
    assertEquals(0, ScionUtil.extractIsd(0));
    assertEquals(42, ScionUtil.extractIsd((42L << 48)));
    assertEquals(42, ScionUtil.extractIsd((42L << 48) | 0xfedcba987654L));
    assertEquals(65000, ScionUtil.extractIsd((65000L << 48) | 0xfedcba987654L));
  }
}
