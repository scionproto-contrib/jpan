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

import java.io.*;
import java.util.List;
import org.scion.*;
import org.scion.demo.inspector.PathHeaderScion;
import org.scion.demo.util.ToStringUtil;
import org.scion.proto.daemon.Daemon;
import org.scion.testutil.MockDNS;

public class SegmentServiceDemo {

  private static final boolean PRINT = ScmpServerDemo.PRINT;

  /**
   * True: connect to ScionPingPongChannelServer via Java mock topology False: connect to any
   * service via ScionProto "tiny" topology
   */
  public static boolean USE_MOCK_TOPOLOGY = false;

  public static void main(String[] args) throws IOException, InterruptedException {
    // Control service IPs
    String csAddr110 = "127.0.0.11:31000";
    String csAddr111 = "127.0.0.18:31006";
    String csAddr112 = "[fd00:f00d:cafe::7f00:a]:31010";
    long ia110 = ScionUtil.parseIA("1-ff00:0:110");
    long ia111 = ScionUtil.parseIA("1-ff00:0:111");
    long ia112 = ScionUtil.parseIA("1-ff00:0:112");

    String csETH = "192.168.53.20:30252";
    long iaETH = ScionUtil.parseIA("64-2:0:9");
    // TODO isGEANT breaks, maybe because it is a core AS?
    long iaGEANT = ScionUtil.parseIA(ScionUtil.toStringIA(71, 20965));
    long iaOVGU = ScionUtil.parseIA("71-2:0:4a");
    long iaAnapayaHK = ScionUtil.parseIA("66-2:0:11");

    // Demo setup
    if (USE_MOCK_TOPOLOGY) {
      DemoTopology.configureMock();
      MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
      // TODO ???? doClientStuff(csAddr112, ia112, );
      DemoTopology.shutDown();
    } else {
      // DemoTopology.configureTiny110_112();
      // MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
      doClientStuff(csETH, iaETH, iaAnapayaHK);
      // doClientStuff(csAddr111, ia111, ia112);
      // DemoTopology.shutDown();
    }
  }

  private static void doClientStuff(String csAddress, long srcIsdAs, long dstIsdAs)
      throws IOException {
    ScionService ss = Scion.newServiceWithControlServiceIP(csAddress);
    //    System.out.println(
    //        "ISD/AS=" + ss.getLocalIsdAs() + "  " + ScionUtil.toStringIA(ss.getLocalIsdAs()));
    // TODO avoid argument!
    // System.out.println(Scion.defaultService().bootstrapViaDNS("inf.ethz.ch").ddr);

    // TODO
    //   - default to (inf).ethz.ch
    //   - default to http (not https)?

    long maskWild = -1L << 48;
    long srcCore = srcIsdAs & maskWild;
    long dstCore = dstIsdAs & maskWild;

    // println("111 -> core");
    //    ss.getSegments(srcIsdAs, srcCore);
    //    // println("core -> core");
    //    ss.getSegments(srcCore, dstCore);
    //    // println("core -> 112");
    //    //dstCore = ScionUtil.parseIA("66-2:0:10"); // TODO remove
    //    ss.getSegments(dstCore, dstIsdAs);

    List<Daemon.Path> list = ss.getPathListCS(srcIsdAs, dstIsdAs);
    for (Daemon.Path path : list) {
      System.out.println("Path CS: " + ToStringUtil.toString(path.getRaw().toByteArray()));
      PathHeaderScion phs = new PathHeaderScion();
      phs.read(path.getRaw().asReadOnlyByteBuffer());
      System.out.println(phs.toString());
    }

    //    ScionService ssD = Scion.newServiceWithDaemon("127.0.0.19:30255");
    //    for (Daemon.Path path : ssD.getPathListDaemon(srcIsdAs, dstIsdAs)) {
    //      System.out.println("Path DA: " + ToStringUtil.toString(path.getRaw().toByteArray()));
    //      PathHeaderScion phs = new PathHeaderScion();
    //      phs.read(path.getRaw().asReadOnlyByteBuffer());
    //      System.out.println(phs.toString());
    //    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
