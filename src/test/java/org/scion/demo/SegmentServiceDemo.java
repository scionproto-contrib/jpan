// Copyright 2023 ETH Zurich
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

package org.scion.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.*;
import java.net.*;
import org.scion.*;
import org.scion.testutil.MockDNS;

public class SegmentServiceDemo {

  private static final boolean PRINT = ScmpServerDemo.PRINT;
  public static int PORT = ScmpServerDemo.PORT;

  /**
   * True: connect to ScionPingPongChannelServer via Java mock topology False: connect to any
   * service via ScionProto "tiny" topology
   */
  public static boolean USE_MOCK_TOPOLOGY = false;

  public static void main(String[] args) throws IOException, InterruptedException {
    // Demo setup
    if (USE_MOCK_TOPOLOGY) {
      DemoTopology.configureMock();
      MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
      doClientStuff();
      DemoTopology.shutDown();
    } else {
      DemoTopology.configureTiny110_112();
      MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
      doClientStuff();
      DemoTopology.shutDown();
    }
  }

  private static void doClientStuff() throws IOException {

    String addr = Scion.defaultService().bootstrapViaDNS("inf.ethz.ch");
    assertNotNull(addr);
    System.out.println(addr);
    ScionService ss = Scion.newServiceWithDaemon(addr);
    //    System.out.println(
    //        "ISD/AS=" + ss.getLocalIsdAs() + "  " + ScionUtil.toStringIA(ss.getLocalIsdAs()));
    // TODO avoid argument!
    // System.out.println(Scion.defaultService().bootstrapViaDNS("inf.ethz.ch").ddr);

    // TODO
    //   - default to (inf).ethz.ch
    //   - default to http (not https)?

    ss.getSegments(0, 1);
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
