// Copyright 2025 ETH Zurich, Anapaya Systems
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

package org.scion.jpan.ppl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

class HopPredicateTest {

  @Test
  void testNewHopPredicate() {
    HopPredicate hp;
    HopPredicate hpExp;

    // "ISD wildcard":
    hp = HopPredicate.fromString("0");
    hpExp = HopPredicate.create(0, 0, new int[] {0});
    assertEquals(hpExp, hp);

    // "AS, IF wildcard omitted"
    hp = HopPredicate.fromString("1");
    hpExp = HopPredicate.create(1, 0, new int[] {0});
    assertEquals(hpExp, hp);

    // "IF wildcard omitted"
    hp = HopPredicate.fromString("1-0");
    hpExp = HopPredicate.create(1, 0, new int[] {0});
    assertEquals(hpExp, hp);

    // "basic wildcard"
    hp = HopPredicate.fromString("1-0#0");
    hpExp = HopPredicate.create(1, 0, new int[] {0});
    assertEquals(hpExp, hp);

    // "AS wildcard, interface set"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1-0#1"));

    // "ISD wildcard, AS set"
    hp = HopPredicate.fromString("0-1#0");
    hpExp = HopPredicate.create(0, 1, new int[] {0});
    assertEquals(hpExp, hp);

    // "ISD wildcard, AS set, interface set"
    hp = HopPredicate.fromString("0-1#2");
    hpExp = HopPredicate.create(0, 1, new int[] {2});
    assertEquals(hpExp, hp);

    // "ISD wildcard, AS set and interface omitted"
    hp = HopPredicate.fromString("0-1");
    hpExp = HopPredicate.create(0, 1, new int[] {0});
    assertEquals(hpExp, hp);

    // "IF wildcard omitted, AS set"
    hp = HopPredicate.fromString("1-2");
    hpExp = HopPredicate.create(1, 2, new int[] {0});
    assertEquals(hpExp, hp);

    // "two IfIDs"
    hp = HopPredicate.fromString("1-2#3,4");
    hpExp = HopPredicate.create(1, 2, new int[] {3, 4});
    assertEquals(hpExp, hp);

    // "three IfIDs"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1-2#3,4,5"));

    // "bad -"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1-1-0"));

    // "missing AS"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1#2"));

    // "bad #"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1-1#0#"));

    // "bad IF"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1-1#e"));

    // "bad second IF"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1-2#1,3a"));

    // "AS wildcard, second IF defined"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1-0#1,3"));

    // "bad AS"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1-12323433243534#0"));

    // "bad ISD"
    assertThrows(PplException.class, () -> HopPredicate.fromString("1123212-23#0"));
  }

  @Test
  void testHopPredicateString() {
    HopPredicate hp1 = HopPredicate.fromString("1-2#3,4");
    assertEquals("1-0:0:2#3,4", hp1.string());

    HopPredicate hp2 = HopPredicate.fromString("1-0:0:2#3,4");
    assertEquals("1-0:0:2#3,4", hp2.string());
  }

  @Test
  void testJsonConversion() {
    HopPredicate hp;

    // "Normal predicate":
    hp = HopPredicate.create(1, 2, new int[] {1, 2});
    checkConversion(hp, true);

    // "wildcard predicate":
    hp = HopPredicate.create(1, 2, new int[] {0});
    checkConversion(hp, true);

    // "only ifIDs"
    // Result doesn't match in because we change that to the catch-all entry
    HopPredicate hp2 = HopPredicate.create(0, 0, new int[] {0});
    assertThrows(AssertionFailedError.class, () -> checkConversion(hp2, false));
  }

  private void checkConversion(HopPredicate hp, boolean addDefault) {
    PplPathFilter.Builder builder = PplPathFilter.builder();
    builder.addAclEntry(true, hp.string());
    if (addDefault) {
      builder.addAclEntry(true, null);
    }

    PplPathFilter ppf = builder.build();
    PplPathFilter ppf2 = PplPathFilter.fromJson("", ppf.toJson());
    assertEquals(ppf, ppf2);
  }
}
