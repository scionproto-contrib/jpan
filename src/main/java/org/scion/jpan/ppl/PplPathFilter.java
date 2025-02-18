// Copyright 2025 ETH Zurich, Anapaya Systems
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
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.scion.jpan.*;

/**
 * A path policy based onj Path Policy Language: <a
 * href="https://docs.scion.org/en/latest/dev/design/PathPolicy.html">...</a>
 *
 * <p>Policy is a compiled path policy object, all extended policies have been merged.
 */
// Copied from https://github.com/scionproto/scion/tree/master/private/path/pathpol
public class PplPathFilter implements PathPolicy {

  /** FilterOptions contains options for filtering. */
  static class FilterOptions {
    // IgnoreSequence can be used to ignore the sequence part of policies.
    private final boolean ignoreSequence;

    FilterOptions(boolean ignoreSequence) {
      this.ignoreSequence = ignoreSequence;
    }

    public static FilterOptions create(boolean ignoreSequence) {
      return new FilterOptions(ignoreSequence);
    }
  }

  private final String name;
  private ACL acl;
  private Sequence sequence;
  private Option[] options;

  PplPathFilter(String name, ACL acl, Sequence sequence, Option... options) {
    this.name = name;
    this.acl = acl;
    this.sequence = sequence;
    this.options = options == null ? new Option[0] : options;
    // Sort Options by weight, descending
    Arrays.sort(this.options, (o1, o2) -> -Integer.compare(o1.weight, o2.weight));
  }

  private static PplPathFilter createCopy(PplPathFilter policy) {
    return new PplPathFilter(policy.name, policy.acl, policy.sequence, policy.options);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static PplPathFilter fromJson(String json) {
    return parseJsonFile(json).get(0); // TODO
  }

  // Filter filters the paths according to the policy.
  @Override
  public List<Path> filter(List<Path> paths) {
    return filterOpt(paths, new FilterOptions(false));
  }

  // FilterOpt filters the path set according to the policy with the given
  // options.
  List<Path> filterOpt(List<Path> paths, FilterOptions opts) {
    paths = acl == null ? paths : acl.eval(paths);
    if (sequence != null && !opts.ignoreSequence) {
      paths = sequence.eval(paths);
    }
    // Filter on sub policies
    if (options.length > 0) {
      paths = evalOptions(paths, opts);
    }
    return paths;
  }

  // PolicyFromExtPolicy creates a Policy from an extending Policy and the extended policies
  public static PplPathFilter policyFromExtPolicy(PplExtPolicy extPolicy, PplExtPolicy[] extended) {
    PplPathFilter policy = PplPathFilter.createCopy(extPolicy);
    // Apply all extended policies
    policy.applyExtended(extPolicy.getExtensions(), extended);
    return policy;
  }

  // applyExtended adds attributes of extended policies to the extending policy if they are not
  // already set
  private void applyExtended(String[] extensions, PplExtPolicy[] exPolicies) {
    // TODO: Prevent circular policies.
    // traverse in reverse s.t. last entry of the list has precedence
    for (int i = extensions.length - 1; i >= 0; i--) {
      PplPathFilter policy = null;
      // Find extended policy
      for (PplExtPolicy exPol : exPolicies) {
        if (Objects.equals(exPol.getName(), extensions[i])) {
          policy = policyFromExtPolicy(exPol, exPolicies);
        }
      }
      if (policy == null) {
        throw new PplException("Extended policy could not be found: " + extensions[i]);
      }
      // Replace ACL
      if (acl == null && policy.acl != null) {
        acl = policy.acl;
      }
      // Replace Options
      if (options.length == 0) {
        options = policy.options;
      }
      // Replace Sequence
      if (sequence == null) {
        sequence = policy.sequence;
      }
    }
  }

  // evalOptions evaluates the options of a policy and returns the pathSet that matches the option
  // with the highest weight
  private List<Path> evalOptions(List<Path> paths, FilterOptions opts) {
    Set<String> subPolicySet = new HashSet<>();
    int currWeight = options[0].weight;
    // Go through sub policies
    for (Option option : options) {
      if (currWeight > option.weight && !subPolicySet.isEmpty()) {
        break;
      }
      currWeight = option.weight;
      List<Path> subPaths = option.policy.filterOpt(paths, opts);
      for (Path path : subPaths) {
        subPolicySet.add(fingerprint(path));
      }
    }
    List<Path> result = new ArrayList<>();
    for (Path path : paths) {
      if (subPolicySet.contains(fingerprint(path))) { // TODO correct?
        result.add(path);
      }
    }
    return result;
  }

  // Option contains a weight and a policy and is used as a list item in Policy.Options
  @Deprecated
  static class Option {
    private final int weight;
    private final PplExtPolicy policy;

    private Option(int weight, PplExtPolicy policy) {
      this.weight = weight;
      this.policy = policy;
    }

    static Option create(int weight, PplExtPolicy policy) {
      return new Option(weight, policy);
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Option option = (Option) o;
      return weight == option.weight && Objects.equals(policy, option.policy);
    }

    @Override
    public int hashCode() {
      return Objects.hash(weight, policy);
    }

    @Override
    public String toString() {
      return "Option{" + "weight=" + weight + ", policy=" + policy + '}';
    }
  }

  // Fingerprint uniquely identifies the path based on the sequence of
  // ASes and BRs, i.e. by its PathInterfaces.
  // Other metadata, such as MTU or NextHop have no effect on the fingerprint.
  // Returns empty string for paths where the interfaces list is not available.
  private static String fingerprint(Path path) {
    PathMetadata meta = path.getMetadata();
    if (meta == null || meta.getInterfacesList().isEmpty()) {
      return "";
    }
    try {
      MessageDigest h = MessageDigest.getInstance("SHA-256");
      ByteBuffer bb = ByteBuffer.allocate(16 * meta.getInterfacesList().size());
      for (PathMetadata.PathInterface intf : meta.getInterfacesList()) {
        bb.putLong(intf.getIsdAs());
        bb.putLong(intf.getId());
      }
      byte[] digest = h.digest(bb.array());
      return new String(digest, StandardCharsets.UTF_8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * getSequence constructs the sequence string from a Path. Output format:
   *
   * <p>{@code 1-ff00:0:133#42 1-ff00:0:120#2,1 1-ff00:0:110#21}
   */
  public static String getSequence(Path path) {
    return Sequence.getSequence(path);
  }

  public String getName() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PplPathFilter policy = (PplPathFilter) o;
    return Objects.equals(name, policy.name)
        && Objects.equals(acl, policy.acl)
        && Objects.equals(sequence, policy.sequence)
        && Objects.deepEquals(options, policy.options);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, acl, sequence, Arrays.hashCode(options));
  }

  @Override
  public String toString() {
    return "PplPathFilter{"
        + "name='"
        + name
        + "', acl="
        + acl
        + ", sequence="
        + sequence
        + ", options="
        + Arrays.toString(options)
        + '}';
  }

  private static List<PplPathFilter> parseJsonFile(String jsonFile) {
    List<PplPathFilter> policies = new ArrayList<>();
    JsonElement jsonTree = JsonParser.parseString(jsonFile);
    if (jsonTree.isJsonObject()) {
      JsonObject parent = jsonTree.getAsJsonObject();
      for (Map.Entry<String, JsonElement> oo : parent.entrySet()) {
        policies.add(parseJsonPolicy(oo.getKey(), oo.getValue().getAsJsonObject()));
      }
    }
    return policies;
  }

  static PplPathFilter parseJsonPolicy(String name, JsonObject policy) {
    Builder b = new Builder();
    b.setName(name);
    JsonElement aclElement = policy.get("acl");
    if (aclElement != null) {
      for (JsonElement e : aclElement.getAsJsonArray()) {
        b.addAclEntry(e.getAsString());
      }
    }
    JsonElement sequence = policy.get("sequence");
    if (sequence != null) {
      b.setSequence(sequence.getAsString());
    }
    return b.build();
  }

  public JsonElement toJson() {
    JsonObject json = new JsonObject();
    // json.addProperty("name", name);
    if (acl != null) {
      json.add("acl", acl.toJson());
    }
    if (sequence != null) {
      json.addProperty("sequence", sequence.getSourceString());
    }
    return json;
  }

  public static class Builder {
    protected String name = "";
    Sequence sequence = null;
    final List<Option> options = new ArrayList<>();
    final List<ACL.AclEntry> entries = new ArrayList<>();

    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    public Builder addAclEntry(String str) {
      entries.add(ACL.AclEntry.create(str));
      return this;
    }

    public Builder addAclEntries(String... strings) {
      for (String str : strings) {
        entries.add(ACL.AclEntry.create(str));
      }
      if (strings.length == 0) {
        throw new PplException(ACL.ERR_NO_DEFAULT);
      }
      return this;
    }

    public Builder addAclEntry(boolean allow, String hopFieldPredicate) {
      entries.add(ACL.AclEntry.create(allow, hopFieldPredicate));
      return this;
    }

    public Builder setSequence(String sequence) {
      this.sequence = Sequence.create(sequence);
      return this;
    }

    /**
     * @param weight weight
     * @param policy policy
     * @return Builder
     * @deprecated Please use with caution, API may change.
     */
    @Deprecated
    public Builder addOption(int weight, PplExtPolicy policy) {
      this.options.add(Option.create(weight, policy));
      return this;
    }

    public PplPathFilter build() {
      if (entries.isEmpty() && sequence == null && options.isEmpty()) {
        throw new PplException(ACL.ERR_NO_DEFAULT);
      }
      ACL acl = entries.isEmpty() ? null : ACL.create(entries.toArray(new ACL.AclEntry[0]));
      return new PplPathFilter(name, acl, sequence, options.toArray(new Option[0]));
    }

    PplPathFilter buildNoValidate() {
      ACL acl =
          entries.isEmpty() ? null : ACL.createNoValidate(entries.toArray(new ACL.AclEntry[0]));
      return new PplPathFilter(name, acl, sequence, options.toArray(new Option[0]));
    }
  }
}
