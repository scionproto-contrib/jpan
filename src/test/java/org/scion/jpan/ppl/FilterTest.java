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

@SuppressWarnings("deprecation")
class FilterTest {

  private static final String ALLOW_STR = "+";
  private static final String DENY_STR = "-";

  @Test
  void testBasicPolicy() {
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
  void testOptionsEval() {
    PathProvider pp = new PathProvider();
    PplPathFilter policy;
    List<Path> paths;

    // "one option, allow everything"
    policy =
        PplPathFilter.builder()
            .addOption(
                0,
                PplExtPolicy.builder()
                    .addAclEntry(true, "0-0#0")
                    .addAclEntry(DENY_STR)
                    .buildNoValidate())
            .build();
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, policy.filter(paths).size());

    // "two options, deny everything"
    policy =
        PplPathFilter.builder()
            .addOption(
                0,
                PplExtPolicy.builder()
                    .addAclEntry(true, "0-0#0")
                    .addAclEntry(DENY_STR)
                    .buildNoValidate())
            .addOption(
                1,
                PplExtPolicy.builder()
                    .addAclEntry(false, "0-0#0")
                    .addAclEntry(DENY_STR)
                    .buildNoValidate())
            .build();
    paths = pp.getPaths("2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, policy.filter(paths).size());

    // "two options, first: allow everything, second: allow one path"
    policy =
        PplPathFilter.builder()
            .setName("")
            .addOption(
                0,
                PplExtPolicy.builder()
                    .addAclEntry(true, "0-0#0")
                    .addAclEntry(DENY_STR)
                    .buildNoValidate())
            .addOption(
                1,
                PplExtPolicy.builder()
                    .addAclEntry(false, "1-ff00:0:110#0")
                    .addAclEntry(false, "1-ff00:0:120#0")
                    .addAclEntry(false, "1-ff00:0:111#2823")
                    .addAclEntry(ALLOW_STR)
                    .build())
            .build();
    paths = pp.getPaths("1-ff00:0:122", "2-ff00:0:222");
    assertEquals(1, policy.filter(paths).size());

    // "two options, combined"
    policy =
        PplPathFilter.builder()
            .addOption(
                0,
                PplExtPolicy.builder()
                    .addAclEntry(false, "1-ff00:0:120#0")
                    .addAclEntries(ALLOW_STR)
                    .build())
            .addOption(
                0,
                PplExtPolicy.builder()
                    .addAclEntry(false, "2-ff00:0:210#0")
                    .addAclEntry(ALLOW_STR)
                    .build())
            .build();
    paths = pp.getPaths("1-ff00:0:110", "2-ff00:0:220");
    assertEquals(3, policy.filter(paths).size());

    // "two options, take first"
    policy =
        PplPathFilter.builder()
            .addOption(
                1,
                PplExtPolicy.builder()
                    .addAclEntry(false, "1-ff00:0:120#0")
                    .addAclEntries(ALLOW_STR)
                    .build())
            .addOption(
                0,
                PplExtPolicy.builder()
                    .addAclEntry(false, "2-ff00:0:210#0")
                    .addAclEntry(ALLOW_STR)
                    .build())
            .build();
    paths = pp.getPaths("1-ff00:0:110", "2-ff00:0:220");
    assertEquals(1, policy.filter(paths).size());

    // "two options, take second"
    policy =
        PplPathFilter.builder()
            .addOption(
                1,
                PplExtPolicy.builder()
                    .addAclEntry(false, "1-ff00:0:120#0")
                    .addAclEntry(ALLOW_STR)
                    .build())
            .addOption(
                10,
                PplExtPolicy.builder()
                    .addAclEntry(false, "2-ff00:0:210#0")
                    .addAclEntry(ALLOW_STR)
                    .build())
            .build();
    paths = pp.getPaths("1-ff00:0:110", "2-ff00:0:220");
    assertEquals(2, policy.filter(paths).size());
  }

  @Test
  void TestExtends() {
    PplExtPolicy policy;
    PplExtPolicy[] extended;
    PplPathFilter extendedPolicy;
    PplPathFilter pol;

    // "one extends, use sub acl"
    policy = PplExtPolicy.builder().addExtension("policy1").buildNoValidate();
    extended =
        new PplExtPolicy[] {
          PplExtPolicy.builder()
              .setName("policy1")
              .addAclEntry(true, "0-0#0")
              .addAclEntry(DENY_STR)
              .buildNoValidate()
        };
    extendedPolicy =
        PplPathFilter.builder().addAclEntry(true, "0-0#0").addAclEntry(DENY_STR).buildNoValidate();

    pol = PplPathFilter.policyFromExtPolicy(policy, extended);
    assertEquals(extendedPolicy, pol);

    // "use option of extended policy"
    policy = PplExtPolicy.builder().addExtension("policy1").buildNoValidate();
    extended =
        new PplExtPolicy[] {
          PplExtPolicy.builder()
              .setName("policy1")
              .addOption(
                  1,
                  PplExtPolicy.builder()
                      .addAclEntry(true, "0-0#0")
                      .addAclEntry(DENY_STR)
                      .buildNoValidate())
              .buildNoValidate()
        };
    extendedPolicy =
        PplPathFilter.builder()
            .addOption(
                1,
                PplExtPolicy.builder()
                    .addAclEntry(true, "0-0#0")
                    .addAclEntry(DENY_STR)
                    .buildNoValidate())
            .build();
    pol = PplPathFilter.policyFromExtPolicy(policy, extended);
    assertEquals(extendedPolicy, pol);

    // "two extends, use sub acl and list"
    policy = PplExtPolicy.builder().addExtension("policy1").buildNoValidate();
    extended =
        new PplExtPolicy[] {
          PplExtPolicy.builder()
              .setName("policy1")
              .addAclEntry(true, "0-0#0")
              .addAclEntry(DENY_STR)
              .setSequence("1-ff00:0:133#1019 1-ff00:0:132#1910")
              .buildNoValidate()
        };
    extendedPolicy =
        PplPathFilter.builder()
            .addAclEntry(true, "0-0#0")
            .addAclEntry(DENY_STR)
            .setSequence("1-ff00:0:133#1019 1-ff00:0:132#1910")
            .buildNoValidate();
    pol = PplPathFilter.policyFromExtPolicy(policy, extended);
    assertEquals(extendedPolicy, pol);

    // "two extends, only use acl"
    policy =
        PplExtPolicy.builder()
            .setSequence("1-ff00:0:133#0 1-ff00:0:132#0")
            .addExtension("policy2")
            .buildNoValidate();
    extended =
        new PplExtPolicy[] {
          PplExtPolicy.builder()
              .setName("policy2")
              .addAclEntry(true, "0-0#0")
              .addAclEntry(DENY_STR)
              .buildNoValidate(),
          PplExtPolicy.builder()
              .setName("policy1")
              .setSequence("1-ff00:0:133#1019 1-ff00:0:132#1910")
              .buildNoValidate()
        };
    extendedPolicy =
        PplPathFilter.builder()
            .addAclEntry(true, "0-0#0")
            .addAclEntry(DENY_STR)
            .setSequence("1-ff00:0:133#0 1-ff00:0:132#0")
            .buildNoValidate();
    pol = PplPathFilter.policyFromExtPolicy(policy, extended);
    assertEquals(extendedPolicy, pol);

    // "three extends, use last list"
    policy = PplExtPolicy.builder().addExtensions("p1", "p2", "p3").build();
    extended =
        new PplExtPolicy[] {
          PplExtPolicy.builder()
              .setName("p1")
              .setSequence("1-ff00:0:133#1011 1-ff00:0:132#1911")
              .build(),
          PplExtPolicy.builder()
              .setName("p2")
              .setSequence("1-ff00:0:133#1012 1-ff00:0:132#1912")
              .build(),
          PplExtPolicy.builder()
              .setName("p3")
              .setSequence("1-ff00:0:133#1013 1-ff00:0:132#1913")
              .build()
        };
    extendedPolicy =
        PplPathFilter.builder().setSequence("1-ff00:0:133#1013 1-ff00:0:132#1913").build();
    pol = PplPathFilter.policyFromExtPolicy(policy, extended);
    assertEquals(extendedPolicy, pol);

    // "nested extends"
    policy = PplExtPolicy.builder().addExtension("policy1").buildNoValidate();
    extended =
        new PplExtPolicy[] {
          PplExtPolicy.builder().setName("policy1").addExtension("policy2").buildNoValidate(),
          PplExtPolicy.builder().setName("policy2").addExtension("policy3").buildNoValidate(),
          PplExtPolicy.builder()
              .setName("policy3")
              .setSequence("1-ff00:0:133#1011 1-ff00:0:132#1911")
              .buildNoValidate()
        };
    extendedPolicy =
        PplPathFilter.builder().setSequence("1-ff00:0:133#1011 1-ff00:0:132#1911").build();
    pol = PplPathFilter.policyFromExtPolicy(policy, extended);
    assertEquals(extendedPolicy, pol);

    // "nested extends, evaluating order"
    policy = PplExtPolicy.builder().addExtension("policy3").build();
    extended =
        new PplExtPolicy[] {
          PplExtPolicy.builder()
              .setName("policy3")
              .setSequence("1-ff00:0:133#1010 1-ff00:0:132#1910")
              .build(),
          PplExtPolicy.builder().addExtension("policy2").build(),
          PplExtPolicy.builder().setName("policy2").addExtensions("policy1").build(),
          PplExtPolicy.builder()
              .setName("policy1")
              .setSequence("1-ff00:0:133#1011 1-ff00:0:132#1911")
              .build()
        };
    extendedPolicy =
        PplPathFilter.builder().setSequence("1-ff00:0:133#1010 1-ff00:0:132#1910").build();
    pol = PplPathFilter.policyFromExtPolicy(policy, extended);
    assertEquals(extendedPolicy, pol);

    // "different nested extends, evaluating order"
    policy = PplExtPolicy.builder().addExtension("policy6").build();
    extended =
        new PplExtPolicy[] {
          PplExtPolicy.builder()
              .setName("policy3")
              .setSequence("1-ff00:0:133#1010 1-ff00:0:132#1910")
              .build(),
          PplExtPolicy.builder().addExtension("policy2").build(),
          PplExtPolicy.builder().setName("policy2").addExtension("policy1").build(),
          PplExtPolicy.builder().setName("policy6").addExtension("policy3").build(),
          PplExtPolicy.builder()
              .setName("policy1")
              .setSequence("1-ff00:0:133#1011 1-ff00:0:132#1911")
              .build()
        };
    extendedPolicy =
        PplPathFilter.builder().setSequence("1-ff00:0:133#1010 1-ff00:0:132#1910").build();
    pol = PplPathFilter.policyFromExtPolicy(policy, extended);
    assertEquals(extendedPolicy, pol);

    // "TestPolicy Extend not found"
    PplExtPolicy errPolicy = PplExtPolicy.builder().addExtension("policy1").build();
    PplExtPolicy[] errExtended =
        new PplExtPolicy[] {
          PplExtPolicy.builder().setName("policy1").addExtension("policy16").build(),
          PplExtPolicy.builder().setName("policy2").addExtension("policy3").build(),
          PplExtPolicy.builder()
              .setName("policy3")
              .setSequence("1-ff00:0:133#1011 1-ff00:0:132#1911")
              .build()
        };
    assertThrows(
        PplException.class, () -> PplPathFilter.policyFromExtPolicy(errPolicy, errExtended));
  }

  @Test
  void TestFilterOpt() {
    PathProvider pp = new PathProvider();
    List<Path> paths = pp.getPaths("1-ff00:0:110", "2-ff00:0:220");

    // "sequence in options is ignored"
    PplExtPolicy optPolicy = PplExtPolicy.builder().setSequence("0+ 1-ff00:0:111 0+").build();
    PplPathFilter policy = PplPathFilter.builder().addOption(0, optPolicy).build();
    List<Path> outPaths = policy.filterOpt(paths, PplPathFilter.FilterOptions.create(true));
    assertEquals(3, outPaths.size());

    // "sequence is ignored"
    policy = PplPathFilter.builder().setSequence("0+ 1-ff00:0:111 0+").build();
    outPaths = policy.filterOpt(paths, PplPathFilter.FilterOptions.create(true));
    assertEquals(3, outPaths.size());
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
