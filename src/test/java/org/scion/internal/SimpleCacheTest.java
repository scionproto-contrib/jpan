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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled
class SimpleCacheTest {

  private static class Pair {
    int i;
    String s;

    Pair(int i, String s) {
      this.i = i;
      this.s = s;
    }
  }

  @Test
  void testInsertOnly() {
    SimpleCache<Integer, Pair> cache = new SimpleCache<>(10);
    List<Pair> list = new ArrayList<>();

    for (int i = 0; i < 30; i++) {
      Pair p = new Pair(i, Integer.toString(i));
      cache.put(i, p);
      list.add(p);
    }

    // Test latest 10 remain
    for (int i = 0; i < list.size(); i++) {
      if (i < 20) {
        assertNull(cache.get(i));
      } else {
        assertEquals(list.get(i), cache.get(i));
      }
    }
  }

  @Test
  void testMultiGet() {
    SimpleCache<Integer, Pair> cache = new SimpleCache<>(10);
    List<Pair> list = new ArrayList<>();

    for (int i = 0; i < 30; i++) {
      Pair p = new Pair(i, Integer.toString(i));
      cache.put(i, p);
      list.add(p);
      // keep using the first 5 entries
      assertNotNull(cache.get(i % 5));
    }

    // Test latest 5 remain and most used 5 remain
    for (int i = 0; i < list.size(); i++) {
      if (i >= 5 && i < 25) {
        assertNull(cache.get(i));
      } else {
        assertEquals(list.get(i), cache.get(i));
      }
    }
  }
}
