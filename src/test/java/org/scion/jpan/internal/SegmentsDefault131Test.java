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

package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.testutil.DNSUtil;
import org.scion.jpan.testutil.MockBootstrapServer;
import org.scion.jpan.testutil.MockControlServer;

class SegmentsDefault131Test extends AbstractSegmentsTest {

  private static final long AS_131 = ScionUtil.parseIA("1-ff00:0:131");
  private static final long AS_133 = ScionUtil.parseIA("1-ff00:0:133");
  private static MockBootstrapServer topoServer;

  SegmentsDefault131Test() {
    super(CFG_DEFAULT);
  }

  @BeforeAll
  static void beforeAll() {
    topoServer = MockBootstrapServer.start(CFG_DEFAULT, "ASff00_0_131");
    InetSocketAddress topoAddr = topoServer.getAddress();
    DNSUtil.installNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
    controlServer = MockControlServer.start(topoServer.getControlServerPort());
  }

  @AfterEach
  void afterEach() {
    controlServer.clearSegments();
    topoServer.getAndResetCallCount();
    controlServer.getAndResetCallCount();
  }

  @AfterAll
  static void afterAll() {
    controlServer.close();
    topoServer.close();
    DNSUtil.clear();
    // Defensive clean up
    ScionService.closeDefault();
    System.clearProperty(Constants.PROPERTY_RESOLVER_MINIMIZE_REQUESTS);
  }

  @Test
  void onPathDown() throws IOException {
    addResponsesScionprotoDefault();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_131, AS_133);

      // Verify that we only get unique paths
      for (int i = 0; i < paths.size(); i++) {
        // byte[] raw = paths.get(i).getRaw().toByteArray();
        // System.out.println(ToStringUtil.pathLong(raw));
        // System.out.println(ToStringUtil.path(raw));
        for (int j = i + 1; j < paths.size(); j++) {
          byte[] b1 = paths.get(i).getRaw().toByteArray();
          byte[] b2 = paths.get(j).getRaw().toByteArray();

          assertFalse(Arrays.equals(b1, b2), "Identical: " + i + "/" + j + " in " + paths.size());
        }
      }

      assertEquals(1, paths.size());
    }
  }
}
