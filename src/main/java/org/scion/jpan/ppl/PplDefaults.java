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

package org.scion.jpan.ppl;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;

class PplDefaults {
  private final int minMtu;
  private final long minBandwidthBPS;
  private final int minValiditySec;
  private final String[] orderingStr;
  private final Comparator<Path> ordering;

  public PplDefaults(
      int minMtuBytes, long minBandwidthBytesPerSeconds, int minValiditySeconds, String ordering) {
    this.minMtu = minMtuBytes;
    this.minBandwidthBPS = minBandwidthBytesPerSeconds;
    this.minValiditySec = minValiditySeconds;
    this.orderingStr = ordering == null ? new String[0] : ordering.split(",");
    this.ordering = buildComparator(orderingStr);
  }

  public List<Path> filter(List<Path> paths, PplDefaults global) {
    ArrayList<Path> filtered = new ArrayList<>();
    long now = System.currentTimeMillis() / 1000; // unix epoch
    for (Path path : paths) {
      PathMetadata meta = path.getMetadata();
      boolean pass = true;

      if (minMtu > 0) {
        pass &= meta.getMtu() >= minMtu;
      } else if (global != null && global.minMtu > 0) {
        pass &= meta.getMtu() >= global.minMtu;
      }

      if (minValiditySec > 0) {
        pass &= meta.getExpiration() >= now + minValiditySec;
      } else if (global != null && global.minValiditySec > 0) {
        pass &= meta.getExpiration() >= now + global.minValiditySec;
      }

      if (minBandwidthBPS > 0) {
        pass &= getMinBandwidth(path) >= minBandwidthBPS;
      } else if (global != null && global.minBandwidthBPS > 0) {
        pass &= getMinBandwidth(path) >= global.minBandwidthBPS;
      }

      if (pass) {
        filtered.add(path);
      }
    }
    return filtered;
  }

  public void sortPaths(List<Path> filtered) {
    if (ordering != null) {
      filtered.sort(ordering);
    }
  }

  private long getMinBandwidth(Path path) {
    long minBandwidth = Long.MAX_VALUE;
    for (long bandwidth : path.getMetadata().getBandwidths()) {
      if (bandwidth < minBandwidth) {
        minBandwidth = bandwidth;
      }
    }
    return minBandwidth;
  }

  private Comparator<Path> buildComparator(String[] orderings) {
    if (orderings.length == 0) {
      return null;
    }
    Comparator<Path> comparator = getPathComparator(orderings[orderings.length - 1]);

    for (int i = orderings.length - 2; i >= 0; i--) {
      comparator = new ChainableComparator(getPathComparator(orderings[i]), comparator);
    }
    return comparator;
  }

  private static class ChainableComparator implements Comparator<Path> {
    private final Comparator<Path> primary;
    private final Comparator<Path> secondary;

    public ChainableComparator(Comparator<Path> primary, Comparator<Path> secondary) {
      this.primary = primary;
      this.secondary = secondary;
    }

    @Override
    public int compare(Path p1, Path p2) {
      int res = primary.compare(p1, p2);
      return res != 0 ? res : secondary.compare(p1, p2);
    }
  }

  private Comparator<Path> getPathComparator(String ordering) {
    switch (ordering) {
      case "hops_asc":
        return Comparator.comparingInt(p -> p.getMetadata().getInterfaces().size());
      case "hops_desc":
        return (p1, p2) ->
            Integer.compare(
                p2.getMetadata().getInterfaces().size(), p1.getMetadata().getInterfaces().size());
      case "meta_bandwidth_desc":
        // Unknown bw is treated as 0. Empty path is treated as MAX bandwidth
        return (p1, p2) ->
            Long.compare(
                p2.getMetadata().getBandwidths().stream()
                    .mapToLong(Long::longValue)
                    .min()
                    .orElse(Long.MAX_VALUE),
                p1.getMetadata().getBandwidths().stream()
                    .mapToLong(Long::longValue)
                    .min()
                    .orElse(Long.MAX_VALUE));
      case "meta_latency_asc":
        // -1 is mapped to 10000 to ensure that paths with missing latencies are sorted last
        return Comparator.comparingInt(
            p -> p.getMetadata().getLatencies().stream().mapToInt(l -> l < 0 ? 10000 : l).sum());
      default:
        throw new IllegalArgumentException("PPL: unknown ordering: " + ordering);
    }
  }

  void toJson(JsonObject defaultsObj) {
    // requirements
    if (minBandwidthBPS > 0) {
      defaultsObj.addProperty("min_meta_bandwidth", minBandwidthBPS);
    }
    if (minMtu > 0) {
      defaultsObj.addProperty("min_mtu", minMtu);
    }
    if (minValiditySec > 0) {
      defaultsObj.addProperty("min_validity_sec", minValiditySec);
    }

    // ordering
    if (orderingStr.length > 0) {
      StringBuilder oStr = new StringBuilder(orderingStr[0]);
      for (int i = 1; i < orderingStr.length; i++) {
        oStr.append(",").append(orderingStr[i]);
      }
      defaultsObj.addProperty("ordering", oStr.toString());
    }
  }

  static class Builder {
    // We create Filters _before_ adding them to the Policy and before/without having defaults.
    // Defaults can be created and applied later! To keep everything constant/final, the filter()
    // function needs to dynamically detect whether requirements/ordering are present or should be
    // taken from the defaults.
    private int minMtuBytes = 0;
    private long minBandwidthBytesPerSeconds = 0;
    private int minValiditySeconds = 0;
    private String ordering = null;

    /**
     * Minimum metadata bandwidth requirement for paths. Default is 0.
     *
     * @param minBandwidthBytesPerSeconds Minimum bandwidth in bytes per second.
     * @return this Builder
     */
    public Builder minMetaBandwidth(long minBandwidthBytesPerSeconds) {
      this.minBandwidthBytesPerSeconds = minBandwidthBytesPerSeconds;
      return this;
    }

    /**
     * Minimum MTU requirement for paths. Default is 0.
     *
     * @param minMtuBytes Minimum MTU bytes required for a path to be accepted.
     * @return this builder
     */
    public Builder minMtu(int minMtuBytes) {
      this.minMtuBytes = minMtuBytes;
      return this;
    }

    /**
     * Minimum validity requirement for paths. Default is 0.
     *
     * @param minValiditySeconds Minimum seconds before a path expires.
     * @return this Builder
     */
    public Builder minValidity(int minValiditySeconds) {
      this.minValiditySeconds = minValiditySeconds;
      return this;
    }

    public Builder ordering(String ordering) {
      this.ordering = ordering;
      return this;
    }

    public PplDefaults build() {
      return new PplDefaults(
          minMtuBytes, minBandwidthBytesPerSeconds, minValiditySeconds, ordering);
    }
  }
}
