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

package org.scion.jpan.api;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.internal.ppl.PathProvider;

class PathPolicyTest {

  @Test
  void first() {
    PathProvider pp = new PathProvider();
    List<Path> paths1 = pp.getPaths("2-ff00:0:210", "1-ff00:0:110");
    List<Path> paths4 = pp.getPaths("2-ff00:0:210", "2-ff00:0:212");

//    List<Path> filtered = PathPolicy.FIRST.filter(paths4);
//    assertEquals()

  }
}
