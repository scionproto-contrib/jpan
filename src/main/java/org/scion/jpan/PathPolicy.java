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

package org.scion.jpan;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Path policy interface.
 *
 * <p>Contract:<br>
 * - The list of paths returned by filter may be ordered by preference (most preferred first).<br>
 * - The filter method must not modify the input list of paths (TBD).<br>
 * - The filter method must not keep a reference to the returned list. The returned list may be
 * modified by the caller.<br>
 * - The filter method must not return a list containing paths with null metadata.<br>
 */
@FunctionalInterface
public interface PathPolicy {
  PathPolicy FIRST = new First();
  PathPolicy MAX_BANDWIDTH = new MaxBandwith();
  PathPolicy MIN_LATENCY = new MinLatency();
  PathPolicy MIN_HOPS = new MinHopCount();
  PathPolicy DEFAULT = MIN_HOPS;

  interface PathPolicyStream extends PathPolicy {
    default List<Path> filter(List<Path> paths) {
      return filter(paths.stream()).collect(Collectors.toList());
    }
  }

  class First implements PathPolicyStream {
    public Stream<Path> filter(Stream<Path> paths) {
      return paths;
    }
  }

  class MaxBandwith implements PathPolicyStream {
    public Stream<Path> filter(Stream<Path> paths) {
      return paths.sorted(
          (p1, p2) -> {
            int bw1 = Collections.min(p1.getMetadata().getBandwidthList()).intValue();
            int bw2 = Collections.min(p2.getMetadata().getBandwidthList()).intValue();
            return Integer.compare(bw2, bw1);
          });
    }
  }

  class MinLatency implements PathPolicyStream {
    public Stream<Path> filter(Stream<Path> paths) {
      // A 0-value indicates that the AS did not announce a latency for this hop.
      // We use Integer.MAX_VALUE for comparison of these ASes.
      return paths
          .sorted(
              Comparator.comparing(
                  path ->
                      path.getMetadata().getLatencyList().stream()
                          .mapToLong(l -> l >= 0 ? l : Integer.MAX_VALUE)
                          .reduce(0, Long::sum)));
    }
  }

  class MinHopCount implements PathPolicyStream {
    public Stream<Path> filter(Stream<Path> paths) {
      return paths
          .sorted(Comparator.comparing(path -> path.getMetadata().getInterfacesList().size()));
    }
  }

  class IsdAllow implements PathPolicyStream {
    private final Set<Integer> allowedIsds;

    public IsdAllow(Set<Integer> allowedIsds) {
      this.allowedIsds = allowedIsds;
    }

    @Override
    public Stream<Path> filter(Stream<Path> paths) {
      return paths.filter(this::checkPath);
    }

    private boolean checkPath(Path path) {
      for (PathMetadata.PathInterface pif : path.getMetadata().getInterfacesList()) {
        int isd = ScionUtil.extractIsd(pif.getIsdAs());
        if (!allowedIsds.contains(isd)) {
          return false;
        }
      }
      return true;
    }
  }

  class IsdDisallow implements PathPolicyStream {
    private final Set<Integer> disallowedIsds;

    public IsdDisallow(Set<Integer> disallowedIsds) {
      this.disallowedIsds = disallowedIsds;
    }

    @Override
    public Stream<Path> filter(Stream<Path> paths) {
      return paths.filter(this::checkPath);
    }

    private boolean checkPath(Path path) {
      for (PathMetadata.PathInterface pif : path.getMetadata().getInterfacesList()) {
        int isd = ScionUtil.extractIsd(pif.getIsdAs());
        if (disallowedIsds.contains(isd)) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * @param paths A list of candidate paths
   * @return A list of path ordered by preference (most preferec first).
   * @throws NoSuchElementException if no matching path could be found.
   */
  List<Path> filter(List<Path> paths);

  default Stream<Path> filter(Stream<Path> paths) {
    return filter(paths.collect(Collectors.toList())).stream();
  }
}
