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

import java.util.*;

public class MultiMap<K, V> {
  private final HashMap<K, ArrayList<V>> map = new HashMap<>();

  public void put(K key, V value) {
    ArrayList<V> entry = map.get(key);
    if (entry == null) {
      entry = new ArrayList<>();
      map.put(key, entry);
    }
    entry.add(value);
  }

  public ArrayList<V> get(K key) {
    return map.get(key);
  }

  public boolean contains(K key) {
    return map.containsKey(key);
  }

  public Set<Map.Entry<K, ArrayList<V>>> entrySet() {
    return map.entrySet();
  }

  public Collection<ArrayList<V>> values() {
    return map.values();
  }

  public boolean isEmpty() {
    return map.isEmpty();
  }

  public void clear() {
    map.clear();
  }
}
