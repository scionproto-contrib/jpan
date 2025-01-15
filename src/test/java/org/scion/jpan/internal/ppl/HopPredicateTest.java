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

package org.scion.jpan.internal.ppl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class HopPredicateTest {

  @Test
  void testNewHopPredicate() {
    HopPredicate hp;
    HopPredicate hpExp;

    // "ISD wildcard":
    hp = HopPredicate.HopPredicateFromString("0");
    hpExp = HopPredicate.create(0, 0, new int[] {0});
    assertEquals(hpExp, hp);

    // "AS, IF wildcard omitted"
    hp = HopPredicate.HopPredicateFromString("1");
    hpExp = HopPredicate.create(1, 0, new int[] {0});
    assertEquals(hpExp, hp);

    // "IF wildcard omitted"
    hp = HopPredicate.HopPredicateFromString("1-0");
    hpExp = HopPredicate.create(1, 0, new int[] {0});
    assertEquals(hpExp, hp);

    // "basic wildcard"
    hp = HopPredicate.HopPredicateFromString("1-0#0");
    hpExp = HopPredicate.create(1, 0, new int[] {0});
    assertEquals(hpExp, hp);

    // "AS wildcard, interface set"
    assertThrows(PplException.class, () -> HopPredicate.HopPredicateFromString("1-0#1"));

    // "ISD wildcard, AS set"
    hp = HopPredicate.HopPredicateFromString("0-1#0");
    hpExp = HopPredicate.create(0, 1, new int[] {0});
    assertEquals(hpExp, hp);

    // "ISD wildcard, AS set, interface set"
    hp = HopPredicate.HopPredicateFromString("0-1#2");
    hpExp = HopPredicate.create(0, 1, new int[] {2});
    assertEquals(hpExp, hp);

    // "ISD wildcard, AS set and interface omitted"
    hp = HopPredicate.HopPredicateFromString("0-1");
    hpExp = HopPredicate.create(0, 1, new int[] {0});
    assertEquals(hpExp, hp);

    // "IF wildcard omitted, AS set"
    hp = HopPredicate.HopPredicateFromString("1-2");
    hpExp = HopPredicate.create(1, 2, new int[] {0});
    assertEquals(hpExp, hp);

    // "two IfIDs"
    hp = HopPredicate.HopPredicateFromString("1-2#3,4");
    hpExp = HopPredicate.create(1, 2, new int[] {3, 4});
    assertEquals(hpExp, hp);

    // "three IfIDs"
    assertThrows(PplException.class, () -> HopPredicate.HopPredicateFromString("1-2#3,4,5"));

    // "bad -"
    assertThrows(PplException.class, () -> HopPredicate.HopPredicateFromString("1-1-0"));

    // "missing AS"
    assertThrows(PplException.class, () -> HopPredicate.HopPredicateFromString("1#2"));

    // "bad #"
    assertThrows(PplException.class, () -> HopPredicate.HopPredicateFromString("1-1#0#"));

    // "bad IF"
    assertThrows(PplException.class, () -> HopPredicate.HopPredicateFromString("1-1#e"));

    // "bad second IF"
    assertThrows(PplException.class, () -> HopPredicate.HopPredicateFromString("1-2#1,3a"));

    // "AS wildcard, second IF defined"
    assertThrows(PplException.class, () -> HopPredicate.HopPredicateFromString("1-0#1,3"));

    // "bad AS"
    assertThrows(
        PplException.class, () -> HopPredicate.HopPredicateFromString("1-12323433243534#0"));

    // "bad ISD"
    assertThrows(PplException.class, () -> HopPredicate.HopPredicateFromString("1123212-23#0"));
  }

  @Test
  void TestHopPredicateString() {
    HopPredicate hp = HopPredicate.HopPredicateFromString("1-2#3,4");
    assertEquals("1-2#3,4", hp.string());
  }

  @Test
  void TestJsonConversion() {
    //        tests := map[string]struct {
    //            Name string
    //            HP   *HopPredicate
    //        }{
    //            "Normal predicate": {
    //                HP: &HopPredicate{ISD: 1, AS: 2, IfIDs: []iface.ID{1, 2}},
    //            },
    //            "wildcard predicate": {
    //                HP: &HopPredicate{ISD: 1, AS: 2, IfIDs: []iface.ID{0}},
    //            },
    //            "only ifIDs": {
    //                HP: &HopPredicate{IfIDs: []iface.ID{0}},
    //            },
    //        }
    //        for name, test := range tests {
    //            t.Run(name, func(t *testing.T) {
    //                jsonHP, err := json.Marshal(test.HP)
    //                if assert.NoError(t, err) {
    //                    var hp HopPredicate
    //                            err = json.Unmarshal(jsonHP, &hp)
    //                    assert.NoError(t, err)
    //                    assert.Equal(t, test.HP, &hp)
    //                }
    //            })
  }
}
