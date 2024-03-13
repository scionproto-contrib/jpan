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
    map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
  }

  public List<V> get(K key) {
    ArrayList<V> result = map.get(key);
    return result == null ? Collections.emptyList() : result;
  }

  public boolean contains(K key) {
    return map.containsKey(key);
  }

  public boolean isEmpty() {
    return map.isEmpty();
  }

  public void clear() {
    map.clear();
  }
}
