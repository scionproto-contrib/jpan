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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Path;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.testutil.ExamplePacket;

class LocalIsdAsTest {

  @Disabled
  @Test
  void TestLocalISDASEval() {
    PathProvider pp = new PathProvider();
    List<Path> paths212 = pp.getPaths("2-ff00:0:212", "2-ff00:0:220");
    List<Path> paths220 = pp.getPaths("2-ff00:0:220", "2-ff00:0:212");
    List<Path> inPaths = new ArrayList<>();
    inPaths.addAll(paths212);
    inPaths.addAll(paths220);

    Path localPath =
        PackageVisibilityHelper.createDummyPath(
            ScionUtil.parseIA("2-ff00:0:220"),
            new byte[] {127, 0, 0, 1},
            12345,
            new byte[] {},
            ExamplePacket.FIRST_HOP);

    // Path localPath = mock_snet.NewMockPath(ctrl);
    // localPath.EXPECT().Source().Return(addr.MustParseIA("2-ff00:0:220"));
    // localPath.EXPECT().Destination().Return(addr.MustParseIA("2-ff00:0:220"));

    // "first isdas"
    LocalIsdAs li = LocalIsdAs.create(ScionUtil.parseIA("2-ff00:0:212"));
    List<Path> outPaths = li.eval(inPaths);
    assertEquals(6, outPaths.size());

    // "second isdas":
    li = LocalIsdAs.create(ScionUtil.parseIA("2-ff00:0:220"));
    outPaths = li.eval(inPaths);
    assertEquals(6, outPaths.size());

    // "both isdases"
    li = LocalIsdAs.create(ScionUtil.parseIA("2-ff00:0:212"), ScionUtil.parseIA("2-ff00:0:220"));
    outPaths = li.eval(inPaths);
    assertEquals(12, outPaths.size());

    // "extra isdas"
    li =
        LocalIsdAs.create(
            ScionUtil.parseIA("2-ff00:0:212"),
            ScionUtil.parseIA("2-ff00:0:220"),
            ScionUtil.parseIA("1-ff00:0:220"));
    outPaths = li.eval(inPaths);
    assertEquals(12, outPaths.size());

    // "local paths are not counted"
    li = LocalIsdAs.create(ScionUtil.parseIA("2-ff00:0:212"), ScionUtil.parseIA("2-ff00:0:220"));
    List<Path> inLocalPaths = new ArrayList<>();
    inLocalPaths.addAll(inPaths);
    inLocalPaths.add(localPath);
    outPaths = li.eval(inLocalPaths);
    assertEquals(6, outPaths.size());
  }
}
