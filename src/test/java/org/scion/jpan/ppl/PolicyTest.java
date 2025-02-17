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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;

class PolicyTest {

  private static final String ALLOW_STR = "+";
  private static final String DENY_STR = "-";

  @Test
  void testBasicFilter() {
    PathProvider pp = new PathProvider();
    List<Path> paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");

    // "Empty policy"
    // Unlike the scionproto, we forbid empty policies.
    assertThrows(PplException.class, () -> PplPathFilter.builder().build());

    // "Empty policy" with "true"
    PplPathFilter policyTrue = PplPathFilter.builder().addAclEntry(true, null).build();
    List<Path> outPathsTrue = policyTrue.filter(paths);
    assertEquals(2, outPathsTrue.size());

    // "Empty policy" with "false"
    PplPathFilter policyFalse = PplPathFilter.builder().addAclEntry(false, null).build();
    List<Path> outPathsFalse = policyFalse.filter(paths);
    assertEquals(0, outPathsFalse.size());
  }

  @Test
  void testBasicPolicy() {
    // "Empty policy"
    // Unlike the scionproto, we forbid empty policies.
    assertThrows(PplException.class, () -> PplPolicy.builder().build());
  }

  @Test
  void testOptionsEval() {
    PathProvider pp = new PathProvider();
    PplPolicy policy;
    List<Path> paths;

    // "one option, allow everything"
    policy =
        PplPolicy.builder()
            .add(
                "0",
                PplPathFilter.builder()
                    .addAclEntry(true, "0-0#0")
                    .addAclEntry(DENY_STR)
                    .buildNoValidate())
            .build();
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, policy.filter(paths).size());

    // "two options, deny everything"
    policy =
        PplPolicy.builder()
            .add(
                "0",
                PplPathFilter.builder()
                    .addAclEntry(true, "0-0#0")
                    .addAclEntry(DENY_STR)
                    .buildNoValidate())
            .add(
                "0",
                PplPathFilter.builder()
                    .addAclEntry(false, "0-0#0")
                    .addAclEntry(DENY_STR)
                    .buildNoValidate())
            .build();
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, policy.filter(paths).size());

    // "two options, first: allow everything, second: allow one path"
    policy =
        PplPolicy.builder()
            .add(
                "5",
                PplPathFilter.builder()
                    .addAclEntry(true, "0-0#0")
                    .addAclEntry(DENY_STR)
                    .buildNoValidate())
            .add(
                "0",
                PplPathFilter.builder()
                    .addAclEntry(false, "1-ff00:0:110#0")
                    .addAclEntry(false, "1-ff00:0:120#0")
                    .addAclEntry(false, "1-ff00:0:111#2823")
                    .addAclEntry(ALLOW_STR)
                    .build())
            .build();
    paths = pp.getPaths("1-ff00:0:122", "2-ff00:0:222");
    assertEquals(1, policy.filter(paths).size());

    // TODO this does currently not work. Do we want this?
    //   We could allow a _list_ of filters per destination. But how are they applied?
    //   - Allow only paths that pass all filters?
    //   - Allow all paths that pass at least one filter?
    //   - Look at what scionproto claims: try all filters until it finds one that matches...
    //     In practice it seems to allow any path that passes at least one filter.
    //   - We could also provide a more complex logic that allow combining filters with && and ||.
    // "two options, combined"
    //    policy =
    //        PplPolicy.builder()
    //            .add(
    //                "0",
    //                PplPathFilter.builder()
    //                    .addAclEntry(false, "1-ff00:0:120#0")
    //                    .addAclEntries(ALLOW_STR)
    //                    .build(),
    //                PplPathFilter.builder()
    //                    .addAclEntry(false, "2-ff00:0:210#0")
    //                    .addAclEntry(ALLOW_STR)
    //                    .build())
    //            .build();
    //    paths = pp.getPaths("1-ff00:0:110", "2-ff00:0:220");
    //    for (Path path : paths) {
    //      System.out.println("path: " + ScionUtil.toStringPath(path.getMetadata()));
    //    }
    //    assertEquals(3, policy.filter(paths).size());

    // "two options, take first"
    policy =
        PplPolicy.builder()
            .add(
                "0",
                PplPathFilter.builder()
                    .addAclEntry(false, "1-ff00:0:120#0")
                    .addAclEntries(ALLOW_STR)
                    .build())
            .add(
                "0",
                PplPathFilter.builder()
                    .addAclEntry(false, "2-ff00:0:210#0")
                    .addAclEntry(ALLOW_STR)
                    .build())
            .build();
    paths = pp.getPaths("1-ff00:0:110", "2-ff00:0:220");
    assertEquals(1, policy.filter(paths).size());

    // "two options, take second"
    policy =
        PplPolicy.builder()
            .add(
                "5",
                PplPathFilter.builder()
                    .addAclEntry(false, "1-ff00:0:120#0")
                    .addAclEntry(ALLOW_STR)
                    .build())
            .add(
                "2",
                PplPathFilter.builder()
                    .addAclEntry(false, "2-ff00:0:210#0")
                    .addAclEntry(ALLOW_STR)
                    .build())
            .build();
    paths = pp.getPaths("1-ff00:0:110", "2-ff00:0:220");
    assertEquals(2, policy.filter(paths).size());
  }

  @Test
  @Disabled
  void testPolicyJsonConversion() {
    //        acl, err := NewACL(
    //                &ACLEntry{Action: Allow, Rule: mustHopPredicate(t, "42-0#0")},
    //        denyEntry,
    //	)
    //        require.NoError(t, err)
    //
    //        policy := NewPolicy("", nil, nil, []Option{
    //            {
    //                Policy: &ExtPolicy{
    //                Policy: &Policy{
    //                    ACL: acl,
    //                            LocalISDAS: &LocalISDAS{
    //                        AllowedIAs: []addr.IA{
    //                            addr.MustParseIA("64-123"),
    //                                    addr.MustParseIA("70-ff00:0102:0304"),
    //                        },
    //                    },
    //                    RemoteISDAS: &RemoteISDAS{
    //                        Rules: []ISDASRule{
    //                            {
    //                                IA:     addr.MustParseIA("64-123"),
    //                                        Reject: true,
    //                            },
    //                        },
    //                    },
    //                },
    //            },
    //                Weight: 0,
    //            },
    //        })
    //        jsonPol, err := json.Marshal(policy)
    //        require.NoError(t, err)
    //        var pol Policy
    //                err = json.Unmarshal(jsonPol, &pol)
    //        assert.NoError(t, err)
    //        assert.Equal(t, policy, &pol)
  }
}
