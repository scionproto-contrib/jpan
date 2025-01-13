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

import java.util.ArrayList;
import java.util.List;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;

public class ACL {

  // ErrNoDefault indicates that there is no default acl entry.
  private static final String ErrNoDefault = "ACL does not have a default";
  // ErrExtraEntries indicates that there extra entries after the default entry.
  private static final String ErrExtraEntries =
      "ACL has unused extra entries after a default entry";

  private AclEntry[] entries;

  // NewACL creates a new entry and checks for the presence of a default action
  static ACL newACL(AclEntry... entries) {
    validateACL(entries);
    return new ACL(entries);
  }

  private ACL(AclEntry... entries) {
    this.entries = entries;
  }

  // Eval returns the set of paths that match the ACL.
  List<Path> eval(List<Path> paths) {
    if (this == null || entries.length == 0) {
      return paths;
    }
    List<Path> result = new ArrayList<>();
    for (Path path : paths) {
      // Check ACL
      if (evalPath(path.getMetadata()) == AclAction.Allow) {
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
      if (evalInterface(iface, i % 2 != 0) == Deny) {
        return Deny;
      }
    }
    return Allow;
  }

  AclAction evalInterface(PathMetadata.PathInterface iface, boolean ingress) {
    for (AclEntry aclEntry : entries) {
      if (aclEntry.rule == null || aclEntry.rule.pathIFMatch(iface, ingress)) {
        return aclEntry.action;
      }
    }
    throw new PPLException("Default ACL action missing");
  }

  private static void validateACL(AclEntry[] entries) {
    if (entries.length == 0) {
      throw new PPLException(ErrNoDefault);
    }

    int foundAt = -1;
    for (int i = 0; i < entries.length; i++) {
      if (entries[i].rule.matchesAll()) {
        foundAt = i;
        break;
      }
    }

    if (foundAt < 0) {
      throw new PPLException(ErrNoDefault);
    }

    if (foundAt != entries.length - 1) {
      throw new PPLException(ErrExtraEntries);
    }
  }

  static class AclEntry {
    AclAction action;
    HopPredicate rule;

    void loadFromString(String str) {
      String[] parts = str.split(" ");
      if (parts.length == 1) {
        action = getAction(parts[0]);
        return;
      } else if (parts.length == 2) {
        action = getAction(parts[0]);
        rule = HopPredicate.create().HopPredicateFromString(parts[1]);
        return;
      }
      throw new PPLException("ACLEntry has too many parts: " + str);
    }

    String String() {
      String str = denySymbol;
      if (action == Allow) {
        str = allowSymbol;
      }
      if (rule != null) {
        str = str + " " + rule.string();
      }
      return str;
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

  }

  private static AclAction getAction(String symbol) {
    if (allowSymbol.equals(symbol)) {
      return AclAction.Allow;
    } else if (denySymbol.equals(denySymbol)) {
      return AclAction.Deny;
    } else {
      throw new PPLException("Bad action symbol: " + "action=" + symbol);
    }
  }

  // ACLAction has two options: Deny and Allow
  enum AclAction {
    Deny,
    Allow
  }

  private static final AclAction Deny = AclAction.Deny;
  private static final AclAction Allow = AclAction.Allow;
  private static final String denySymbol = "-";
  private static final String allowSymbol = "+";
}
