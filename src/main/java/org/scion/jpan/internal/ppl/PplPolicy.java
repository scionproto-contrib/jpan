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

package org.scion.jpan.internal.ppl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
public class PplPolicy implements PathPolicy {

  // PolicyMap is a container for Policies, keyed by their unique name. PolicyMap
  // can be used to marshal Policies to JSON. Unmarshaling back to PolicyMap is
  // guaranteed to yield an object that is identical to the initial one.
  // type PolicyMap map[string]*ExtPolicy
  // TODO

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

  private final String name; //       `json:"-"`
  private ACL acl; //         `json:"acl,omitempty"`
  private Sequence sequence; //    `json:"sequence,omitempty"`
  private LocalIsdAs lLocalIsdAs; //  `json:"local_isd_ases,omitempty"`
  private RemoteIsdAs remoteIsdAs; // `json:"remote_isd_ases,omitempty"`
  private Option[] options; //     `json:"options,omitempty"`

  protected PplPolicy(String name, ACL acl, Sequence sequence, Option... options) {
    this.name = name;
    this.acl = acl;
    this.sequence = sequence;
    this.options = options == null ? new Option[0] : options;
    // Sort Options by weight, descending
    Arrays.sort(this.options, (o1, o2) -> -Integer.compare(o1.weight, o2.weight));
  }

  private PplPolicy() {
    this(null, null, null);
  }

  // creates a Policy and sorts its Options
  static PplPolicy create(String name, ACL acl, Sequence sequence, Option... options) {
    return new PplPolicy(name, acl, sequence, options);
  }

  private static PplPolicy createCopy(PplPolicy policy) {
    return new PplPolicy(policy.name, policy.acl, policy.sequence, policy.options);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static PplPolicy fromJson(String json) {
    return parseJsonFile(json).get(0); // TODO
  }

  @Override
  public List<Path> filter(List<Path> paths) {
    List<Path> filtered = filterAll(paths);
    return PathPolicy.assertNotEmpty(filtered);
  }

  // Filter filters the paths according to the policy.
  List<Path> filterAll(List<Path> paths) {
    return filterOpt(paths, new FilterOptions(false));
  }

  // FilterOpt filters the path set according to the policy with the given
  // options.
  List<Path> filterOpt(List<Path> paths, FilterOptions opts) {
    if (this == null) {
      return paths;
    }
    if (lLocalIsdAs != null) {
      paths = lLocalIsdAs.eval(paths);
    }
    if (remoteIsdAs != null) {
      paths = remoteIsdAs.eval(paths);
    }
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
  public static PplPolicy policyFromExtPolicy(PplExtPolicy extPolicy, PplExtPolicy[] extended) {
    PplPolicy policy = PplPolicy.createCopy(extPolicy);
    if (policy == null) {
      policy = new PplPolicy();
    }
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
      PplPolicy policy = null;
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
      // Replace local ISDAS filter.
      if (lLocalIsdAs == null) {
        lLocalIsdAs = policy.lLocalIsdAs;
      }
      // Replace remote ISDAS filter.
      if (remoteIsdAs == null) {
        remoteIsdAs = policy.remoteIsdAs;
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
    private final int weight; //        `json:"weight"`
    private final PplExtPolicy policy; // `json:"policy"`

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
      throw new PplException(e);
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
    PplPolicy policy = (PplPolicy) o;
    return Objects.equals(name, policy.name)
        && Objects.equals(acl, policy.acl)
        && Objects.equals(sequence, policy.sequence)
        && Objects.equals(lLocalIsdAs, policy.lLocalIsdAs)
        && Objects.equals(remoteIsdAs, policy.remoteIsdAs)
        && Objects.deepEquals(options, policy.options);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, acl, sequence, lLocalIsdAs, remoteIsdAs, Arrays.hashCode(options));
  }

  @Override
  public String toString() {
    return "PplPolicy{"
        + "name='"
        + name
        + '\''
        + ", acl="
        + acl
        + ", sequence="
        + sequence
        + ", lLocalIsdAs="
        + lLocalIsdAs
        + ", remoteIsdAs="
        + remoteIsdAs
        + ", options="
        + Arrays.toString(options)
        + '}';
  }

  private static List<PplPolicy> parseJsonFile(String jsonFile) {
    List<PplPolicy> policies = new ArrayList<>();
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(jsonFile);
    if (jsonTree.isJsonObject()) {
      JsonObject o = jsonTree.getAsJsonObject();
      for (Map.Entry<String, JsonElement> oo : o.entrySet()) {
        Builder b = new Builder();
        b.setName(oo.getKey());
        System.out.println("name: " + oo.getKey()); // TODO
        if (oo.getValue().isJsonObject()) {
          JsonObject policy = oo.getValue().getAsJsonObject();
          JsonElement aclElement = policy.get("acl");
          if (aclElement != null) {
            for (JsonElement e : aclElement.getAsJsonArray()) {
              b.addAclEntry(e.getAsString());
              System.out.println("  acl: " + e.getAsString()); // TODO
            }
          }
          JsonElement sequence = policy.get("sequence");
          if (sequence != null) {
            b.setSequence(sequence.getAsString());
            System.out.println("  sequence: " + sequence.getAsString()); // TODO
          }
        }
        policies.add(b.build());
      }
    }
    return policies;
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

    public PplPolicy build() {
      if (entries.isEmpty() && sequence == null && options.isEmpty()) {
        throw new PplException(ACL.ERR_NO_DEFAULT);
      }
      ACL acl = entries.isEmpty() ? null : ACL.create(entries.toArray(new ACL.AclEntry[0]));
      return new PplPolicy(name, acl, sequence, options.toArray(new Option[0]));
    }

    PplPolicy buildNoValidate() {
      ACL acl =
          entries.isEmpty() ? null : ACL.createNoValidate(entries.toArray(new ACL.AclEntry[0]));
      return new PplPolicy(name, acl, sequence, options.toArray(new Option[0]));
    }
  }
}
