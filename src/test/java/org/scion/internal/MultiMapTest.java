// Copyright 2024 ETH Zurich
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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultiMapTest {

  private static class Pair {
    int i;
    String s;

    Pair(int i, String s) {
      this.i = i;
      this.s = s;
    }
  }

  @Test
  void testSmoke() {
    MultiMap<Integer, String> map = new MultiMap<>();
    ArrayList<Pair> list = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      // Use duplicate keys as well as duplicate values
      Pair p = new Pair(i % 10, Integer.toString(i % 100));
      map.put(p.i, p.s);
      list.add(p);
    }

    assertFalse(map.isEmpty());

    for (Pair p : list) {
      assertTrue(map.contains(p.i));
      List<String> entries = map.get(p.i);
      assertEquals(100, entries.size());
      int matches = 0;
      int identity = 0;
      for (String s : entries) {
        assertEquals(p.i, Integer.parseInt(s) % 10);
        if (p.s.equals(s)) {
          matches++;
        }
        if (p.s == s) {
          identity++;
        }
      }
      assertEquals(10, matches);
      assertEquals(1, identity);
    }

    map.clear();
    assertTrue(map.isEmpty());
    assertFalse(map.contains(0));
    assertNotNull(map.get(0));
    assertTrue(map.get(0).isEmpty());
  }

  @Test
  void testEmptyMap() {
    // Check that basic function don´t fail on empty map
    MultiMap<Integer, String> map = new MultiMap<>();
    assertTrue(map.isEmpty());
    // assertEquals(0, map.size());
    assertFalse(map.contains(0));
    assertNotNull(map.get(0));
    assertTrue(map.get(0).isEmpty());
  }
}
