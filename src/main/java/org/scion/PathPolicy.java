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

package org.scion;

import java.util.*;

public interface PathPolicy {
  PathPolicy FIRST = new First();
  PathPolicy MAX_BANDWIDTH = new MaxBandwith();
  PathPolicy MIN_LATENCY = new MinLatency();
  PathPolicy MIN_HOPS = new MinHopCount();
  PathPolicy DEFAULT = MIN_HOPS;

  class First implements PathPolicy {
    public RequestPath filter(List<RequestPath> paths) {
      return paths.stream().findFirst().orElseThrow(NoSuchElementException::new);
    }
  }

  class MaxBandwith implements PathPolicy {
    public RequestPath filter(List<RequestPath> paths) {
      return paths.stream()
          .max(Comparator.comparing(path -> Collections.min(path.getBandwidthList())))
          .orElseThrow(NoSuchElementException::new);
    }
  }

  class MinLatency implements PathPolicy {
    public RequestPath filter(List<RequestPath> paths) {
      // A 0-value indicates that the AS did not announce a latency for this hop.
      // We use Integer.MAX_VALUE for comparison of these ASes.
      return paths.stream()
          .min(
              Comparator.comparing(
                  path ->
                      path.getLatencyList().stream()
                          .mapToLong(l -> l > 0 ? l : Integer.MAX_VALUE)
                          .reduce(0, Long::sum)))
          .orElseThrow(NoSuchElementException::new);
    }
  }

  class MinHopCount implements PathPolicy {
    public RequestPath filter(List<RequestPath> paths) {
      return paths.stream()
          .min(Comparator.comparing(path -> path.getInternalHopsList().size()))
          .orElseThrow(NoSuchElementException::new);
    }
  }

  class IsdAllow implements PathPolicy {
    private final Set<Integer> allowedIsds;

    public IsdAllow(Set<Integer> allowedIsds) {
      this.allowedIsds = allowedIsds;
    }

    @Override
    public RequestPath filter(List<RequestPath> paths) {
      return paths.stream()
          .filter(this::checkPath)
          .findAny()
          .orElseThrow(NoSuchElementException::new);
    }

    private boolean checkPath(RequestPath path) {
      for (RequestPath.PathInterface pif : path.getInterfacesList()) {
        int isd = (int) (pif.getIsdAs() >>> 48);
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
    public RequestPath filter(List<RequestPath> paths) {
      return paths.stream()
          .filter(this::checkPath)
          .findAny()
          .orElseThrow(NoSuchElementException::new);
    }

    private boolean checkPath(RequestPath path) {
      for (RequestPath.PathInterface pif : path.getInterfacesList()) {
        int isd = (int) (pif.getIsdAs() >>> 48);
        if (disallowedIsds.contains(isd)) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * @param paths A list of candidate paths
   * @return The "best" path according to the filter's policy.
   * @throws NoSuchElementException if no matching path could be found.
   */
  RequestPath filter(List<RequestPath> paths);
}
