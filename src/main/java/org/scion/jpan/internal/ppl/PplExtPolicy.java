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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * ExtPolicy is an extending policy, it may have a list of policies it extends.
 *
 * @deprecated Use with caution, this API is unstable. See <a
 *     href="https://github.com/scionproto/scion/issues/4687">#4687</a>
 */
@Deprecated
public class PplExtPolicy extends PplPolicy {
  private final String[] extensions; // []string `json:"extends,omitempty"`

  private PplExtPolicy(
      String name, ACL acl, Sequence sequence, String[] extensions, Option... options) {
    super(name, acl, sequence, options);
    this.extensions = extensions;
  }

  static PplExtPolicy createExt(
      String name, ACL acl, Sequence sequence, String[] extensions, Option... options) {
    return new PplExtPolicy(name, acl, sequence, extensions, options);
  }

  public static Builder builder() {
    return new Builder();
  }

  public String[] getExtensions() {
    return extensions;
  }

  public static class Builder extends PplPolicy.Builder {
    private final List<String> extensions = new ArrayList<>();

    public Builder addExtension(String extension) {
      this.extensions.add(extension);
      return this;
    }

    public Builder addExtensions(String... extensions) {
      for (String extension : extensions) {
        addExtension(extension);
      }
      return this;
    }

    @Override
    public Builder setName(String name) {
      super.setName(name);
      return this;
    }

    @Override
    public Builder addAclEntry(String str) {
      super.addAclEntry(str);
      return this;
    }

    @Override
    public Builder addAclEntries(String... strings) {
      super.addAclEntries(strings);
      return this;
    }

    @Override
    public Builder addAclEntry(boolean allow, String hopFieldPredicate) {
      super.addAclEntry(allow, hopFieldPredicate);
      return this;
    }

    @Override
    public Builder setSequence(String sequence) {
      super.setSequence(sequence);
      return this;
    }

    @Override
    public Builder addOption(int weight, PplExtPolicy policy) {
      super.addOption(weight, policy);
      return this;
    }

    @Override
    public PplExtPolicy build() {
      ACL acl = entries.isEmpty() ? null : ACL.create(entries.toArray(new ACL.AclEntry[0]));
      return new PplExtPolicy(
          name, acl, sequence, extensions.toArray(new String[0]), options.toArray(new Option[0]));
    }

    @Override
    PplExtPolicy buildNoValidate() {
      ACL acl =
          entries.isEmpty() ? null : ACL.createNoValidate(entries.toArray(new ACL.AclEntry[0]));
      return new PplExtPolicy(
          name, acl, sequence, extensions.toArray(new String[0]), options.toArray(new Option[0]));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    PplExtPolicy that = (PplExtPolicy) o;
    return Objects.deepEquals(extensions, that.extensions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), Arrays.hashCode(extensions));
  }

  @Override
  public String toString() {
    return "PplExtPolicy{"
        + "extensions="
        + Arrays.toString(extensions)
        + ", "
        + super.toString()
        + '}';
  }
}
