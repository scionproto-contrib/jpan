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

package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.internal.util.SimpleCache;

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

  @Test
  void testReduceCapacity() {
    SimpleCache<Integer, Pair> cache = new SimpleCache<>(10);
    List<Pair> list = new ArrayList<>();

    for (int i = 0; i < 30; i++) {
      Pair p = new Pair(i, Integer.toString(i));
      cache.put(i, p);
      list.add(p);
      // keep using the first 2 entries
      assertNotNull(cache.get(i % 2));
    }

    cache.setCapacity(5);
    assertEquals(5, cache.getCapacity());

    // Test latest 3 remain and most used 2 remain
    for (int i = 0; i < list.size(); i++) {
      if (i >= 2 && i < 27) {
        assertNull(cache.get(i));
      } else {
        assertEquals(list.get(i), cache.get(i));
      }
    }
  }

  @Test
  void testSetCapacity() {
    SimpleCache<Integer, Pair> cache = new SimpleCache<>(42);
    assertEquals(42, cache.getCapacity());

    cache.setCapacity(100);
    assertEquals(100, cache.getCapacity());

    assertThrows(IllegalArgumentException.class, () -> cache.setCapacity(0));

    assertThrows(IllegalArgumentException.class, () -> new SimpleCache<>(0));
  }

  @Test
  void testClose() {
    ArrayList<AbstractEntry> list = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      list.add(new CloseableEntry(i));
    }

    testCloseOnRemove(list);
  }

  @Test
  void testAutoclose() {
    ArrayList<AbstractEntry> list = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      list.add(new AutoCloseableEntry(i));
    }
    testCloseOnRemove(list);
  }

  private static class AbstractEntry {
    int id;
    boolean isClosed = false;

    AbstractEntry(int id) {
      this.id = id;
    }

    public void close() {
      isClosed = true;
    }
  }

  private static class CloseableEntry extends AbstractEntry implements Closeable {
    CloseableEntry(int id) {
      super(id);
    }
  }

  private static class AutoCloseableEntry extends AbstractEntry implements AutoCloseable {
    AutoCloseableEntry(int id) {
      super(id);
    }
  }

  private void testCloseOnRemove(List<AbstractEntry> list) {
    SimpleCache<Integer, AbstractEntry> sc1 = new SimpleCache<>(5, false);
    for (AbstractEntry e : list) {
      sc1.put(e.id, e);
    }
    for (AbstractEntry abstractEntry : list) {
      assertFalse(abstractEntry.isClosed);
    }

    SimpleCache<Integer, AbstractEntry> sc2 = new SimpleCache<>(5, true);
    for (AbstractEntry e : list) {
      sc2.put(e.id, e);
    }
    for (int i = 0; i < list.size(); i++) {
      if (i < list.size() / 2) {
        assertTrue(list.get(i).isClosed);
      } else {
        assertFalse(list.get(i).isClosed);
      }
    }
  }
}
