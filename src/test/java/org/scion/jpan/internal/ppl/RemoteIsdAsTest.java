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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.ScionUtil;

public class RemoteIsdAsTest {

  @Test
  void TestRemoteISDASEval() {
    PathProvider pp = new PathProvider();
    List<Path> paths1_110 = pp.getPaths("2-ff00:0:210", "1-ff00:0:110");
    List<Path> paths2_212 = pp.getPaths("2-ff00:0:210", "2-ff00:0:212");
    List<Path> paths2_220 = pp.getPaths("2-ff00:0:210", "2-ff00:0:220");
    List<Path> inPaths = new ArrayList<>();
    inPaths.addAll(paths1_110);
    inPaths.addAll(paths2_212);
    inPaths.addAll(paths2_220);

    // "nil"
    RemoteIsdAs ri = RemoteIsdAs.create(null);
    List<Path> outPaths = ri.eval(inPaths);
    assertEquals(0, outPaths.size());

    // "reject all"
    ri = RemoteIsdAs.create();
    outPaths = ri.eval(inPaths);
    assertEquals(0, outPaths.size());

    // "accept all"
    ri = RemoteIsdAs.create(RemoteIsdAs.IsdAsRule.create(ScionUtil.parseIA("0-0")));
    outPaths = ri.eval(inPaths);
    assertEquals(6, outPaths.size());

    // "as wildcard"
    ri = RemoteIsdAs.create(RemoteIsdAs.IsdAsRule.create(ScionUtil.parseIA("2-0")));
    outPaths = ri.eval(inPaths);
    assertEquals(5, outPaths.size());

    // "isd wildcard"
    ri = RemoteIsdAs.create(RemoteIsdAs.IsdAsRule.create(ScionUtil.parseIA("0-ff00:0:212")));
    outPaths = ri.eval(inPaths);
    assertEquals(4, outPaths.size());

    // "two rules"
    ri =
        RemoteIsdAs.create(
            RemoteIsdAs.IsdAsRule.create(ScionUtil.parseIA("1-0")),
            RemoteIsdAs.IsdAsRule.create(ScionUtil.parseIA("2-ff00:0:220")));
    outPaths = ri.eval(inPaths);
    assertEquals(2, outPaths.size());

    // "two rules negated"
    ri =
        RemoteIsdAs.create(
            RemoteIsdAs.IsdAsRule.create(ScionUtil.parseIA("1-0"), true),
            RemoteIsdAs.IsdAsRule.create(ScionUtil.parseIA("0-0")));
    outPaths = ri.eval(inPaths);
    assertEquals(5, outPaths.size());
  }
}
