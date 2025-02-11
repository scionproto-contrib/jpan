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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Stream;
import org.scion.jpan.*;
import org.scion.jpan.internal.IPHelper;

public class PplPolicy implements PathPolicy {

  private final List<Entry> policies;
  private final int minMtu;
  private final int minValiditySec;

  private PplPolicy(List<Entry> policies, int minMtuBytes, int minValiditySeconds) {
    this.policies = policies;
    this.minMtu = minMtuBytes;
    this.minValiditySec = minValiditySeconds;
  }

  @Override
  public List<Path> filter(List<Path> input) {
    // filter
    List<Path> filtered = new ArrayList<>();
    long now = System.currentTimeMillis() / 1000; // unix epoch
    for (Path path : input) {
      PathMetadata meta = path.getMetadata();
      if (meta.getMtu() >= minMtu && meta.getExpiration() >= now + minValiditySec) {
        filtered.add(path);
      }
    }
    if (filtered.isEmpty()) {
      return Collections.emptyList();
    }
    // We assume that all paths have the same destination
    ScionSocketAddress destination = filtered.get(0).getRemoteSocketAddress();
    for (Entry entry : policies) {
      if (entry.isMatch(destination)) {
        return entry.policy.filter(filtered);
      }
    }
    throw new IllegalStateException("Default policy does not match!");
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
      JsonElement jsonTree = com.google.gson.JsonParser.parseString(jsonFile);
      if (!jsonTree.isJsonObject()) {
        throw new IllegalArgumentException("Bad file format: " + jsonFile);
      }
      JsonObject parentSet = jsonTree.getAsJsonObject();

      // Policies
      Map<String, PplSubPolicy> policies = new HashMap<>();
      JsonObject policySet = parentSet.get("policies").getAsJsonObject();
      for (Map.Entry<String, JsonElement> p : policySet.entrySet()) {
        policies.put(
            p.getKey(), PplSubPolicy.parseJsonPolicy(p.getKey(), p.getValue().getAsJsonObject()));
      }

      // Group
      Builder groupBuilder = new Builder();
      JsonObject groupSet = parentSet.get("destinations").getAsJsonObject();
      for (Map.Entry<String, JsonElement> entry : groupSet.entrySet()) {
        String destination = entry.getKey();
        String policyName = entry.getValue().getAsString();
        PplSubPolicy policy = policies.get(policyName);
        if (policy == null) {
          throw new IllegalArgumentException("Policy not found: " + policyName);
        }
        groupBuilder.add(destination, policy);
      }
      if (groupBuilder.list.isEmpty()) {
        throw new IllegalArgumentException("No entries in group");
      }
      PplSubPolicy defaultPolicy = policies.get("default");
      Entry defaultEntry = groupBuilder.list.get(groupBuilder.list.size() - 1);
      if (defaultPolicy == null || defaultEntry.dstISD != 0) {
        throw new IllegalArgumentException("No default in group");
      }
      return groupBuilder.build();
    } catch (Exception e) {
      throw new IllegalArgumentException("Error parsing JSON: " + e.getMessage(), e);
    }
  }

  private static class Entry {
    private final int dstISD;
    private final long dstAS;
    private final byte[] dstIP;
    private final int dstPort;
    private final PplSubPolicy policy;

    private Entry(String destination, PplSubPolicy policy) {
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
    private int minMtuBytes = 0;
    private int minValiditySeconds = 0;

    public Builder add(String destination, PplSubPolicy policy) {
      list.add(new Entry(destination, policy));
      return this;
    }

    /**
     * Minimum MTU requirement for paths. Default is 0.
     * @param minMtuBytes Minimum MTU bytes required for a path to be accepted.
     * @return this builder
     */
    public Builder minMtu(int minMtuBytes) {
      this.minMtuBytes = minMtuBytes;
      return this;
    }

    /**
     * Minimum validity requirement for paths. Default is 0.
     * @param minValiditySeconds Minimum seconds before a path expires.
     * @return this Builder
     */
    public Builder minValidity(int minValiditySeconds) {
      this.minValiditySeconds = minValiditySeconds;
      return this;
    }

    public PplPolicy build() {
      return new PplPolicy(list, minMtuBytes, minValiditySeconds);
    }
  }
}
