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
import org.scion.jpan.internal.ppl.PathProvider;

class PathPolicyTest {

  @Test
  void first() {
    List<Path> paths = createLongMixedList();
    List<Path> filtered = PathPolicy.FIRST.filter(paths);
    for (int i = 0; i < 4; i++) {
      assertEquals(paths.get(i), filtered.get(i));
    }
  }

  @Test
  void minHops() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    List<Path> filtered = PathPolicy.MIN_HOPS.filter(pathsWithDifferentLengths);
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
  void minLatency() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    List<Path> filtered = PathPolicy.MIN_LATENCY.filter(pathsWithDifferentLengths);
    int prevLatency = 1;
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

      assertTrue(localMin >= prevLatency);
      prevLatency = localMin;
    }
  }

  @Test
  void maxBandwidth() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    List<Path> filtered = PathPolicy.MAX_BANDWIDTH.filter(pathsWithDifferentLengths);
    long prevBW = Long.MAX_VALUE;
    for (int i = 0; i < pathsWithDifferentLengths.size(); i++) {
      long localMax = Long.MAX_VALUE;
      for (Long bw : filtered.get(i).getMetadata().getBandwidthList()) {
        if (bw <= 0) {
          localMax = 0;
          break;
        }
        localMax = Math.min(localMax, bw);
      }
      if (localMax == 0) {
        localMax = 0;
      }

      assertTrue(localMax <= prevBW);
      prevBW = localMax;
    }
  }

  @Test
  void isdAllow() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    Set<Integer> allowedIsds = new HashSet<>();
    allowedIsds.add(2);
    PathPolicy.IsdAllow isdAllow = new PathPolicy.IsdAllow(allowedIsds);
    List<Path> filtered = isdAllow.filter(pathsWithDifferentLengths);
    assertEquals(4, filtered.size());
  }

  @Test
  void isdDisallow() {
    List<Path> pathsWithDifferentLengths = createLongMixedList();
    Set<Integer> disallowedIsds = new HashSet<>();
    disallowedIsds.add(1);
    PathPolicy.IsdDisallow isdDisallow = new PathPolicy.IsdDisallow(disallowedIsds);
    List<Path> filtered = isdDisallow.filter(pathsWithDifferentLengths);
    assertEquals(4, filtered.size());
  }

  private List<Path> createLongMixedList() {
    PathProvider pp = new PathProvider();
    List<Path> paths1x2 = pp.getPaths("2-ff00:0:210", "1-ff00:0:110");
    List<Path> paths4x4 = pp.getPaths("2-ff00:0:210", "2-ff00:0:212");
    //  List<Path> paths3_4 = pp.getPaths("2-ff00:0:211", "2-ff00:0:220");
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
