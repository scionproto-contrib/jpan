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
import org.scion.jpan.ScionUtil;

public class RemoteIsdAs {
  // RemoteISDAS is a path policy that checks whether the last hop in the path (remote AS) satisfies
  // the supplied rules. Rules are evaluated in order and first that matches the remote ISD-AS wins.
  // If in the winnig rule Reject is set to true, then the path will rejected by the policy,
  // otherwise it will be accepted. If no rule matches the path will be rejected.
  private IsdAsRule[] rules;

  // TODO rename to Rule?
  private static class IsdAsRule {
    long isdAs; //     addr.IA `json:"isd_as,omitempty"`
    boolean reject; //    `json:"reject,omitempty"`
  }

  List<Path> eval(List<Path> paths) {
    List<Path> result = new ArrayList<>();
    for (Path path : paths) {
      if (path.getMetadata().getInterfacesList().isEmpty()) {
        continue;
      }
      long ia = path.getRemoteIsdAs();
      for (IsdAsRule rule : rules) {
        if (matchISDAS(rule.isdAs, ia)) {
          if (!rule.reject) {
            result.add(path);
          }
          break;
        }
      }
    }
    return result;
  }

  private boolean matchISDAS(long rule, long ia) {
    int ruleIsd = ScionUtil.extractIsd(rule);
    if (ruleIsd != 0 && ruleIsd != ScionUtil.extractIsd(ia)) {
      return false;
    }
    long ruleAs = ScionUtil.extractAs(rule);
    if (ruleAs != 0 && ruleAs != ScionUtil.extractAs(ia)) {
      return false;
    }
    return true;
  }

  //    JsonWriter MarshalJSON(JsonWriter json) {
  //        return json.write(ri.Rules);
  //    }
  //
  //    JsonReader UnmarshalJSON(JsonReader reader) {
  //        return json.Unmarshal(b, ri.Rules);
  //    }

}
