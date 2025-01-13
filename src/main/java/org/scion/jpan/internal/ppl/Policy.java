// Copyright 2024 ETH Zurich
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

public class Policy {

  // ExtPolicy is an extending policy, it may have a list of policies it extends
  private static class ExtPolicy extends Policy {
    String[] extensions; // []string `json:"extends,omitempty"`
  }

  // PolicyMap is a container for Policies, keyed by their unique name. PolicyMap
  // can be used to marshal Policies to JSON. Unmarshaling back to PolicyMap is
  // guaranteed to yield an object that is identical to the initial one.
  // type PolicyMap map[string]*ExtPolicy
  // TODO

  // FilterOptions contains options for filtering.
  private static class FilterOptions {
    // IgnoreSequence can be used to ignore the sequence part of policies.
    boolean IgnoreSequence;
  }

  // Policy is a compiled path policy object, all extended policies have been merged.
  final String name; //       `json:"-"`
  ACL acl; //         `json:"acl,omitempty"`
  Sequence sequence; //    `json:"sequence,omitempty"`
  LocalIsdAs lLocalIsdAs; //  `json:"local_isd_ases,omitempty"`
  RemoteIsdAs remoteIsdAs; // `json:"remote_isd_ases,omitempty"`
  Option[] options; //     `json:"options,omitempty"`

  private Policy(String name, ACL acl, Sequence sequence, Option... options) {
    this.name = name;
    this.acl = acl;
    this.sequence = sequence;
    this.options = options;
  }

  private Policy() {
    this.name = null;
    this.acl = null;
    this.sequence = null;
    this.options = null;
  }

  // NewPolicy creates a Policy and sorts its Options
  public static Policy newPolicy(String name, ACL acl, Sequence sequence, Option... options) {
    Policy policy = new Policy(name, acl, sequence, options);
    // Sort Options by weight, descending
    Arrays.sort(policy.options, (o1, o2) -> -Integer.compare(o1.weight, o2.weight));
    return policy;
  }

  // Filter filters the paths according to the policy.
  List<Path> filter(List<Path> paths) {
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
    if (sequence != null && !opts.IgnoreSequence) {
      paths = sequence.eval(paths);
    }
    // Filter on sub policies
    if (options.length > 0) {
      paths = evalOptions(paths, opts);
    }
    return paths;
  }

  // PolicyFromExtPolicy creates a Policy from an extending Policy and the extended policies
  Policy policyFromExtPolicy(ExtPolicy extPolicy, ExtPolicy[] extended) {
    Policy policy = extPolicy;
    if (policy == null) {
      policy = new Policy();
    }
    // Apply all extended policies
    try {
      policy.applyExtended(extPolicy.extensions, extended);
    } catch (PPLException e) {
      return null;
    }
    return policy;
  }

  // applyExtended adds attributes of extended policies to the extending policy if they are not
  // already set
  void applyExtended(String[] extensions, ExtPolicy[] exPolicies) {
    // TODO(worxli): Prevent circular policies.
    // traverse in reverse s.t. last entry of the list has precedence
    for (int i = extensions.length - 1; i >= 0; i--) {
      Policy policy = null;
      // Find extended policy
      for (ExtPolicy exPol : exPolicies) {
        if (Objects.equals(exPol.name, extensions[i])) {
          policy = policyFromExtPolicy(exPol, exPolicies);
        }
      }
      if (policy == null) {
        throw new PPLException("Extended policy could not be found" + extensions[i]);
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
  List<Path> evalOptions(List<Path> paths, FilterOptions opts) {
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
  static class Option {
    int weight; //        `json:"weight"`
    ExtPolicy policy; // `json:"policy"`
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
      throw new PPLException(e);
    }
  }
}
