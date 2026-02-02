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

package org.scion.jpan.internal.util;

import java.util.HashMap;
import java.util.TreeMap;

/**
 * A simple cache that min/max watermark. Once max watermark is reached, the cache removes entries
 * until min watermark is reached. Least recently accessed entries are removed first.
 *
 * @param <K> The key
 * @param <V> The value
 */
public class SimpleCache<K, V> {
  private final TreeMap<Long, Entry> ageMap = new TreeMap<>();
  private final HashMap<K, Entry> lookupMap = new HashMap<>();

  private int capacity;
  private long opCount = 0;

  public SimpleCache(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException();
    }
    this.capacity = capacity;
  }

  public void put(K key, V value) {
    Entry e = lookupMap.get(key);
    if (e == null) {
      e = new Entry(opCount++, key, value);
      checkCapacity(1);
      lookupMap.put(key, e);
    } else {
      ageMap.remove(e.age);
      e.age = opCount++;
      e.value = value;
    }
    ageMap.put(e.age, e);
  }

  public V get(K key) {
    Entry e = lookupMap.get(key);
    if (e == null) {
      return null;
    }

    ageMap.remove(e.age);
    e.age = opCount++;
    ageMap.put(e.age, e);

    return e.value;
  }

  public void clear() {
    lookupMap.clear();
    ageMap.clear();
  }

  public void setCapacity(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException();
    }
    this.capacity = capacity;
    checkCapacity(0);
  }

  public int getCapacity() {
    return capacity;
  }

  private void checkCapacity(int spare) {
    while (lookupMap.size() + spare > capacity) {
      Entry e = ageMap.pollFirstEntry().getValue();
      lookupMap.remove(e.key);
    }
  }

  private class Entry {
    private long age;
    private final K key;
    private V value;

    public Entry(long l, K key, V value) {
      this.age = l;
      this.key = key;
      this.value = value;
    }
  }
}
