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
import java.util.NoSuchElementException;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.internal.IPHelper;
import org.scion.jpan.ppl.PplPolicy;
import org.scion.jpan.ppl.PplPolicyGroup;
import org.scion.jpan.proto.daemon.Daemon;

class PathPolicyLanguageGroupTest {

  @Test
  void filter_smokeTest() {
    PplPolicyGroup group = PplPolicyGroup.fromJson(getPath("ppl/pplGroup.json"));
    InetSocketAddress addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    List<Path> paths = toList(createPath(addr, "1-ff00:0:112", "1", "2", "1-ff00:0:111"));

    List<Path> filteredPaths = group.filter(paths);
    assertEquals(1, filteredPaths.size());
  }

  @Test
  void filter_defaultOnly() {
    PplPolicyGroup group = PplPolicyGroup.fromJson(getPath("ppl/pplGroup_trivial.json"));
    InetSocketAddress addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    List<Path> paths = toList(createPath(addr, "1-ff00:0:110", "1", "2", "1-ff00:0:111"));

    List<Path> filteredPaths = group.filter(paths);
    assertEquals(1, filteredPaths.size());
  }

  @Test
  void filter_complex() {
    PplPolicyGroup group = PplPolicyGroup.fromJson(getPath("ppl/pplGroup.json"));
    final List<Path> paths = new ArrayList<>();
    InetSocketAddress addr;

    // policy_110a - address match
    // This path matches the "sequence" (policy_110a) but not the "acl" in policy_110b
    String[] path133x110 = {
      "1-ff00:0:133",
      "0",
      "2",
      "1-ff00:0:120",
      "1",
      "0",
      "1-ff00:0:130",
      "0",
      "0",
      "1-ff00:0:131",
      "0",
      "0",
      "1-ff00:0:110"
    };
    paths.add(createPath("10.0.0.2:12234", path133x110));
    assertEquals(1, group.filter(paths).size());

    // policy_110a: address does not match -> policy_110b fails
    paths.clear();
    paths.add(createPath("10.0.0.3:12235", path133x110));
    assertThrows(NoSuchElementException.class, () -> group.filter(paths));

    // policy_110b - address match - no ISD match -> accept
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "1-ff00:0:112", "1", "2", "1-ff00:0:110"));
    assertEquals(1, group.filter(paths).size());

    // policy_110b - address match - ISD match -> deny
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "1-ff00:0:130", "0", "2", "1-ff00:0:110"));
    assertThrows(NoSuchElementException.class, () -> group.filter(paths));

    // default match only (ISD 1-..210) -> specific 112 -> accept
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "1-ff00:0:112", "1", "2", "2-ff00:0:210"));
    assertEquals(1, group.filter(paths).size());

    // default match only (ISD 1-..210) -> specific 1 -> deny
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "1-ff00:0:113", "1", "2", "2-ff00:0:210"));
    assertThrows(NoSuchElementException.class, () -> group.filter(paths));

    // default match only  (ISD 1-..210) -> default ISD 2 -> accept
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "2-ff00:0:113", "1", "2", "2-ff00:0:210"));
    assertEquals(1, group.filter(paths).size());
  }

  private List<Path> toList(Path path) {
    List<Path> paths = new ArrayList<>();
    paths.add(path);
    return paths;
  }

  private RequestPath createPath(String addr, String... str) {
    return createPath(IPHelper.toInetSocketAddress(addr), str);
  }

  private RequestPath createPath(InetSocketAddress addr, String... str) {
    Daemon.Path protoPath = createProtoPath(str);
    long dstIsdAs = ScionUtil.parseIA(str[str.length - 1]);
    return PackageVisibilityHelper.createRequestPath(protoPath, dstIsdAs, addr);
  }

  @Test
  void filter_IPv4() {
    testDenyAllow("1-ff00:0:110,10.0.0.2", "10.0.0.3:12234", "10.0.0.2:22222");
  }

  @Test
  void filter_IPv4_port() {
    testDenyAllow("1-ff00:0:110,10.0.0.2:22222", "10.0.0.2:12234", "10.0.0.2:22222");
  }

  @Test
  void filter_IPv6() {
    testDenyAllow("1-ff00:0:110,[1:2::3]", "[1:2::4]:12234", "[1:2::3]:22222");
  }

  @Test
  void filter_IPv6_port() {
    testDenyAllow("1-ff00:0:110,[1:2::3]:22222", "[1:2::3]:12234", "[1:2::3]:22222");
  }

  private void testDenyAllow(String destDeny, String addrDeny, String addrAllow) {
    PplPolicy deny = PplPolicy.builder().addAclEntry("-").build();
    PplPolicy allow = PplPolicy.builder().addAclEntry("+").build();
    PplPolicyGroup group = PplPolicyGroup.builder().add(destDeny, deny).add("0", allow).build();
    String[] path133x110 = {"1-ff00:0:133", "0", "0", "1-ff00:0:110"};

    // address+port do not match "deny"
    List<Path> pathsAllow = toList(createPath(addrDeny, path133x110));
    assertEquals(1, group.filter(pathsAllow).size());

    // address+port do not match "deny"
    List<Path> pathsDeny = toList(createPath(addrAllow, path133x110));
    assertThrows(NoSuchElementException.class, () -> group.filter(pathsDeny));
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
        int id2 = Integer.parseInt(str[i++]);
        path.addInterfaces(Daemon.PathInterface.newBuilder().setIsdAs(isdAs).setId(id2).build());
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

    // bad default destination (not a catch-all)
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
      return Paths.get(
          Objects.requireNonNull(getClass().getClassLoader().getResource(resource)).toURI());
    } catch (URISyntaxException e) {
      throw new ScionRuntimeException(e);
    }
  }
}
