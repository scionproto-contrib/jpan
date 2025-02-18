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
class PathPolicyLanguageOrderingTest {

  private static final PplPathFilter ALLOW = PplPathFilter.builder().addAclEntry("+").build();

  @Test
  void errorOrdering_label() {
    PplPolicy.Builder pb = PplPolicy.builder().add("0", ALLOW).ordering("shops_asc");
    Exception e = assertThrows(IllegalArgumentException.class, pb::build);
    assertTrue(e.getMessage().contains("PPL: unknown ordering: "));
  }

  @Test
  void errorOrdering_labels() {
    PplPolicy.Builder pb = PplPolicy.builder().add("0", ALLOW).ordering("hops_asc,meta_asc");
    Exception e = assertThrows(IllegalArgumentException.class, pb::build);
    assertTrue(e.getMessage().contains("PPL: unknown ordering: "));
  }

  @Test
  void hopsAsc() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    PplPolicy policy = PplPolicy.builder().add("0", ALLOW).ordering("hops_asc").build();
    List<Path> filtered = policy.filter(pathsWithDifferentLengths);
    assertEquals(2, filtered.get(0).getMetadata().getInterfacesList().size());
    int prevHops = 2;
    for (int i = 0; i < pathsWithDifferentLengths.size(); i++) {
      int nHops = filtered.get(i).getMetadata().getInterfacesList().size();
      assertTrue(nHops >= prevHops);
      prevHops = nHops;
    }
    assertEquals(8, prevHops);
  }

  @Test
  void hopsDesc() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    PplPolicy policy = PplPolicy.builder().add("0", ALLOW).ordering("hops_desc").build();
    List<Path> filtered = policy.filter(pathsWithDifferentLengths);
    assertEquals(8, filtered.get(0).getMetadata().getInterfacesList().size());
    int prevHops = 20000;
    for (int i = 0; i < pathsWithDifferentLengths.size(); i++) {
      int nHops = filtered.get(i).getMetadata().getInterfacesList().size();
      assertTrue(nHops <= prevHops);
      prevHops = nHops;
    }
    assertEquals(2, prevHops);
  }

  @Test
  void metaLatencyAsc() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    PplPolicy policy = PplPolicy.builder().add("0", ALLOW).ordering("meta_latency_asc").build();
    List<Path> filtered = policy.filter(pathsWithDifferentLengths);
    int prevLatency = 0;
    for (int i = 0; i < pathsWithDifferentLengths.size(); i++) {
      int localMin = 0;
      for (Integer lat : filtered.get(i).getMetadata().getLatencyList()) {
        if (lat >= 0) {
          localMin += lat;
        } else {
          localMin = Integer.MAX_VALUE;
          break;
        }
      }
      if (filtered.get(i).getMetadata().getLatencyList().isEmpty()) {
        localMin = Integer.MAX_VALUE;
      }

      assertTrue(localMin >= prevLatency, localMin + " vs " + prevLatency);
      prevLatency = localMin;
    }
  }

  @Test
  void metaBandwidthDesc() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    PplPolicy policy = PplPolicy.builder().add("0", ALLOW).ordering("meta_bandwidth_desc").build();
    List<Path> filtered = policy.filter(pathsWithDifferentLengths);
    long prevBW = Long.MAX_VALUE;
    for (int i = 0; i < pathsWithDifferentLengths.size(); i++) {
      long localMax = Long.MAX_VALUE;
      for (long bw : filtered.get(i).getMetadata().getBandwidthList()) {
        if (bw <= 0) {
          localMax = 0;
          break;
        }
        localMax = Math.min(localMax, bw);
      }

      assertTrue(localMax <= prevBW);
      prevBW = localMax;
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
