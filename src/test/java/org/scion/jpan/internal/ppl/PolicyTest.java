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

import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.ScionUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PolicyTest {

//    @Test
//    void testBasicPolicy() {
//        tests := map[string]struct {
//            Name       string
//            Policy     *Policy
//            Src        addr.IA
//            Dst        addr.IA
//            ExpPathNum int
//        }{
//            "Empty policy": {
//                Policy:     &Policy{},
//                Src:        addr.MustParseIA("2-ff00:0:212"),
//                        Dst:        addr.MustParseIA("2-ff00:0:211"),
//                        ExpPathNum: 2,
//            },
//        }
//        ctrl := gomock.NewController(t)
//        defer ctrl.Finish()
//        pp := NewPathProvider(ctrl)
//        for name, test := range tests {
//            t.Run(name, func(t *testing.T) {
//                paths := pp.GetPaths(test.Src, test.Dst)
//                outPaths := test.Policy.Filter(paths)
//                assert.Equal(t, test.ExpPathNum, len(outPaths))
//            })
//        }
//    }
//
//    func TestOptionsEval(t *testing.T) {
//        tests := map[string]struct {
//            Policy     *Policy
//            Src        addr.IA
//            Dst        addr.IA
//            ExpPathNum int
//        }{
//            "one option, allow everything": {
//                Policy: NewPolicy("", nil, nil, []Option{
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Allow, Rule: mustHopPredicate(t, "0-0#0")},
//                                    denyEntry,
//                                },
//                            },
//                        },
//                    },
//                        Weight: 0,
//                    },
//                }),
//                Src:        addr.MustParseIA("2-ff00:0:212"),
//                        Dst:        addr.MustParseIA("2-ff00:0:211"),
//                        ExpPathNum: 2,
//            },
//            "two options, deny everything": {
//                Policy: NewPolicy("", nil, nil, []Option{
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Allow, Rule: mustHopPredicate(t, "0-0#0")},
//                                    denyEntry,
//                                },
//                            },
//                        },
//                    },
//                        Weight: 0,
//                    },
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Deny, Rule: mustHopPredicate(t, "0-0#0")},
//                                    denyEntry,
//                                },
//                            },
//                        },
//                    },
//                        Weight: 1,
//                    },
//                }),
//                Src:        addr.MustParseIA("2-ff00:0:212"),
//                        Dst:        addr.MustParseIA("2-ff00:0:211"),
//                        ExpPathNum: 2,
//            },
//            "two options, first: allow everything, second: allow one path": {
//                Policy: NewPolicy("", nil, nil, []Option{
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Allow, Rule: mustHopPredicate(t, "0-0#0")},
//                                    denyEntry,
//                                },
//                            },
//                        },
//                    },
//                        Weight: 0,
//                    },
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Deny, Rule: mustHopPredicate(t, "1-ff00:0:110#0")},
//                                    {Action: Deny, Rule: mustHopPredicate(t, "1-ff00:0:120#0")},
//                                    {Action: Deny, Rule: mustHopPredicate(t, "1-ff00:0:111#2823")},
//                                    allowEntry,
//                                },
//                            },
//                        },
//                    },
//                        Weight: 1,
//                    },
//                }),
//                Src:        addr.MustParseIA("1-ff00:0:122"),
//                        Dst:        addr.MustParseIA("2-ff00:0:222"),
//                        ExpPathNum: 1,
//            },
//            "two options, combined": {
//                Policy: NewPolicy("", nil, nil, []Option{
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Deny, Rule: mustHopPredicate(t, "1-ff00:0:120#0")},
//                                    allowEntry,
//                                },
//                            },
//                        },
//                    },
//                        Weight: 0,
//                    },
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Deny, Rule: mustHopPredicate(t, "2-ff00:0:210#0")},
//                                    allowEntry,
//                                },
//                            },
//                        },
//                    },
//                        Weight: 0,
//                    },
//                }),
//                Src:        addr.MustParseIA("1-ff00:0:110"),
//                        Dst:        addr.MustParseIA("2-ff00:0:220"),
//                        ExpPathNum: 3,
//            },
//            "two options, take first": {
//                Policy: NewPolicy("", nil, nil, []Option{
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Deny, Rule: mustHopPredicate(t, "1-ff00:0:120#0")},
//                                    allowEntry,
//                                },
//                            },
//                        },
//                    },
//                        Weight: 1,
//                    },
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Deny, Rule: mustHopPredicate(t, "2-ff00:0:210#0")},
//                                    allowEntry,
//                                },
//                            },
//                        },
//                    },
//                        Weight: 0,
//                    },
//                }),
//                Src:        addr.MustParseIA("1-ff00:0:110"),
//                        Dst:        addr.MustParseIA("2-ff00:0:220"),
//                        ExpPathNum: 1,
//            },
//            "two options, take second": {
//                Policy: NewPolicy("", nil, nil, []Option{
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Deny, Rule: mustHopPredicate(t, "1-ff00:0:120#0")},
//                                    allowEntry},
//                            },
//                        },
//                    },
//                        Weight: 1,
//                    },
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            ACL: &ACL{
//                                Entries: []*ACLEntry{
//                                    {Action: Deny, Rule: mustHopPredicate(t, "2-ff00:0:210#0")},
//                                    allowEntry},
//                            },
//                        },
//                    },
//                        Weight: 10,
//                    },
//                }),
//                Src:        addr.MustParseIA("1-ff00:0:110"),
//                        Dst:        addr.MustParseIA("2-ff00:0:220"),
//                        ExpPathNum: 2,
//            },
//        }
//        ctrl := gomock.NewController(t)
//        defer ctrl.Finish()
//        pp := NewPathProvider(ctrl)
//        for name, test := range tests {
//            t.Run(name, func(t *testing.T) {
//                paths := pp.GetPaths(test.Src, test.Dst)
//                outPaths := test.Policy.Filter(paths)
//                assert.Equal(t, test.ExpPathNum, len(outPaths))
//            })
//        }
//    }
//
//    func TestExtends(t *testing.T) {
//        tests := map[string]struct {
//            Policy         *ExtPolicy
//            Extended       []*ExtPolicy
//            ExtendedPolicy *Policy
//        }{
//            "one extends, use sub acl": {
//                Policy: &ExtPolicy{
//                    Extends: []string{"policy1"},
//                },
//                Extended: []*ExtPolicy{
//                    {
//                        Policy: &Policy{Name: "policy1",
//                            ACL: &ACL{
//                        Entries: []*ACLEntry{
//                            {Action: Allow, Rule: mustHopPredicate(t, "0-0#0")},
//                            denyEntry,
//                        },
//                    },
//                    },
//                    },
//                },
//                ExtendedPolicy: &Policy{
//                    ACL: &ACL{
//                        Entries: []*ACLEntry{
//                            {Action: Allow, Rule: mustHopPredicate(t, "0-0#0")},
//                            denyEntry,
//                        },
//                    },
//                },
//            },
//            "use option of extended policy": {
//                Policy: &ExtPolicy{
//                    Extends: []string{"policy1"},
//                },
//                Extended: []*ExtPolicy{
//                    {
//                        Policy: &Policy{
//                        Name: "policy1",
//                                Options: []Option{
//                            {
//                                Weight: 1,
//                                        Policy: &ExtPolicy{
//                                Policy: &Policy{
//                                    ACL: &ACL{
//                                        Entries: []*ACLEntry{
//                                            {Action: Allow, Rule: mustHopPredicate(t, "0-0#0")},
//                                            denyEntry,
//                                        },
//                                    },
//                                },
//                            },
//                            },
//                        },
//                    },
//                    },
//                },
//                ExtendedPolicy: &Policy{
//                    Options: []Option{
//                        {
//                            Weight: 1,
//                                    Policy: &ExtPolicy{
//                            Policy: &Policy{
//                                ACL: &ACL{
//                                    Entries: []*ACLEntry{
//                                        {Action: Allow, Rule: mustHopPredicate(t, "0-0#0")},
//                                        denyEntry,
//                                    },
//                                },
//                            },
//                        },
//                        },
//                    },
//                },
//            },
//            "two extends, use sub acl and list": {
//                Policy: &ExtPolicy{Extends: []string{"policy1"}},
//                Extended: []*ExtPolicy{
//                    {
//                        Policy: &Policy{
//                        Name: "policy1",
//                                ACL: &ACL{
//                            Entries: []*ACLEntry{
//                                {
//                                    Action: Allow,
//                                            Rule:   mustHopPredicate(t, "0-0#0"),
//                                },
//                                denyEntry,
//                            },
//                        },
//                        Sequence: newSequence(t, "1-ff00:0:133#1019 1-ff00:0:132#1910"),
//                    },
//                    },
//                },
//                ExtendedPolicy: &Policy{
//                    ACL: &ACL{
//                        Entries: []*ACLEntry{
//                            {
//                                Action: Allow,
//                                        Rule:   mustHopPredicate(t, "0-0#0"),
//                            },
//                            denyEntry,
//                        },
//                    },
//                    Sequence: newSequence(t, "1-ff00:0:133#1019 1-ff00:0:132#1910"),
//                },
//            },
//            "two extends, only use acl": {
//                Policy: &ExtPolicy{
//                    Policy: &Policy{
//                        Sequence: newSequence(t, "1-ff00:0:133#0 1-ff00:0:132#0"),
//                    },
//                    Extends: []string{"policy2"},
//                },
//                Extended: []*ExtPolicy{
//                    {
//                        Policy: &Policy{
//                        Name: "policy2",
//                                ACL: &ACL{
//                            Entries: []*ACLEntry{
//                                {
//                                    Action: Allow,
//                                            Rule:   mustHopPredicate(t, "0-0#0")},
//                                denyEntry,
//                            },
//                        },
//                    },
//                    },
//                    {
//                        Policy: &Policy{
//                        Name:     "policy1",
//                                Sequence: newSequence(t, "1-ff00:0:133#1019 1-ff00:0:132#1910"),
//                    },
//                    },
//                },
//                ExtendedPolicy: &Policy{
//                    ACL: &ACL{
//                        Entries: []*ACLEntry{
//                            {Action: Allow, Rule: mustHopPredicate(t, "0-0#0")},
//                            denyEntry,
//                        },
//                    },
//                    Sequence: newSequence(t, "1-ff00:0:133#0 1-ff00:0:132#0"),
//                },
//            },
//            "three extends, use last list": {
//                Policy: &ExtPolicy{
//                    Extends: []string{"p1", "p2", "p3"},
//                },
//                Extended: []*ExtPolicy{
//                    {
//                        Policy: &Policy{
//                        Name:     "p1",
//                                Sequence: newSequence(t, "1-ff00:0:133#1011 1-ff00:0:132#1911"),
//                    },
//                    },
//                    {
//                        Policy: &Policy{
//                        Name:     "p2",
//                                Sequence: newSequence(t, "1-ff00:0:133#1012 1-ff00:0:132#1912"),
//                    },
//                    },
//                    {
//                        Policy: &Policy{
//                        Name:     "p3",
//                                Sequence: newSequence(t, "1-ff00:0:133#1013 1-ff00:0:132#1913"),
//                    },
//                    },
//                },
//                ExtendedPolicy: &Policy{
//                    Sequence: newSequence(t, "1-ff00:0:133#1013 1-ff00:0:132#1913")},
//            },
//            "nested extends": {
//                Policy: &ExtPolicy{
//                    Extends: []string{"policy1"},
//                },
//                Extended: []*ExtPolicy{
//                    {
//                        Policy:  &Policy{Name: "policy1"},
//                        Extends: []string{"policy2"},
//                    },
//                    {
//                        Policy:  &Policy{Name: "policy2"},
//                        Extends: []string{"policy3"},
//                    },
//                    {
//                        Policy: &Policy{
//                        Name:     "policy3",
//                                Sequence: newSequence(t, "1-ff00:0:133#1011 1-ff00:0:132#1911"),
//                    },
//                    },
//                },
//                ExtendedPolicy: &Policy{
//                    Sequence: newSequence(t, "1-ff00:0:133#1011 1-ff00:0:132#1911")},
//            },
//            "nested extends, evaluating order": {
//                Policy: &ExtPolicy{
//                    Extends: []string{"policy3"},
//                },
//                Extended: []*ExtPolicy{
//                    {
//                        Policy: &Policy{
//                        Name:     "policy3",
//                                Sequence: newSequence(t, "1-ff00:0:133#1010 1-ff00:0:132#1910"),
//                    },
//                        Extends: []string{"policy2"},
//                    },
//                    {
//                        Policy:  &Policy{Name: "policy2"},
//                        Extends: []string{"policy1"},
//                    },
//                    {
//                        Policy: &Policy{
//                        Name:     "policy1",
//                                Sequence: newSequence(t, "1-ff00:0:133#1011 1-ff00:0:132#1911"),
//                    },
//                    },
//                },
//                ExtendedPolicy: &Policy{
//                    Sequence: newSequence(t, "1-ff00:0:133#1010 1-ff00:0:132#1910")},
//            },
//            "different nested extends, evaluating order": {
//                Policy: &ExtPolicy{
//                    Extends: []string{"policy6"},
//                },
//                Extended: []*ExtPolicy{
//                    {
//                        Policy: &Policy{
//                        Name:     "policy3",
//                                Sequence: newSequence(t, "1-ff00:0:133#1010 1-ff00:0:132#1910"),
//                    },
//                        Extends: []string{"policy2"},
//                    },
//                    {
//                        Policy:  &Policy{Name: "policy2"},
//                        Extends: []string{"policy1"},
//                    },
//                    {
//                        Policy:  &Policy{Name: "policy6"},
//                        Extends: []string{"policy3"},
//                    },
//                    {
//                        Policy: &Policy{
//                        Name:     "policy1",
//                                Sequence: newSequence(t, "1-ff00:0:133#1011 1-ff00:0:132#1911"),
//                    },
//                    },
//                },
//                ExtendedPolicy: &Policy{
//                    Sequence: newSequence(t, "1-ff00:0:133#1010 1-ff00:0:132#1910")},
//            },
//        }
//
//        for name, test := range tests {
//            t.Run(name, func(t *testing.T) {
//                pol, err := PolicyFromExtPolicy(test.Policy, test.Extended)
//                if assert.NoError(t, err) {
//                    assert.Equal(t, test.ExtendedPolicy, pol)
//                }
//            })
//        }
//
//        t.Run("TestPolicy Extend not found", func(t *testing.T) {
//            extPolicy := &ExtPolicy{Extends: []string{"policy1"}}
//            extended := []*ExtPolicy{
//                {
//                    Policy:  &Policy{Name: "policy1"},
//                    Extends: []string{"policy16"},
//                },
//                {
//                    Policy:  &Policy{Name: "policy2"},
//                    Extends: []string{"policy3"},
//                },
//                {
//                    Policy: &Policy{
//                    Name:     "policy3",
//                            Sequence: newSequence(t, "1-ff00:0:133#1011 1-ff00:0:132#1911"),
//                },
//                },
//            }
//            _, err := PolicyFromExtPolicy(extPolicy, extended)
//            assert.Error(t, err)
//        })
//    }

    @Test
    void TestFilterOpt() {
//        tests := map[string]struct {
//            Policy     *Policy
//            ExpPathNum int
//        }{
        PathProvider pp = new PathProvider();
        List<Path> paths = pp.getPaths("1-ff00:0:110", "2-ff00:0:220");

        // "sequence in options is ignored"
        PplPolicy policy = PplPolicy.create("", null, null,
                PplPolicy.Option.create(
                        0,
                        PplPolicy.ExtPolicy.createExt(null, null, Sequence.create("0+ 1-ff00:0:111 0+"), null)));
        List<Path> outPaths = policy.filterOpt(paths, PplPolicy.FilterOptions.create(true));
        assertEquals(3, outPaths.size());

//                Policy: NewPolicy("", nil, nil, []Option{
//                    {
//                        Policy: &ExtPolicy{
//                        Policy: &Policy{
//                            Sequence: newSequence(t, "0+ 1-ff00:0:111 0+"),
//                        },
//                    },
//                        Weight: 0,
//                    },
//                }),
//                ExpPathNum: 3,
//            },

        // "sequence is ignored":
        policy = PplPolicy.create("", null, newSequence("0+ 1-ff00:0:111 0+"), null);
        outPaths = policy.filterOpt(paths, PplPolicy.FilterOptions.create(true));
        assertEquals(3, outPaths.size());

//                Policy:     NewPolicy("", nil, newSequence(t, "0+ 1-ff00:0:111 0+"), nil),
//                        ExpPathNum: 3,
//            },
//        }
//        ctrl := gomock.NewController(t)
//        defer ctrl.Finish()
//        pp := NewPathProvider(ctrl)
//        src := addr.MustParseIA("1-ff00:0:110")
//        dst := addr.MustParseIA("2-ff00:0:220")
//        paths := pp.GetPaths(src, dst)
//        for name, test := range tests {
//            t.Run(name, func(t *testing.T) {
//                outPaths := test.Policy.FilterOpt(paths, FilterOptions{IgnoreSequence: true})
//                assert.Equal(t, test.ExpPathNum, len(outPaths))
//            })
//        }
    }

    @Test
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

    private Sequence newSequence(String str) {
        return Sequence.create(str);
    }

//    func (p PathProvider) GetPaths(src, dst addr.IA) []snet.Path {
//        result := []snet.Path{}
//        paths := p.g.GetPaths(src.String(), dst.String())
//        for _, ifIDs := range paths {
//            pathIntfs := make([]snet.PathInterface, 0, len(ifIDs))
//            for _, ifID := range ifIDs {
//                ia := p.g.GetParent(ifID)
//                pathIntfs = append(pathIntfs, snet.PathInterface{
//                    IA: ia,
//                            ID: iface.ID(ifID),
//                })
//            }
//            var srcIA, dstIA addr.IA
//            if len(pathIntfs) > 0 {
//                srcIA = pathIntfs[0].IA
//                dstIA = pathIntfs[len(pathIntfs)-1].IA
//            }
//            result = append(result, snetpath.Path{
//                Src: srcIA,
//                        Dst: dstIA,
//                        Meta: snet.PathMetadata{
//                    Interfaces: pathIntfs,
//                },
//            })
//        }
//        return result
//    }
//
//    func mustHopPredicate(t *testing.T, str string) *HopPredicate {
//        hp, err := HopPredicateFromString(str)
//        require.NoError(t, err)
//        return hp
//    }

}
