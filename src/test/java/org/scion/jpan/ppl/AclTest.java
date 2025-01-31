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

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.testutil.ExamplePacket;

class AclTest {

  @Test
  void testNewACL() {
    // "No entry":
    {
      PplException e = assertThrows(PplException.class, () -> ACL.create(new ACL.AclEntry[0]));
      assertEquals(ACL.ERR_NO_DEFAULT, e.getMessage());
    }
    // "No default entry":
    {
      ACL.AclEntry ae1 = ACL.AclEntry.create("+ 1-0#0");
      PplException e = assertThrows(PplException.class, () -> ACL.create(ae1));
      assertEquals(ACL.ERR_NO_DEFAULT, e.getMessage());
    }
    // "Entry without rule":
    {
      ACL.AclEntry ae1 = ACL.AclEntry.create("+");
      ACL.create(ae1);
    }

    // "Entry with hop predicates":
    {
      ACL.AclEntry ae1 = ACL.AclEntry.create("+ 1-0#0");
      ACL.AclEntry ae2 = ACL.AclEntry.create("- 0-0#0");
      ACL.create(ae1, ae2);
    }
  }

  //    @Test
  //    void TestUnmarshal(t *testing.T) {
  //        tests := map[string]struct {
  //            Input       string
  //            ExpectedErr error
  //        }{
  //            "No entry": {
  //                Input:       `[]`,
  //                ExpectedErr: ErrNoDefault,
  //            },
  //            "No default entry": {
  //                Input:       `["+ 42"]`,
  //                ExpectedErr: ErrNoDefault,
  //            },
  //            "Entry without rule": {
  //                Input: `["+"]`,
  //            },
  //            "Entry with hop predicates": {
  //                Input: `["+ 42", "-"]`,
  //            },
  //            "Extra entries (first)": {
  //                Input:       `["-", "+ 27"]`,
  //                ExpectedErr: ErrExtraEntries,
  //            },
  //            "Extra entries (in the middle)": {
  //                Input:       `["+ 42", "-", "+ 27", "- 30"]`,
  //                ExpectedErr: ErrExtraEntries,
  //            },
  //        }
  //        for name, test := range tests {
  //            t.Run("json: "+name, func(t *testing.T) {
  //                var acl ACL
  //                err := json.Unmarshal([]byte(test.Input), &acl)
  //                assert.ErrorIs(t, err, test.ExpectedErr)
  //            })
  //            t.Run("yaml: "+name, func(t *testing.T) {
  //                var acl ACL
  //                err := yaml.Unmarshal([]byte(test.Input), &acl)
  //                assert.ErrorIs(t, err, test.ExpectedErr)
  //            })
  //        }
  //    }

  @Test
  void testACLEntryLoadFromString() {
    // "Allow all"
    assertEquals("+ 0-0#0", ACL.AclEntry.create("+ 0").string());
    // "Allow 1-2#3"
    assertEquals("+ 1-2#3", ACL.AclEntry.create("+ 1-2#3").string());
    // "Allow all short"
    assertEquals("+", ACL.AclEntry.create("+").string());
    // "Allow none"
    assertEquals("- 0-0#0", ACL.AclEntry.create("- 0").string());
    // "Bad action symbol"
    PplException e1 = assertThrows(PplException.class, () -> ACL.AclEntry.create("* 0"));
    assertEquals("Bad action symbol: action=*", e1.getMessage());
    // "Bad aclEntry string"
    PplException e2 = assertThrows(PplException.class, () -> ACL.AclEntry.create("+ 0 0"));
    assertEquals("ACLEntry has too many parts: + 0 0", e2.getMessage());
  }

  @Test
  void testACLEntryString() {
    String aclEntryString = "+ 0-0#0";
    ACL.AclEntry aclEntry = ACL.AclEntry.create(aclEntryString);
    assertEquals(aclEntryString, aclEntry.string());
  }

  private static final ACL.AclEntry allowEntry = ACL.AclEntry.create(true, "0");
  private static final ACL.AclEntry denyEntry = ACL.AclEntry.create(false, "0");

  @Test
  void testAclEval() {
    List<Path> input;
    PathProvider pp = new PathProvider();
    ACL acl;

    // "allow everything"
    acl = ACL.createNoValidate(ACL.AclEntry.create(true, "0-0#0"), denyEntry);
    input = getPaths(pp, "2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, acl.eval(input).size());

    // "allow 2-0#0, deny rest"
    acl = ACL.create(ACL.AclEntry.create(true, "2-0#0"), denyEntry);
    input = getPaths(pp, "2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, acl.eval(input).size());

    // "allow 2-ff00:0:212#0 and 2-ff00:0:211, deny rest"
    acl =
        ACL.create(
            ACL.AclEntry.create(true, "2-ff00:0:212#0"),
            ACL.AclEntry.create(true, "2-ff00:0:211"),
            denyEntry);
    input = getPaths(pp, "2-ff00:0:212", "2-ff00:0:211");
    assertEquals(2, acl.eval(input).size());

    // "allow 2-ff00:0:212#0, deny rest"
    acl = ACL.createNoValidate(ACL.AclEntry.create(true, "2-ff00:0:212#0"), denyEntry);
    input = getPaths(pp, "2-ff00:0:212", "2-ff00:0:211");
    assertEquals(0, acl.eval(input).size());

    // "deny 1-ff00:0:110#0, 1-ff00:0:120#0, allow rest"
    acl =
        ACL.create(
            ACL.AclEntry.create(false, "1-ff00:0:110#0"),
            ACL.AclEntry.create(false, "1-ff00:0:120#0"),
            allowEntry);
    input = getPaths(pp, "1-ff00:0:133", "2-ff00:0:222");
    assertEquals(2, acl.eval(input).size());

    // "deny 1-ff00:0:110#0, 1-ff00:0:120#0 and 1-ff00:0:111#2823, allow rest"
    acl =
        ACL.create(
            ACL.AclEntry.create(false, "1-ff00:0:110#0"),
            ACL.AclEntry.create(false, "1-ff00:0:120#0"),
            ACL.AclEntry.create(false, "1-ff00:0:111#2823"),
            allowEntry);
    input = getPaths(pp, "1-ff00:0:133", "2-ff00:0:222");
    assertEquals(1, acl.eval(input).size());

    // "deny ISD1, allow certain ASes"
    acl =
        ACL.create(
            ACL.AclEntry.create(true, "1-ff00:0:120#0"),
            ACL.AclEntry.create(true, "1-ff00:0:130#0"),
            ACL.AclEntry.create(false, "1-0#0"),
            allowEntry);
    input = getPaths(pp, "1-ff00:0:130", "2-ff00:0:220");
    assertEquals(2, acl.eval(input).size());

    // "deny ISD1, allow certain ASes - wrong oder"
    acl =
        ACL.create(
            ACL.AclEntry.create(false, "1-0#0"),
            ACL.AclEntry.create(true, "1-ff00:0:130#0"),
            ACL.AclEntry.create(true, "1-ff00:0:120#0"),
            allowEntry);
    input = getPaths(pp, "1-ff00:0:130", "2-ff00:0:220");
    assertEquals(0, acl.eval(input).size());

    // "nil rule should match all the paths"
    acl = ACL.createNoValidate(ACL.AclEntry.create(false, null), allowEntry);
    input = getPaths(pp, "1-ff00:0:130", "2-ff00:0:220");
    assertEquals(0, acl.eval(input).size());
  }

  @Test
  void testACLPanic() {
    PathProvider pp = new PathProvider();

    // Default ACL action missing
    ACL acl = ACL.createNoValidate(ACL.AclEntry.create(true, "1-0#0"));
    List<Path> input = getPaths(pp, "2-ff00:0:212", "2-ff00:0:211");
    Exception e = assertThrows(PplException.class, () -> acl.eval(input));
    assertEquals("Default ACL action missing", e.getMessage());
  }

  private static List<Path> getPaths(PathProvider pp, String src, String dst) {
    long srcIA = ScionUtil.parseIA(src);
    long dstIA = ScionUtil.parseIA(dst);
    // just use a random address
    InetSocketAddress dstAddress = ExamplePacket.FIRST_HOP;
    return pp.getPaths(dstAddress, srcIA, dstIA);
  }
}
