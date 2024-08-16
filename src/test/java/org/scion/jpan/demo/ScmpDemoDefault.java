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

package org.scion.jpan.demo;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.List;
import org.scion.jpan.*;
import org.scion.jpan.internal.PathRawParser;
import org.scion.jpan.testutil.Scenario;

/**
 * This demo tests various scenarios on the scionproto "default" topology.
 *
 * <p>The current implementation only validates the number of hops (i.e. that a pth is indeed
 * "short") and that the path works (i.e. is accepted by the scionproto mock network).
 *
 * <p>Each test is run twice: once with getting the path from the control server and once with
 * getting the path from the daemon.
 */
public class ScmpDemoDefault {

  public static boolean PRINT = true;
  private static int REPEAT = 2;
  // Use a port from the dispatcher compatibility range
  private static final int LOCAL_PORT = 32766;
  private static final String PREFIX = "topologies/scionproto-default/";

  public static void init(boolean print, int repeat) {
    PRINT = print;
    REPEAT = repeat;
  }

  public static void main(String[] args) throws IOException {
    // Peering direct
    //    run("1-ff00:0:133", "1-ff00:0:122", "ASff00_0_133/topology.json", 1);
    //    run("1-ff00:0:133", "1-ff00:0:122", null, 1);

    // Peering via parent
    //    run("1-ff00:0:132", "1-ff00:0:122", "ASff00_0_132/topology.json", 5);
    //    run("1-ff00:0:132", "1-ff00:0:122", null, 5);

    // On path down, IPv4
    run("1-ff00:0:131", "1-ff00:0:132", "ASff00_0_131/topology.json", 2);
    run("1-ff00:0:131", "1-ff00:0:132", null, 2);

    // On path down, IPv6
    run("1-ff00:0:132", "1-ff00:0:133", "ASff00_0_132/topology.json", 2);
    run("1-ff00:0:132", "1-ff00:0:133", null, 2);

    // On path up, IPv4
    run("1-ff00:0:133", "1-ff00:0:131", "ASff00_0_133/topology.json", 3);
    run("1-ff00:0:133", "1-ff00:0:131", null, 3);

    // On path up, IPv6
    run("1-ff00:0:132", "1-ff00:0:131", "ASff00_0_132/topology.json", 2);
    run("1-ff00:0:132", "1-ff00:0:131", null, 2);

    // Shortcut, IPv4
    run("2-ff00:0:212", "2-ff00:0:222", "ASff00_0_212/topology.json", 4);
    run("2-ff00:0:212", "2-ff00:0:222", null, 4);

    // Shortcut, IPv6
    run("2-ff00:0:222", "2-ff00:0:212", "ASff00_0_222/topology.json", 4);
    run("2-ff00:0:222", "2-ff00:0:212", null, 4);
  }

  public static void run(String src, String dst, String topoFile, int nHops) throws IOException {
    // Same as:
    // scion ping 2-ff00:0:211,127.0.0.10 --sciond 127.0.0.43:30255
    try {
      // Use scenario builder to get access to relevant IP addresses
      Scenario scenario = Scenario.readFrom(PREFIX);
      long srcIsdAs = ScionUtil.parseIA(src);
      long dstIsdAs = ScionUtil.parseIA(dst);

      if (topoFile != null) {
        // Alternative #1: Bootstrap from topo file
        System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, PREFIX + topoFile);
      } else {
        // Alternative #2: Bootstrap from SCION daemon
        System.setProperty(Constants.PROPERTY_DAEMON, scenario.getDaemon(srcIsdAs));
      }

      // Ping the dispatcher/shim. It listens on the same IP as the control service.
      InetAddress ip = scenario.getControlServer(dstIsdAs).getAddress();

      // Get paths
      List<Path> paths = Scion.defaultService().getPaths(dstIsdAs, ip, Constants.SCMP_PORT);
      Path path = PathPolicy.MIN_HOPS.filter(paths);
      runDemo(path);
      new HopValidator(nHops).validate(path);
    } finally {
      Scion.closeDefault();
    }
  }

  private static void runDemo(Path path) throws IOException {
    ByteBuffer data = ByteBuffer.allocate(0);
    printPath(path);
    try (ScmpChannel scmpChannel = Scmp.createChannel(LOCAL_PORT)) {
      for (int i = 0; i < REPEAT; i++) {
        Scmp.EchoMessage msg = scmpChannel.sendEchoRequest(path, i, data);
        String millis = String.format("%.3f", msg.getNanoSeconds() / (double) 1_000_000);
        String echoMsgStr = msg.getSizeReceived() + " bytes from ";
        InetAddress addr = msg.getPath().getRemoteAddress();
        echoMsgStr += ScionUtil.toStringIA(path.getRemoteIsdAs()) + "," + addr.getHostAddress();
        echoMsgStr += ": scmp_seq=" + msg.getSequenceNumber();
        if (msg.isTimedOut()) {
          echoMsgStr += " Timed out after";
        }
        echoMsgStr += " time=" + millis + "ms";
        println(echoMsgStr);
        try {
          if (i < REPEAT - 1) {
            Thread.sleep(1000);
          }
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private static void printPath(Path path) {
    String sb = "Hops: " + ScionUtil.toStringPath(path.getMetadata());
    sb += " MTU: " + path.getMetadata().getMtu();
    sb += " NextHop: " + path.getMetadata().getInterface().getAddress();
    println(sb);
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }

  private static class HopValidator {
    final int nHops;

    private HopValidator(int nHops) {
      this.nHops = nHops;
    }

    void validate(Path path) {
      PathRawParser ph = PathRawParser.create(path.getRawPath());
      int hopCount = ph.getSegLen(0) + ph.getSegLen(1) + ph.getSegLen(2);
      if (hopCount != nHops) {
        throw new IllegalStateException("Expected: " + nHops + " but got " + hopCount);
      }
    }
  }
}
