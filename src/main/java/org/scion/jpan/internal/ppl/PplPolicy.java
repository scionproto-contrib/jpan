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

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.PathPolicy;

/**
 * A path policy based onj Path Policy Language: <a
 * href="https://docs.scion.org/en/latest/dev/design/PathPolicy.html">...</a>
 *
 * <p>Policy is a compiled path policy object, all extended policies have been merged.
 */
// Copied from https://github.com/scionproto/scion/tree/master/private/path/pathpol
public class PplPolicy implements PathPolicy {

  /** ExtPolicy is an extending policy, it may have a list of policies it extends. */
  public static class ExtPolicy extends PplPolicy {
    private final String[] extensions; // []string `json:"extends,omitempty"`

    public ExtPolicy(
        String name, ACL acl, Sequence sequence, String[] extensions, Option... options) {
      super(name, acl, sequence, options);
      this.extensions = extensions;
    }
  }

  // PolicyMap is a container for Policies, keyed by their unique name. PolicyMap
  // can be used to marshal Policies to JSON. Unmarshaling back to PolicyMap is
  // guaranteed to yield an object that is identical to the initial one.
  // type PolicyMap map[string]*ExtPolicy
  // TODO

  /** FilterOptions contains options for filtering. */
  private static class FilterOptions {
    // IgnoreSequence can be used to ignore the sequence part of policies.
    private boolean ignoreSequence;
  }

  private final String name; //       `json:"-"`
  private ACL acl; //         `json:"acl,omitempty"`
  private Sequence sequence; //    `json:"sequence,omitempty"`
  private LocalIsdAs lLocalIsdAs; //  `json:"local_isd_ases,omitempty"`
  private RemoteIsdAs remoteIsdAs; // `json:"remote_isd_ases,omitempty"`
  private Option[] options; //     `json:"options,omitempty"`

  private PplPolicy(String name, ACL acl, Sequence sequence, Option... options) {
    this.name = name;
    this.acl = acl;
    this.sequence = sequence;
    this.options = options;
    // Sort Options by weight, descending
    Arrays.sort(options, (o1, o2) -> -Integer.compare(o1.weight, o2.weight));
  }

  private PplPolicy() {
    this(null, null, null);
  }

  // creates a Policy and sorts its Options
  public static PplPolicy create(String name, ACL acl, Sequence sequence, Option... options) {
    return new PplPolicy(name, acl, sequence, options);
  }

  @Override
  public Path filter(List<Path> paths) {
    List<Path> filtered = filterAll(paths);
    return filtered.isEmpty() ? null : filtered.get(0);
  }

  // Filter filters the paths according to the policy.
  List<Path> filterAll(List<Path> paths) {
    return filterOpt(paths, new FilterOptions());
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
    paths = acl.eval(paths);
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
  PplPolicy policyFromExtPolicy(ExtPolicy extPolicy, ExtPolicy[] extended) {
    PplPolicy policy = extPolicy;
    if (policy == null) {
      policy = new PplPolicy();
    }
    // Apply all extended policies
    try {
      policy.applyExtended(extPolicy.extensions, extended);
    } catch (PplException e) {
      return null;
    }
    return policy;
  }

  // applyExtended adds attributes of extended policies to the extending policy if they are not
  // already set
  private void applyExtended(String[] extensions, ExtPolicy[] exPolicies) {
    // TODO: Prevent circular policies.
    // traverse in reverse s.t. last entry of the list has precedence
    for (int i = extensions.length - 1; i >= 0; i--) {
      PplPolicy policy = null;
      // Find extended policy
      for (ExtPolicy exPol : exPolicies) {
        if (Objects.equals(exPol.getName(), extensions[i])) {
          policy = policyFromExtPolicy(exPol, exPolicies);
        }
      }
      if (policy == null) {
        throw new PplException("Extended policy could not be found" + extensions[i]);
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
  public static class Option {
    private int weight; //        `json:"weight"`
    private ExtPolicy policy; // `json:"policy"`

    public Option(int weight, ExtPolicy policy) {
      this.weight = weight;
      this.policy = policy;
    }
  }

  // Fingerprint uniquely identifies the path based on the sequence of
  // ASes and BRs, i.e. by its PathInterfaces.
  // Other metadata, such as MTU or NextHop have no effect on the fingerprint.
  // Returns empty string for paths where the interfaces list is not available.
  private static String fingerprint(Path path) {
    PathMetadata meta = path.getMetadata();
    if (meta == null || meta.getInterfacesList().isEmpty()) {
      return ""; // TODO?
    }
    try {
      MessageDigest h = MessageDigest.getInstance("SHA-256");
      for (PathMetadata.PathInterface intf : meta.getInterfacesList()) {
        h.digest(ByteBuffer.allocate(8).putLong(intf.getIsdAs()).array());
        h.digest(ByteBuffer.allocate(8).putLong(intf.getId()).array());
      }
      return String.valueOf(h.digest()); // TODO check result
    } catch (NoSuchAlgorithmException e) {
      throw new PplException(e);
    }
  }

  public static class HopParseException extends IllegalArgumentException {
    public HopParseException(String str) {
      super(str);
    }

    public HopParseException(String str, Throwable t) {
      super(str, t);
    }
  }

  public String getName() {
    return name;
  }
}
