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

public class PpldPathPolicy {

  public static class HopParseException extends IllegalArgumentException {
    public HopParseException(String str) {
      super(str);
    }

    public HopParseException(String str, Throwable t) {
      super(str, t);
    }
  }

  // NewPolicy creates a Policy and sorts its Options
  public static Policy newPolicy(
      String name, ACL acl, Sequence sequence, Policy.Option... options) {
    return Policy.newPolicy(name, acl, sequence, options);
  }
}
