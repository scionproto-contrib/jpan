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

package org.scion.jpan.internal.ppl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.scion.jpan.ScionRuntimeException;

public class PplPolicyGroup {

  private final List<Entry> policies;

  private PplPolicyGroup(List<Entry> policies) {
    this.policies = policies;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static PplPolicyGroup fromJson(Path file) {
    StringBuilder contentBuilder = new StringBuilder();
    try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
      stream.forEach(s -> contentBuilder.append(s).append("\n"));
    } catch (IOException e) {
      throw new ScionRuntimeException("Error reading topology file: " + file.toAbsolutePath(), e);
    }
    return fromJson(contentBuilder.toString());
  }

  public static PplPolicyGroup fromJson(String jsonFile) {
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(jsonFile);
    if (!jsonTree.isJsonObject()) {
      throw new IllegalArgumentException("Bad file format: " + jsonFile);
    }
    JsonObject parentSet = jsonTree.getAsJsonObject();

    // Policies
    Map<String, PplPolicy> policies = new HashMap<>();
    JsonObject policySet = parentSet.get("policies").getAsJsonObject();
    for (Map.Entry<String, JsonElement> p : policySet.entrySet()) {
      policies.put(
          p.getKey(), PplPolicy.parseJsonPolicy(p.getKey(), p.getValue().getAsJsonObject()));
    }

    // Group
    Builder groupBuilder = new Builder();
    JsonObject groupSet = parentSet.get("group").getAsJsonObject();
    for (Map.Entry<String, JsonElement> entry : groupSet.entrySet()) {
      String destination = entry.getKey();
      String policyName = entry.getValue().getAsString();
      PplPolicy policy = policies.get(policyName);
      if (policy == null) {
        throw new IllegalArgumentException("Policy not found: " + policyName);
      }
      groupBuilder.add(destination, policy);
    }
    return groupBuilder.build();
  }

  private static class Entry {
    private final String destination;
    private final PplPolicy policy;

    private Entry(String destination, PplPolicy policy) {
      this.destination = destination;
      this.policy = policy;
    }
  }

  public static class Builder {
    private final List<Entry> list = new ArrayList<>();

    public Builder add(String destination, PplPolicy policy) {
      list.add(new Entry(destination, policy));
      return this;
    }

    public PplPolicyGroup build() {
      return new PplPolicyGroup(list);
    }
  }
}
