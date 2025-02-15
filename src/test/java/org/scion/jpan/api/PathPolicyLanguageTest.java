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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.ppl.PplException;
import org.scion.jpan.ppl.PplExtPolicy;
import org.scion.jpan.ppl.PplPolicy;
import org.scion.jpan.testutil.ExamplePacket;

class PathPolicyLanguageTest {

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
    assertTrue(ppl.filter(paths).isEmpty());

    String str = PplPolicy.getSequence(ExamplePacket.PATH_IPV4); // TODO do we need this?
    assertEquals("", str);
  }

  @Test
  void aclFail_extraEntries() {
    PplPolicy.Builder builder = PplPolicy.builder();
    assertThrows(
        PplException.class, () -> builder.addAclEntries("ext-acl1-fsd", "ext-acl2-fsd dsf"));
  }

  @Test
  void aclFail_noDefault() {
    PplPolicy.Builder builder = PplPolicy.builder();
    assertThrows(PplException.class, () -> builder.addAclEntries(new String[0]));
  }

  @Test
  void aclFail_badISD() {
    PplPolicy.Builder builder = PplPolicy.builder();
    assertThrows(PplException.class, () -> builder.addAclEntry("ext-acl1-fsd sss"));
  }

  @Test
  void aclFail_badLast() {
    PplPolicy.Builder builder = PplPolicy.builder();
    assertThrows(PplException.class, () -> builder.addAclEntry("ext-acl1-fsd"));
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

  @Test
  void json() {
    String json =
        "{\n"
            + "  \"global\": {\n"
            + "    \"acl\": [\n"
            + "      \"+ 1-ff00:0:111\",\n"
            + "      \"+ 1-ff00:0:112\",\n"
            + "      \"- 1\",\n"
            + "      \"+\"\n"
            + "    ]\n"
            + "  },\n"
            + "  \"1-110,10.0.0.1\": {\n"
            + "    \"acl\": [\n"
            + "      \"+ 1-ff00:0:133\",\n"
            + "      \"+ 1-ff00:0:120\",\n"
            + "      \"- 1\",\n"
            + "      \"+\"\n"
            + "    ]\n"
            + "  },\n"
            + "    \"1-110,10.0.0.2\": {\n"
            + "      \"sequence\" : \"1-ff00:0:133#0 1-ff00:0:120#2,1 0 0 1-ff00:0:110#0\"\n"
            + "    }\n"
            + "}";
    PplPolicy ppl = PplPolicy.fromJson(json);
    // TODO
  }

  @Test
  void jsonSingle() {
    String json =
        "{\n"
            + "  \"options\": [\n"
            + "    {\n"
            + "      \"weight\":0,\n"
            + "      \"policy\": {\n"
            + "        \"acl\": [\n"
            + "          \"+ 42-0#0\",\n"
            + "          \"- 0-0#0\"\n"
            + "        ],\n"
            + "        \"local_isd_ases\": [\n"
            + "          \"64-123\",\n"
            + "          \"70-ff00:102:304\"\n"
            + "        ],\n"
            + "        \"remote_isd_ases\": [\n"
            + "          {\n"
            + "            \"isd_as\":\"64-123\",\n"
            + "            \"reject\":true\n"
            + "          }\n"
            + "        ]\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}\n";
    // TODO
  }
}
