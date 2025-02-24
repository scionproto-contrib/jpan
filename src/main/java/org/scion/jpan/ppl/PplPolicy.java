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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Stream;
import org.scion.jpan.*;
import org.scion.jpan.internal.IPHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PplPolicy implements PathPolicy {

  private static final Logger log = LoggerFactory.getLogger(PplPolicy.class);
  private final List<Entry> policies;
  private final PplDefaults defaults;

  private PplPolicy(List<Entry> policies, PplDefaults defaults) {
    this.policies = policies;
    this.defaults = defaults;
  }

  @Override
  public List<Path> filter(List<Path> input) {
    if (input.isEmpty()) {
      return Collections.emptyList();
    }

    List<Path> filtered = new ArrayList<>(input);

    // We assume that all paths have the same destination
    ScionSocketAddress destination = filtered.get(0).getRemoteSocketAddress();
    for (Entry entry : policies) {
      if (entry.isMatch(destination)) {
        filtered = entry.policy.filter(filtered, this.defaults);
        break;
      }
    }

    defaults.sortPaths(filtered);
    return filtered;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static PplPolicy fromJson(java.nio.file.Path file) {
    StringBuilder contentBuilder = new StringBuilder();
    try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
      stream.forEach(s -> contentBuilder.append(s).append("\n"));
    } catch (IOException e) {
      throw new ScionRuntimeException("Error reading topology file: " + file.toAbsolutePath(), e);
    }
    return fromJson(contentBuilder.toString());
  }

  public static PplPolicy fromJson(String jsonFile) {
    try {
      JsonElement jsonTree = JsonParser.parseString(jsonFile);
      if (!jsonTree.isJsonObject()) {
        throw new IllegalArgumentException("Bad file format: " + jsonFile);
      }
      JsonObject parentSet = jsonTree.getAsJsonObject();

      // Ordering and Requirements
      Builder defaultsBuilder = new Builder();
      JsonElement defaultsElement = parentSet.get("defaults");
      if (defaultsElement != null) {
        for (Map.Entry<String, JsonElement> p : defaultsElement.getAsJsonObject().entrySet()) {
          switch (p.getKey()) {
            case "min_mtu":
              defaultsBuilder.minMtu(p.getValue().getAsInt());
              break;
            case "min_validity_sec":
              defaultsBuilder.minValidity(p.getValue().getAsInt());
              break;
            case "min_meta_bandwidth":
              defaultsBuilder.minMetaBandwidth(p.getValue().getAsLong());
              break;
            case "ordering":
              defaultsBuilder.ordering(p.getValue().getAsString());
              break;
            default:
              log.warn("Unknown key in \"defaults\": {}", p.getKey());
          }
        }
      }

      // Filters
      Map<String, PplPathFilter> policies = new HashMap<>();
      JsonObject filterSet = parentSet.get("filters").getAsJsonObject();
      for (Map.Entry<String, JsonElement> p : filterSet.entrySet()) {
        PplPathFilter pf = PplPathFilter.fromJson(p.getKey(), p.getValue().getAsJsonObject());
        policies.put(p.getKey(), pf);
      }

      // Destinations
      JsonObject destinationSet = parentSet.get("destinations").getAsJsonObject();
      for (Map.Entry<String, JsonElement> entry : destinationSet.entrySet()) {
        String destination = entry.getKey();
        String policyName = entry.getValue().getAsString();
        PplPathFilter policy = policies.get(policyName);
        if (policy == null) {
          throw new IllegalArgumentException("Policy not found: " + policyName);
        }
        defaultsBuilder.add(destination, policy);
      }
      if (defaultsBuilder.list.isEmpty()) {
        throw new IllegalArgumentException("No entries in group");
      }
      PplPathFilter defaultPolicy = policies.get("default");
      Entry defaultEntry = defaultsBuilder.list.get(defaultsBuilder.list.size() - 1);
      if (defaultPolicy == null || defaultEntry.dstISD != 0) {
        throw new IllegalArgumentException("No default in group");
      }
      return defaultsBuilder.build();
    } catch (Exception e) {
      throw new IllegalArgumentException("Error parsing JSON: " + e.getMessage(), e);
    }
  }

  public String toJson(boolean prettyPrint) {
    JsonObject rootObj = new JsonObject();

    // destinations
    JsonObject destObj = new JsonObject();
    for (Entry entry : policies) {
      String key = PplUtil.toMinimal(entry.dstISD, entry.dstAS, entry.dstIP, entry.dstPort);
      destObj.addProperty(key, entry.policy.getName());
    }
    destObj.addProperty("0", "default");
    rootObj.add("destinations", destObj);

    // requirements and ordering defaults
    JsonObject defaultsObj = new JsonObject();
    defaults.toJson(defaultsObj);
    rootObj.add("defaults", defaultsObj);

    JsonObject filtersObj = new JsonObject();
    for (Entry entry : policies) {
      filtersObj.add(entry.policy.getName(), entry.policy.toJson());
    }
    rootObj.add("filters", filtersObj);

    GsonBuilder gsonBuilder = new GsonBuilder();
    if (prettyPrint) {
      gsonBuilder.setPrettyPrinting();
    }
    return gsonBuilder.create().toJson(rootObj);
  }

  private static class Entry {
    private final int dstISD;
    private final long dstAS;
    private final byte[] dstIP;
    private final int dstPort;
    private final PplPathFilter policy;

    private Entry(String destination, PplPathFilter policy) {
      this.policy = policy;
      String[] parts = destination.split("-");
      if (parts.length < 1 || parts.length > 2) {
        throw new IllegalArgumentException("Bad destination format: " + destination);
      }
      this.dstISD = Integer.parseInt(parts[0]);
      if (parts.length == 1) {
        this.dstAS = 0;
        this.dstIP = null;
        this.dstPort = 0;
        return;
      }
      parts = parts[1].split(",");
      this.dstAS = ScionUtil.parseAS(parts[0]);
      if (parts.length > 2) {
        throw new IllegalArgumentException("Bad destination format: " + destination);
      }
      if (parts.length == 1) {
        this.dstIP = null;
        this.dstPort = 0;
        return;
      }
      String[] partsIP = parts[1].split(":");
      if (partsIP.length == 1) {
        // IPv4 without port
        this.dstIP = IPHelper.toByteArray(partsIP[0]);
        this.dstPort = 0;
        return;
      }
      if (partsIP.length == 2) {
        // IPv4 with port
        this.dstIP = IPHelper.toByteArray(partsIP[0]);
        this.dstPort = Integer.parseInt(partsIP[1]);
        return;
      }
      // IPv6 with or without port
      String[] partsIPv6 = parts[1].split("]");
      dstIP = IPHelper.toByteArray(partsIPv6[0].substring(1)); // skip "["
      if (partsIPv6.length == 1) {
        // IPv6 without port
        this.dstPort = 0;
        return;
      }
      this.dstPort = Integer.parseInt(partsIPv6[1].substring(1)); // skip ":"
    }

    public boolean isMatch(ScionSocketAddress destination) {
      if (dstISD == 0 || dstISD == ScionUtil.extractIsd(destination.getIsdAs())) {
        if (dstAS == 0 || dstAS == ScionUtil.extractAs(destination.getIsdAs())) {
          if (dstIP == null || Arrays.equals(dstIP, destination.getAddress().getAddress())) {
            return dstPort == 0 || dstPort == destination.getPort();
          }
        }
      }
      return false;
    }
  }

  public static class Builder {
    private final List<Entry> list = new ArrayList<>();
    private final PplDefaults.Builder defaults = new PplDefaults.Builder();

    public Builder add(String destination, PplPathFilter policy) {
      list.add(new Entry(destination, policy));
      return this;
    }

    /**
     * Minimum metadata bandwidth requirement for paths. Default is 0.
     *
     * @param minBandwidthBytesPerSeconds Minimum bandwidth in bytes per second.
     * @return this Builder
     */
    public Builder minMetaBandwidth(long minBandwidthBytesPerSeconds) {
      this.defaults.minMetaBandwidth(minBandwidthBytesPerSeconds);
      return this;
    }

    /**
     * Minimum MTU requirement for paths. Default is 0.
     *
     * @param minMtuBytes Minimum MTU bytes required for a path to be accepted.
     * @return this builder
     */
    public Builder minMtu(int minMtuBytes) {
      this.defaults.minMtu(minMtuBytes);
      return this;
    }

    /**
     * Minimum validity requirement for paths. Default is 0.
     *
     * @param minValiditySeconds Minimum seconds before a path expires.
     * @return this Builder
     */
    public Builder minValidity(int minValiditySeconds) {
      this.defaults.minValidity(minValiditySeconds);
      return this;
    }

    public Builder ordering(String ordering) {
      this.defaults.ordering(ordering);
      return this;
    }

    public PplPolicy build() {
      if (list.isEmpty()) {
        throw new PplException("Policy has no default filter");
      }
      return new PplPolicy(list, defaults.build());
    }
  }
}
