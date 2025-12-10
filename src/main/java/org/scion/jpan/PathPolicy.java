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

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

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
public interface PathPolicy {
  PathPolicy FIRST = new First();
  PathPolicy MAX_BANDWIDTH = new MaxBandwith();
  PathPolicy MIN_LATENCY = new MinLatency();
  PathPolicy MIN_HOPS = new MinHopCount();
  PathPolicy DEFAULT = MIN_HOPS;

  /**
   * Simple filter that will do nothing, effectively giving the same order, and the same first path,
   * as the path source.
   */
  class First implements PathPolicy {
    public List<Path> filter(List<Path> paths) {
      return paths;
    }
  }

  class MaxBandwith implements PathPolicy {
    public List<Path> filter(List<Path> paths) {
      List<Path> result = new ArrayList<>(paths);
      result.sort(
          (p1, p2) -> {
            int bw1 = Collections.min(p1.getMetadata().getBandwidthList()).intValue();
            int bw2 = Collections.min(p2.getMetadata().getBandwidthList()).intValue();
            return Integer.compare(bw2, bw1);
          });
      return result;
    }
  }

  class MinLatency implements PathPolicy {
    public List<Path> filter(List<Path> paths) {
      // A 0-value indicates that the AS did not announce a latency for this hop.
      // We use Integer.MAX_VALUE for comparison of these ASes.
      return paths.stream()
          .sorted(
              Comparator.comparing(
                  path ->
                      path.getMetadata().getLatencyList().stream()
                          .mapToLong(l -> l >= 0 ? l : Integer.MAX_VALUE)
                          .reduce(0, Long::sum)))
          .collect(Collectors.toList());
    }
  }

  class MinHopCount implements PathPolicy {
    public List<Path> filter(List<Path> paths) {
      return paths.stream()
          .sorted(Comparator.comparing(path -> path.getMetadata().getInterfacesList().size()))
          .collect(Collectors.toList());
    }
  }

  class IsdAllow implements PathPolicy {
    private final Set<Integer> allowedIsds;

    public IsdAllow(Set<Integer> allowedIsds) {
      this.allowedIsds = allowedIsds;
    }

    @Override
    public List<Path> filter(List<Path> paths) {
      return paths.stream().filter(this::checkPath).collect(Collectors.toList());
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

  class IsdDisallow implements PathPolicy {
    private final Set<Integer> disallowedIsds;

    public IsdDisallow(Set<Integer> disallowedIsds) {
      this.disallowedIsds = disallowedIsds;
    }

    @Override
    public List<Path> filter(List<Path> paths) {
      return paths.stream().filter(this::checkPath).collect(Collectors.toList());
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
   * This policy allows only paths that use links identical to the reference path. This can be
   * useful to ensure that a path does not change when refreshed.
   *
   * @see ScionDatagramChannel#send(ByteBuffer, SocketAddress)
   */
  class SameLink implements PathPolicy {
    private final List<PathMetadata.PathInterface> reference;

    public SameLink(Path reference) {
      this.reference = reference.getMetadata().getInterfacesList();
    }

    @Override
    public List<Path> filter(List<Path> paths) {
      return paths.stream().filter(this::checkPath).collect(Collectors.toList());
    }

    private boolean checkPath(Path path) {
      List<PathMetadata.PathInterface> ifs = path.getMetadata().getInterfacesList();
      if (ifs.size() != reference.size()) {
        return false;
      }
      for (int i = 0; i < ifs.size(); i++) {
        // In theory, we could compare only the first ISD/AS and then only Interface IDs....
        PathMetadata.PathInterface if1 = ifs.get(i);
        PathMetadata.PathInterface if2 = reference.get(i);
        if (if1.getIsdAs() != if2.getIsdAs() || if1.getId() != if2.getId()) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * @param paths A list of candidate paths
   * @return A list of paths ordered by preference (most preferred first).
   * @throws NoSuchElementException if no matching path could be found.
   */
  List<Path> filter(List<Path> paths);
}
