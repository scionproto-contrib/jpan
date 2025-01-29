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

import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.internal.IPHelper;
import org.scion.jpan.internal.ppl.PplPolicyGroup;
import org.scion.jpan.proto.daemon.Daemon;

class PathPolicyLanguageGroupTest {

  @Test
  void groupSmokeTest() {
    PplPolicyGroup group = PplPolicyGroup.fromJson(getPath("ppl/pplGroup.json"));
    // pp.getPaths(ScionUtil.parseIA("1-ff00:0:110"),
    List<Path> paths = new ArrayList<>();
    InetSocketAddress addr1 = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createRequestPath(addr1, "1-ff00:0:110", "1", "2", "1-ff00:0:111"));

    List<Path> filteredPaths = group.filter(paths);
    assertEquals(1, filteredPaths.size());
  }

  @Test
  void group_defaultOnly() {
    PplPolicyGroup group = PplPolicyGroup.fromJson(getPath("ppl/pplGroup_trivial.json"));
    // pp.getPaths(ScionUtil.parseIA("1-ff00:0:110"),
    List<Path> paths = new ArrayList<>();
    InetSocketAddress addr1 = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createRequestPath(addr1, "1-ff00:0:110", "1", "2", "1-ff00:0:111"));

    List<Path> filteredPaths = group.filter(paths);
    assertEquals(1, filteredPaths.size());
  }

  private RequestPath createRequestPath(InetSocketAddress addr, String... str) {
    Daemon.Path protoPath = createProtoPath(str);
    long dstIsdAs = ScionUtil.parseIA(str[str.length - 1]);
    return PackageVisibilityHelper.createRequestPath(protoPath, dstIsdAs, addr);
  }

  /**
   * @param str E.g. "1-ff00:0:110", 1, 2, "1-ff00:0:111", 3, 4, "1-ff00:0:112"
   * @return A path with Metadata but WITHOUT raw path
   */
  private Daemon.Path createProtoPath(String... str) {
    Daemon.Path.Builder path = Daemon.Path.newBuilder();
    int i = 0;
    while (i < str.length) {
      int id1 = -1;
      if (i > 0) {
        id1 = Integer.parseInt(str[i++]);
      }
      long isdAs = ScionUtil.parseIA(str[i++]);

      if (id1 >= 0) {
        path.addInterfaces(Daemon.PathInterface.newBuilder().setIsdAs(isdAs).setId(id1).build());
      }

      if (i < str.length) {
        long id2 = Integer.parseInt(str[i++]);
        path.addInterfaces(Daemon.PathInterface.newBuilder().setIsdAs(id2).build());
      }
    }
    return path.build();
  }

  @Test
  void fromJson_invalidFile_throwsException() {
    Class<IllegalArgumentException> ec = IllegalArgumentException.class;
    Exception e;
    // missing default policy in "group"
    e = assertThrows(ec, () -> testJsonGroup("ppl/pplGroup_missingDefault.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: "), e.getMessage());

    // missing policy in "policies"
    e = assertThrows(ec, () -> testJsonGroup("ppl/pplGroup_missingPolicy.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: Policy not found:"), e.getMessage());

    // missing policies in "group"
    e = assertThrows(ec, () -> testJsonGroup("ppl/pplGroup_missingPolicies.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: No entries in group"));

    // missing default policy in "policies"
    e = assertThrows(ec, () -> testJsonGroup("ppl/pplGroup_missingDefault.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: No default in group"));

    // bad default destination (not a catch all)
    e = assertThrows(ec, () -> testJsonGroup("ppl/pplGroup_badDefault.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: No default in group"));
  }

  private void testJsonGroup(String file) {
    PplPolicyGroup.fromJson(getPath(file));
  }

  @Test
  void fromJson_invalidJsonFormat_throwsException() {
    String invalidJson = "{ invalid json }";
    Exception e =
        assertThrows(IllegalArgumentException.class, () -> PplPolicyGroup.fromJson(invalidJson));
    assertTrue(e.getMessage().startsWith("Error parsing JSON:"), e.getMessage());
  }

  private java.nio.file.Path getPath(String resource) {
    try {
      return Paths.get(getClass().getClassLoader().getResource(resource).toURI());
    } catch (URISyntaxException e) {
      throw new ScionRuntimeException(e);
    }
  }
}
