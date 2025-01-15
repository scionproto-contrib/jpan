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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;

public class SequenceTest {

  @Test
  void testNewSequence() {
    // error
    assertThrows(PplException.class, () -> Sequence.create("0-0-0#0"));
    // error
    assertThrows(PplException.class, () -> Sequence.create("0#0#0"));
    // no error
    Sequence.create("0");
    // error
    assertThrows(PplException.class, () -> Sequence.create("1#0"));
    // no error
    Sequence.create("1-0");
  }

  @Test
  void testSequenceEval() {
    //        tests := map[string]struct {
    //            Seq        *Sequence
    //            Src        addr.IA
    //            Dst        addr.IA
    //            ExpPathNum int
    //        }{

    //        pp := NewPathProvider(ctrl)
    //        for name, test := range tests {
    //            t.Run(name, func(t *testing.T) {
    //                paths := pp.GetPaths(test.Src, test.Dst)
    //                outPaths := test.Seq.Eval(paths)
    //                assert.Equal(t, test.ExpPathNum, len(outPaths))
    //            })
    //        }

    PathProvider pp = new PathProvider();

    // "Empty path":
    Sequence seq = Sequence.create("0-0#0");
    List<Path> paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:212");
    assertEquals(0, seq.eval(paths).size());

    // "Asterisk matches empty path"
    seq = Sequence.create("0*");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:212");
    assertEquals(1, seq.eval(paths).size());

    // "Asterisk on non-wildcard matches empty path"
    seq = Sequence.create("1-ff00:0:110#1,2*");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:212");
    assertEquals(1, seq.eval(paths).size());

    // "Double Asterisk matches empty path"
    seq = Sequence.create("0* 0*");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:212");
    assertEquals(1, seq.eval(paths).size());

    // "QuestionMark matches empty path"
    seq = Sequence.create("0*");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:212");
    assertEquals(1, seq.eval(paths).size());

    // "Asterisk and QuestionMark matches empty path"
    seq = Sequence.create("0* 0?");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:212");
    assertEquals(1, seq.eval(paths).size());

    // "Plus does not match empty path"
    seq = Sequence.create("0+");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:212");
    assertEquals(0, seq.eval(paths).size());

    // "Length not matching"
    seq = Sequence.create("0-0#0");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(0, seq.eval(paths).size());

    // "Two Wildcard matching"
    seq = Sequence.create("0-0#0 0-0#0");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, seq.eval(paths).size());

    // "Longer Wildcard matching"
    seq = Sequence.create("0-0#0 0-0#0 0-0#0 0-0#0");
    paths = pp.getPaths("1-ff00:0:122", "2-ff00:0:220");
    assertEquals(2, seq.eval(paths).size());

    // "Two Explicit matching"
    seq = Sequence.create("1-ff00:0:133#1019 1-ff00:0:132#1910");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:132");
    assertEquals(1, seq.eval(paths).size());

    // "AS double IF matching"
    seq = Sequence.create("0 1-ff00:0:132#1910,1916 0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:131");
    assertEquals(1, seq.eval(paths).size());

    // "AS IF matching, first wildcard"
    seq = Sequence.create("0 1-ff00:0:132#0,1916 0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:131");
    assertEquals(1, seq.eval(paths).size());

    // "Longer Explicit matching"
    seq =
        Sequence.create(
            "1-ff00:0:122#1815 1-ff00:0:121#1518,1530 1-ff00:0:120#3015,3122 2-ff00:0:220#2231,2224 2-ff00:0:221#2422");
    paths = pp.getPaths("1-ff00:0:122", "2-ff00:0:221");
    assertEquals(1, seq.eval(paths).size());

    // "Longer Explicit matching, single wildcard"
    seq =
        Sequence.create(
            "1-ff00:0:133#1018 1-ff00:0:122#1810,1815 "
                + "1-ff00:0:121#0,1530 1-ff00:0:120#3015,2911 1-ff00:0:110#1129");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:110");
    assertEquals(1, seq.eval(paths).size());

    // "Longer Explicit matching, reverse single wildcard"
    seq =
        Sequence.create(
            "1-ff00:0:133#1018 1-ff00:0:122#1810,1815 "
                + "1-ff00:0:121#1530,0 1-ff00:0:120#3015,2911 1-ff00:0:110#1129");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:110");
    assertEquals(0, seq.eval(paths).size());

    // "Longer Explicit matching, multiple wildcard"
    seq =
        Sequence.create(
            "1-ff00:0:133#1018 1-ff00:0:122#0,1815 "
                + "1-ff00:0:121#0,1530 1-ff00:0:120#3015,0 1-ff00:0:110#1129");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:110");
    assertEquals(1, seq.eval(paths).size());

    // "Longer Explicit matching, mixed wildcard types"
    seq = Sequence.create("1-ff00:0:133#0 1 " + "0-0#0 1-ff00:0:120#0 1-ff00:0:110#1129");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:110");
    assertEquals(1, seq.eval(paths).size());

    // "Longer Explicit matching, mixed wildcard types, two paths"
    seq = Sequence.create("1-ff00:0:133#0 1-0#0 " + "0-0#0 1-0#0 1-ff00:0:110#0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:110");
    assertEquals(2, seq.eval(paths).size());

    // "Nil sequence does not filter"
    seq = Sequence.create(null);
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, seq.eval(paths).size());

    // "Asterisk matches multiple hops"
    seq = Sequence.create("0*");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, seq.eval(paths).size());

    // "Asterisk matches zero hops"
    seq = Sequence.create("0 0 0*");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, seq.eval(paths).size());

    // "Plus matches multiple hops"
    seq = Sequence.create("0+");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, seq.eval(paths).size());

    // "Plus doesn't match zero hops"
    seq = Sequence.create("0 0 0+");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(0, seq.eval(paths).size());

    // "Question mark matches zero hops"
    seq = Sequence.create("0 0 0?");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, seq.eval(paths).size());

    // "Question mark matches one hop"
    seq = Sequence.create("0 0?");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, seq.eval(paths).size());

    // "Question mark doesn't match two hops"
    seq = Sequence.create("0?");
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(0, seq.eval(paths).size());

    // "Successful match on hop count"
    seq = Sequence.create("0 0 0");
    paths = pp.getPaths("2-ff00:0:211", "2-ff00:0:220");
    assertEquals(3, seq.eval(paths).size());

    // "Failed match on hop count"
    seq = Sequence.create("0 0");
    paths = pp.getPaths("2-ff00:0:211", "2-ff00:0:220");
    assertEquals(0, seq.eval(paths).size());

    // "Select one of the intermediate ASes"
    seq = Sequence.create("0 2-ff00:0:221 0");
    paths = pp.getPaths("2-ff00:0:211", "2-ff00:0:220");
    assertEquals(1, seq.eval(paths).size());

    // "Select two alternative intermediate ASes"
    seq = Sequence.create("0 (2-ff00:0:221 | 2-ff00:0:210) 0");
    paths = pp.getPaths("2-ff00:0:211", "2-ff00:0:220");
    assertEquals(3, seq.eval(paths).size());

    // "Alternative intermediate ASes, but one doesn't exist"
    seq = Sequence.create("0 (2-ff00:0:221 |64-12345) 0");
    paths = pp.getPaths("2-ff00:0:211", "2-ff00:0:220");
    assertEquals(1, seq.eval(paths).size());

    // "Or has higher priority than concatenation"
    seq = Sequence.create("0 2-ff00:0:221|64-12345 0");
    paths = pp.getPaths("2-ff00:0:211", "2-ff00:0:220");
    assertEquals(1, seq.eval(paths).size());

    // "Question mark has higher priority than concatenation"
    seq = Sequence.create("0 0 0 ?  ");
    paths = pp.getPaths("2-ff00:0:211", "2-ff00:0:220");
    assertEquals(3, seq.eval(paths).size());

    // "Parentheses change priority"
    seq = Sequence.create("(0 ?)");
    paths = pp.getPaths("2-ff00:0:211", "2-ff00:0:220");
    assertEquals(0, seq.eval(paths).size());

    // "Single interface matches inbound interface"
    seq = Sequence.create("0 1-ff00:0:132#1910 0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:131");
    assertEquals(1, seq.eval(paths).size());

    // "Single interface matches outbound interface"
    seq = Sequence.create("0 1-ff00:0:132#1916 0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:131");
    assertEquals(1, seq.eval(paths).size());

    // "Single non-matching interface"
    seq = Sequence.create("0 1-ff00:0:132#1917 0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:131");
    assertEquals(0, seq.eval(paths).size());

    // "Left interface matches inbound"
    seq = Sequence.create("0 1-ff00:0:132#1910,0 0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:131");
    assertEquals(1, seq.eval(paths).size());

    // "Left interface doesn't match outbound"
    seq = Sequence.create("0 1-ff00:0:132#1916,0 0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:131");
    assertEquals(0, seq.eval(paths).size());

    // "Right interface matches outbound"
    seq = Sequence.create("0 1-ff00:0:132#0,1916 0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:131");
    assertEquals(1, seq.eval(paths).size());

    // "Right interface doesn't match inbound"
    seq = Sequence.create("0 1-ff00:0:132#0,1910 0");
    paths = pp.getPaths("1-ff00:0:133", "1-ff00:0:131");
    assertEquals(0, seq.eval(paths).size());
  }
}
