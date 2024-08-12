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

public class SegmentsDefault112Test extends AbstractSegmentsMinimalTest {

  private static MockBootstrapServer topoServer;

  SegmentsDefault112Test() {
    super(CFG_DEFAULT);
  }

  @BeforeAll
  public static void beforeAll() {
    topoServer = MockBootstrapServer.start(CFG_DEFAULT, "ASff00_0_112/topology.json");
    InetSocketAddress topoAddr = topoServer.getAddress();
    DNSUtil.installNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
    controlServer = MockControlServer.start(topoServer.getControlServerPort());
  }

  @AfterEach
  public void afterEach() {
    controlServer.clearSegments();
    topoServer.getAndResetCallCount();
    controlServer.getAndResetCallCount();
  }

  @AfterAll
  public static void afterAll() {
    controlServer.close();
    topoServer.close();
    DNSUtil.clear();
    // Defensive clean up
    ScionService.closeDefault();
    System.clearProperty(Constants.PROPERTY_RESOLVER_MINIMIZE_REQUESTS);
  }

  @Test
  void removeDuplicatePaths() throws IOException {
    addResponsesScionprotoDefault();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_112, AS_111);

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

      // scionproto reports only 6 paths. The difference is that JPAN considers path different
      // even if their hops are identical, as long their expiry date or SegmentId differs.
      // For example, there are two paths that look like this:
      // [494>103]
      // However, they are not identical for JPAN:
      // - segID=9858, timestamp=1723449803
      // - segID=9751, timestamp=1723449803

      // assertEquals(6, paths.size());
      assertEquals(7, paths.size());
    }
  }

  @Test
  void orderingByHopCount() throws IOException {
    addResponsesScionprotoDefault();
    try (Scion.CloseableService ss = Scion.newServiceWithDNS(AS_HOST)) {
      List<Daemon.Path> paths = PackageVisibilityHelper.getPathListCS(ss, AS_112, AS_111);

      // Verify that paths are ordered by lengths
      int maxHopCount = 0;
      for (int i = 0; i < paths.size(); i++) {
        int hopCount = paths.get(i).getInterfacesCount();
        if (hopCount < maxHopCount) {
          fail();
        }
        maxHopCount = hopCount;
      }
    }
  }
}
