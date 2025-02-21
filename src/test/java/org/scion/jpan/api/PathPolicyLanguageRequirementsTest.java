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

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.ppl.PathProvider;
import org.scion.jpan.ppl.PplPathFilter;
import org.scion.jpan.ppl.PplPolicy;

/** Tests for PPL requirements and ordering. */
class PathPolicyLanguageRequirementsTest {

  private static final PplPathFilter ALLOW = PplPathFilter.builder().addAclEntry("+").build();

  @Test
  void minMetaBandwidth() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    PplPolicy policy = PplPolicy.builder().add("0", ALLOW).minMetaBandwidth(1500).build();
    List<Path> filtered = policy.filter(pathsWithDifferentLengths);
    assertFalse(filtered.isEmpty());
    assertTrue(filtered.size() < pathsWithDifferentLengths.size());
    for (int i = 0; i < filtered.size(); i++) {
      long localBW = Long.MAX_VALUE;
      for (long bw : filtered.get(i).getMetadata().getBandwidthList()) {
        if (bw <= 0) {
          localBW = 0;
          break;
        }
        localBW = Math.min(localBW, bw);
      }

      assertTrue(localBW >= 1500);
    }
  }

  @Test
  void minMTU() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    PplPolicy policy = PplPolicy.builder().add("0", ALLOW).minMtu(1500).build();
    List<Path> filtered = policy.filter(pathsWithDifferentLengths);
    assertFalse(filtered.isEmpty());
    assertTrue(filtered.size() < pathsWithDifferentLengths.size());
    for (int i = 0; i < filtered.size(); i++) {
      int mtu = filtered.get(i).getMetadata().getMtu();
      assertTrue(mtu >= 1500);
    }
  }

  @Test
  void minValidity() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    PplPolicy policy = PplPolicy.builder().add("0", ALLOW).minValidity(1500).build();
    List<Path> filtered = policy.filter(pathsWithDifferentLengths);
    assertFalse(filtered.isEmpty());
    assertTrue(filtered.size() < pathsWithDifferentLengths.size());
    for (int i = 0; i < filtered.size(); i++) {
      long validity =
          filtered.get(i).getMetadata().getExpiration() - System.currentTimeMillis() / 1000;
      assertTrue(validity >= 1500);
    }
  }

  private List<Path> createLongMixedList() {
    PathProvider pp = new PathProvider();
    List<Path> paths1x2 = pp.getPaths("2-ff00:0:210", "1-ff00:0:110");
    List<Path> paths4x4 = pp.getPaths("2-ff00:0:210", "2-ff00:0:212");
    //  List<Path> paths3_4 = pp.getPaths("2-ff00:0:211", "2-ff00:0:220")
    List<Path> paths2x8 = pp.getPaths("1-ff00:0:122", "2-ff00:0:222");
    List<Path> paths4x8 = pp.getPaths("1-ff00:0:122", "2-ff00:0:221");
    List<Path> paths3x4 = pp.getPaths("1-ff00:0:110", "2-ff00:0:220");
    List<Path> pathsWithDifferentLengths = new ArrayList<>();
    pathsWithDifferentLengths.addAll(paths2x8);
    pathsWithDifferentLengths.addAll(paths3x4);
    pathsWithDifferentLengths.addAll(paths1x2);
    pathsWithDifferentLengths.addAll(paths4x8);
    pathsWithDifferentLengths.addAll(paths4x4);
    return pathsWithDifferentLengths;
  }
}
