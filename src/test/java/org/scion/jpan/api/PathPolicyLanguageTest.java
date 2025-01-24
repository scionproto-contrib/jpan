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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.internal.ppl.ACL;
import org.scion.jpan.internal.ppl.PplException;
import org.scion.jpan.internal.ppl.PplExtPolicy;
import org.scion.jpan.internal.ppl.PplPolicy;
import org.scion.jpan.internal.ppl.Sequence;
import org.scion.jpan.testutil.ExamplePacket;

class PathPolicyLanguageTest {

  @Test
  void smokeTest() {
    ACL extAcl =
        ACL.create(
            ACL.AclEntry.create("+ 1-ff00:0:133"),
            ACL.AclEntry.create("+ 1-ff00:0:120"),
            ACL.AclEntry.create("- 1"),
            ACL.AclEntry.create("+"));
    Sequence extSequence = Sequence.create("1-ff00:0:133#0 1-ff00:0:120#2,1 0 0 1-ff00:0:110#0");
    String[] extensions = {"hello", "you"};
    PplExtPolicy ext = PplExtPolicy.createExt("myExtPolicy", extAcl, extSequence, extensions);

    ACL acl = ACL.create("+ 1-ff00:0:133", "+ 1-ff00:0:120", "- 1", "+");
    Sequence sequence = Sequence.create("1-ff00:0:133#1 1+ 2-ff00:0:1? 2-ff00:0:233#1");
    PplPolicy.Option option = PplPolicy.Option.create(15, ext);

    PplPolicy ppl = PplPolicy.create("My policy", acl, sequence, option);
    List<Path> paths = new ArrayList<>();
    paths.add(ExamplePacket.PATH_IPV4);
    assertThrows(NoSuchElementException.class, () -> ppl.filter(paths));

    String str = Sequence.getSequence(ExamplePacket.PATH_IPV4); // TODO do we need this?
    assertEquals("", str);
  }

  @Test
  void smokeTestBuilder() {
    PplPolicy ppl =
        PplPolicy.builder()
            .setName("My policy")
            .addAclEntries("+ 1-ff00:0:133", "+ 1-ff00:0:120", "- 1", "+")
            .setSequence("1-ff00:0:133#1 1+ 2-ff00:0:1? 2-ff00:0:233#1")
            .addOption(
                15,
                PplExtPolicy.builder()
                    .setName("myExtPolicy")
                    .addAclEntry("+ 1-ff00:0:133")
                    .addAclEntry(true, "1-ff00:0:120")
                    .addAclEntry(false, "1")
                    .addAclEntry("+")
                    .setSequence("1-ff00:0:133#0 1-ff00:0:120#2,1 0 0 1-ff00:0:110#0")
                    .addExtension("hello")
                    .addExtension("you")
                    .build())
            .build();

    List<Path> paths = new ArrayList<>();
    paths.add(ExamplePacket.PATH_IPV4);
    assertThrows(NoSuchElementException.class, () -> ppl.filter(paths));

    String str = Sequence.getSequence(ExamplePacket.PATH_IPV4); // TODO do we need this?
    assertEquals("", str);
  }

  @Test
  void aclFail_extraEntries() {
    assertThrows(PplException.class, () -> ACL.create("ext-acl1-fsd", "ext-acl2-fsd dsf"));
  }

  @Test
  void aclFail_noDefault() {
    assertThrows(PplException.class, () -> ACL.create(new String[0]));
  }

  @Test
  void aclFail_badISD() {
    assertThrows(PplException.class, () -> ACL.create("ext-acl1-fsd sss"));
  }

  @Test
  void aclFail_badLast() {
    assertThrows(PplException.class, () -> ACL.create("ext-acl1-fsd"));
  }

  @Test
  void aclFail_badPredicate() {
    PplPolicy.Builder ppl = PplPolicy.builder();
    // unnecessary "+"
    Exception e = assertThrows(PplException.class, () -> ppl.addAclEntry(true, "+ 1-ff00:0:133"));
    assertTrue(e.getMessage().startsWith("Failed to parse "));
  }

  @Test
  void acl1a() {
    PplPolicy ppl =
        PplPolicy.builder()
            .setName("My policy")
            .addAclEntries("+ 1-ff00:0:133", "+ 1-ff00:0:120", "- 1", "+")
            .build();
    testPath(ppl);
  }

  @Test
  void acl1b() {
    PplPolicy ppl =
        PplPolicy.builder()
            .setName("My policy")
            .addAclEntry("+ 1-ff00:0:133")
            .addAclEntry("+ 1-ff00:0:120")
            .addAclEntry("- 1")
            .addAclEntry("+")
            .build();
    testPath(ppl);
  }

  @Test
  void acl1c() {
    PplPolicy ppl =
        PplPolicy.builder()
            .setName("My policy")
            .addAclEntry(true, "1-ff00:0:133")
            .addAclEntry(true, "1-ff00:0:120")
            .addAclEntry(false, "1")
            .addAclEntry(true, null)
            .build();
    testPath(ppl);
  }

  private void testPath(PplPolicy ppl) {
    List<Path> paths = new ArrayList<>();
    paths.add(ExamplePacket.PATH_IPV4);
    Path p = ppl.filter(paths).get(0);
    assertEquals(ExamplePacket.PATH_IPV4, p);
  }
}
