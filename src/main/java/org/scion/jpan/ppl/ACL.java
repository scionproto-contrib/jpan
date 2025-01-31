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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;

// Copied from https://github.com/scionproto/scion/tree/master/private/path/pathpol
class ACL {

  /** ErrNoDefault indicates that there is no default acl entry. */
  static final String ERR_NO_DEFAULT = "ACL does not have a default";

  /** ErrExtraEntries indicates that there extra entries after the default entry. */
  static final String ERR_EXTRA_ENTRIES = "ACL has unused extra entries after a default entry";

  private final AclEntry[] entries;

  /** Creates a new entry and checks for the presence of a default action. */
  static ACL create(AclEntry... entries) {
    validateACL(entries);
    return new ACL(entries);
  }

  static ACL createNoValidate(AclEntry... entries) {
    return new ACL(entries);
  }

  /** Creates a new entry and checks for the presence of a default action. */
  static ACL create(String... entries) {
    AclEntry[] eArray = new AclEntry[entries.length];
    for (int i = 0; i < entries.length; i++) {
      eArray[i] = AclEntry.create(entries[i]);
    }
    validateACL(eArray);
    return new ACL(eArray);
  }

  private ACL(AclEntry... entries) {
    this.entries = entries;
  }

  public static Builder builder() {
    return new Builder();
  }

  // Eval returns the set of paths that match the ACL.
  List<Path> eval(List<Path> paths) {
    if (this == null || entries.length == 0) {
      return paths;
    }
    List<Path> result = new ArrayList<>();
    for (Path path : paths) {
      // Check ACL
      if (evalPath(path.getMetadata()) == AclAction.ALLOW) {
        result.add(path);
      }
    }
    return result;
  }

  //    JsonWriter MarshalJSON(JsonWriter json) {
  //        return json.Marshal(entries);
  //    }
  //
  //    JsonReader UnmarshalJSON(JsonReader json) {
  //        json.Unmarshal(b, Entries);
  //        validateACL(entries);
  //        return json;
  //    }

  // TODO
  //    func (a *ACL) MarshalYAML() (interface{}, error) {
  //        return a.Entries, nil
  //    }
  //
  //    func (a *ACL) UnmarshalYAML(unmarshal func(interface{}) error) error {
  //        if err := unmarshal(&a.Entries); err != nil {
  //            return err
  //        }
  //        return validateACL(a.Entries)
  //    }

  AclAction evalPath(PathMetadata pm) {
    for (int i = 0; i < pm.getInterfacesList().size(); i++) {
      PathMetadata.PathInterface iface = pm.getInterfacesList().get(i);
      if (evalInterface(iface, i % 2 != 0) == AclAction.DENY) {
        return AclAction.DENY;
      }
    }
    return AclAction.ALLOW;
  }

  AclAction evalInterface(PathMetadata.PathInterface iface, boolean ingress) {
    for (AclEntry aclEntry : entries) {
      if (aclEntry.rule == null || aclEntry.rule.pathIFMatch(iface, ingress)) {
        return aclEntry.action;
      }
    }
    throw new PplException("Default ACL action missing");
  }

  private static void validateACL(AclEntry[] entries) {
    if (entries.length == 0) {
      throw new PplException(ERR_NO_DEFAULT);
    }

    int foundAt = -1;
    for (int i = 0; i < entries.length; i++) {
      if (entries[i].rule == null || entries[i].rule.matchesAll()) {
        foundAt = i;
        break;
      }
    }

    if (foundAt < 0) {
      throw new PplException(ERR_NO_DEFAULT);
    }

    if (foundAt != entries.length - 1) {
      throw new PplException(ERR_EXTRA_ENTRIES);
    }
  }

  static class AclEntry {
    private final AclAction action;
    private final HopPredicate rule;

    private AclEntry(AclAction action, HopPredicate rule) {
      this.action = action;
      this.rule = rule;
    }

    static AclEntry create(String str) {
      String[] parts = str.split(" ");
      if (parts.length == 1) {
        return new AclEntry(getAction(parts[0]), null);
      } else if (parts.length == 2) {
        return new AclEntry(getAction(parts[0]), HopPredicate.HopPredicateFromString(parts[1]));
      }
      throw new PplException("ACLEntry has too many parts: " + str);
    }

    public static AclEntry create(boolean allow, String hopFieldPredicate) {
      HopPredicate hp =
          hopFieldPredicate == null ? null : HopPredicate.HopPredicateFromString(hopFieldPredicate);
      return new AclEntry(allow ? AclAction.ALLOW : AclAction.DENY, hp);
    }

    String string() {
      String str = DENY_SYMBOL;
      if (action == AclAction.ALLOW) {
        str = ALLOW_SYMBOL;
      }
      if (rule != null) {
        str = str + " " + rule.string();
      }
      return str;
    }

    @Override
    public String toString() {
      return "AclEntry{" + "action=" + action + ", rule=" + rule + '}';
    }

    //    JsonWriter MarshalJSON(JsonWriter json) {
    //        return json.Marshal(String());
    //    }
    //
    //    JsonReader UnmarshalJSON(JsonReader json) {
    //        String str;
    //        json.Unmarshal(b, str);
    //        loadFromString(str);
    //        return json;
    //    }

    //    func (ae *ACLEntry) MarshalYAML() (interface{}, error) {
    //        return ae.String(), nil
    //    }
    //
    //    func (ae *ACLEntry) UnmarshalYAML(unmarshal func(interface{}) error) error {
    //        var str string
    //        err := unmarshal(&str)
    //        if err != nil {
    //            return err
    //        }
    //        return ae.LoadFromString(str)
    //    }

    private static AclAction getAction(String symbol) {
      if (ALLOW_SYMBOL.equals(symbol)) {
        return AclAction.ALLOW;
      } else if (DENY_SYMBOL.equals(symbol)) {
        return AclAction.DENY;
      } else {
        throw new PplException("Bad action symbol: " + "action=" + symbol);
      }
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      AclEntry aclEntry = (AclEntry) o;
      return action == aclEntry.action && Objects.equals(rule, aclEntry.rule);
    }

    @Override
    public int hashCode() {
      return Objects.hash(action, rule);
    }
  }

  // ACLAction has two options: Deny and Allow
  private enum AclAction {
    DENY,
    ALLOW
  }

  private static final String DENY_SYMBOL = "-";
  private static final String ALLOW_SYMBOL = "+";

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ACL acl = (ACL) o;
    return Objects.deepEquals(entries, acl.entries);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(entries);
  }

  @Override
  public String toString() {
    return "ACL{" + "entries=" + Arrays.toString(entries) + '}';
  }

  public static class Builder {
    private final List<AclEntry> entries = new ArrayList<>();

    public Builder addEntry(String str) {
      entries.add(AclEntry.create(str));
      return this;
    }

    public Builder addEntry(boolean allow, String hopFieldPredicate) {
      entries.add(AclEntry.create(allow, hopFieldPredicate));
      return this;
    }

    public Builder addEntry(AclEntry entry) {
      entries.add(entry);
      return this;
    }

    public ACL build() {
      return new ACL(entries.toArray(new AclEntry[0]));
    }
  }
}
