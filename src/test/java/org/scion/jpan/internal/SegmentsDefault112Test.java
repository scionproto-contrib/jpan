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
import org.scion.jpan.demo.util.ToStringUtil;
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
        byte[] raw = paths.get(i).getRaw().toByteArray();
        System.out.println(ToStringUtil.pathLong(raw));
        System.out.println(ToStringUtil.path(raw));

        // Problem:
        // The following are identical EXCEPT that they are "reflecting" on interface 104 vs 105 in
        // 111.
        // TODO fix:
        //    - the consingress port should probably be 0!
        //    - the segmentID can probably be ignored....?
        //    - check for expiry?
        // They also have different SegmentIDs
        //    Path header:   currINF=0  currHP=0  reserved=0  seg0Len=2  seg1Len=0  seg2Len=0
        //    info0=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false,
        // P=false, C=false, reserved=0, segID=9858, timestamp=1723044995}
        //    hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, I=false,
        // E=false, expiryTime=63, consIngress=494, consEgress=0, mac=1108152157446}
        //    hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, I=false,
        // E=false, expiryTime=63, consIngress=105, consEgress=103, mac=1108152157446}
        //    Hops: [c0:false c1:false c2:false 494>103 ]
        //
        //    Path header:   currINF=0  currHP=0  reserved=0  seg0Len=2  seg1Len=0  seg2Len=0
        //    info0=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false,
        // P=false, C=false, reserved=0, segID=9751, timestamp=1723044995}
        //    hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, I=false,
        // E=false, expiryTime=63, consIngress=494, consEgress=0, mac=1108152157446}
        //    hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, I=false,
        // E=false, expiryTime=63, consIngress=104, consEgress=103, mac=1108152157446}
        //    Hops: [c0:false c1:false c2:false 494>103 ]

        //        System.out.println("RAW: " +
        // ScionUtil.toStringPath(paths.get(i).getRaw().toByteArray()));
        //        System.out.println("RAW: " +
        // ToStringUtil.toStringPath(paths.get(i).getRaw().toByteArray()));
        for (int j = i + 1; j < paths.size(); j++) {
          byte[] b1 = paths.get(i).getRaw().toByteArray();
          byte[] b2 = paths.get(j).getRaw().toByteArray();

          assertFalse(
              Arrays.equals(b1, b2), "Identical arrays: " + i + "/" + j + " in " + paths.size());
        }
      }

      // TODO this may be 7, should we just use 7?
      assertEquals(5, paths.size());
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
