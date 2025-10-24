// Copyright 2025 ETH Zurich
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

import org.scion.jpan.Path;
import org.scion.jpan.ScionService;

public interface PathProvider {
  Path getPath();

  ScionService getService();

  void close();

  void refresh();

  @FunctionalInterface
  interface Filter {
    boolean accept(Path path);
  }

  @FunctionalInterface
  interface Comparator extends java.util.Comparator<Path> {
    int compare(Path p1, Path p2);
  }
}
