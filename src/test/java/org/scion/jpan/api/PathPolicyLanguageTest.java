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

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.internal.IPHelper;
import org.scion.jpan.ppl.PplPathFilter;
import org.scion.jpan.ppl.PplPolicy;
import org.scion.jpan.proto.daemon.Daemon;

class PathPolicyLanguageTest {

  @Test
  void filter_smokeTest() {
    PplPolicy policy = PplPolicy.fromJson(getPath("ppl/ppl.json"));
    InetSocketAddress addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    List<Path> paths = toList(createPath(addr, "1-ff00:0:112", "1", "2", "1-ff00:0:111"));

    List<Path> filteredPaths = policy.filter(paths);
    assertEquals(1, filteredPaths.size());
  }

  @Test
  void filter_export() {
    String input = readFile(getPath("ppl/ppl.json"));
    PplPolicy policy = PplPolicy.fromJson(getPath("ppl/ppl.json"));

    String output = policy.toJson(true);
    assertEquals(input, output);
    // We only compare the length, the ordering of filters may be different.
    // + 1 for final line break.
    assertEquals(input.length(), output.length() + 1);
    PplPolicy policy2 = PplPolicy.fromJson(output);
    String output2 = policy2.toJson(true);
    assertEquals(output, output2);
  }

  public static String readFile(java.nio.file.Path file) {
    StringBuilder contentBuilder = new StringBuilder();
    try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
      stream.forEach(s -> contentBuilder.append(s).append("\n"));
    } catch (IOException e) {
      throw new ScionRuntimeException("Error reading topology file: " + file.toAbsolutePath(), e);
    }
    return contentBuilder.toString();
  }

  @Test
  void filter_defaultOnly() {
    PplPolicy policy = PplPolicy.fromJson(getPath("ppl/ppl_trivial.json"));
    InetSocketAddress addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    List<Path> paths = toList(createPath(addr, "1-ff00:0:110", "1", "2", "1-ff00:0:111"));

    List<Path> filteredPaths = policy.filter(paths);
    assertEquals(1, filteredPaths.size());
  }

  @Test
  void filter_complex() {
    PplPolicy policy = PplPolicy.fromJson(getPath("ppl/ppl.json"));
    final List<Path> paths = new ArrayList<>();
    InetSocketAddress addr;

    // filter_110a - address match
    // This path matches the "sequence" (filter_110a) but not the "acl" in filter_110b
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
    assertEquals(1, policy.filter(paths).size());

    // filter_110a: address does not match -> filter_110b fails
    paths.clear();
    paths.add(createPath("10.0.0.3:12235", path133x110));
    assertTrue(policy.filter(paths).isEmpty());

    // filter_110b - address match - no ISD match -> accept
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "1-ff00:0:112", "1", "2", "1-ff00:0:110"));
    assertEquals(1, policy.filter(paths).size());

    // filter_110b - address match - ISD match -> deny
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "1-ff00:0:130", "0", "2", "1-ff00:0:110"));
    assertTrue(policy.filter(paths).isEmpty());

    // default match only (ISD 1-..210) -> specific 112 -> accept
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "1-ff00:0:112", "1", "2", "2-ff00:0:210"));
    assertEquals(1, policy.filter(paths).size());

    // default match only (ISD 1-..210) -> specific 1 -> deny
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "1-ff00:0:113", "1", "2", "2-ff00:0:210"));
    assertTrue(policy.filter(paths).isEmpty());

    // default match only  (ISD 1-..210) -> default ISD 2 -> accept
    paths.clear();
    addr = IPHelper.toInetSocketAddress("192.186.0.5:12234");
    paths.add(createPath(addr, "2-ff00:0:113", "1", "2", "2-ff00:0:210"));
    assertEquals(1, policy.filter(paths).size());
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
    PplPathFilter deny = PplPathFilter.builder().addAclEntry("-").build();
    PplPathFilter allow = PplPathFilter.builder().addAclEntry("+").build();
    PplPolicy policy = PplPolicy.builder().add(destDeny, deny).add("0", allow).build();
    String[] path133x110 = {"1-ff00:0:133", "0", "0", "1-ff00:0:110"};

    // address+port do not match "deny"
    List<Path> pathsAllow = toList(createPath(addrDeny, path133x110));
    assertEquals(1, policy.filter(pathsAllow).size());

    // address+port do not match "deny"
    List<Path> pathsDeny = toList(createPath(addrAllow, path133x110));
    assertTrue(policy.filter(pathsDeny).isEmpty());
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

    long now = Instant.now().getEpochSecond();
    path.setExpiration(Timestamp.newBuilder().setSeconds(now + 3600).build())
        .addBandwidth(100_000_000)
        .setMtu(1280)
        .addLatency(Duration.newBuilder().setNanos(10_000_000));

    return path.build();
  }

  @Test
  void fromJson_invalidFile_throwsException() {
    Class<IllegalArgumentException> ec = IllegalArgumentException.class;
    Exception e;
    // missing default policy in "destinations"
    e = assertThrows(ec, () -> testJsonFile("ppl/ppl_missingDefault.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: "), e.getMessage());

    // missing policy in "filters"
    e = assertThrows(ec, () -> testJsonFile("ppl/ppl_missingPolicy.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: Policy not found:"), e.getMessage());

    // missing policies in "destinations"
    e = assertThrows(ec, () -> testJsonFile("ppl/ppl_missingPolicies.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: No entries in group"));

    // missing default policy in "filters"
    e = assertThrows(ec, () -> testJsonFile("ppl/ppl_missingDefault.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: No default in group"));

    // bad default destination (not a catch-all)
    e = assertThrows(ec, () -> testJsonFile("ppl/ppl_badDefault.json"));
    assertTrue(e.getMessage().startsWith("Error parsing JSON: No default in group"));
  }

  private void testJsonFile(String file) {
    PplPolicy.fromJson(getPath(file));
  }

  @Test
  void fromJson_invalidJsonFormat_throwsException() {
    String invalidJson = "{ invalid json }";
    Exception e =
        assertThrows(IllegalArgumentException.class, () -> PplPolicy.fromJson(invalidJson));
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
