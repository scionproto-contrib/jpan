// Copyright 2026 ETH Zurich
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

package org.scion.jpan;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/** SCMP traceroute demo for JPAN using Endhost API bootstrap and SNAP underlay encapsulation. */
public class SnapPacketDemo {

  public static boolean PRINT = true;

  private SnapPacketDemo() {}

  public static void main(String[] args) throws Exception {
    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");

    Cli cli = Cli.parse(args);

    ScionService service = Scion.defaultService();
    long destinationIa = ScionUtil.parseIA(cli.destinationIa);

    Path path = service.getPaths(destinationIa, cli.destinationIp, Constants.SCMP_PORT).get(0);
    String localAddress;
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(path.getRemoteSocketAddress());
      // We determine the address separately because SCMP will always have 0.0.0.0 as local address
      localAddress = channel.getLocalAddress().getAddress().getHostAddress();
    }

    try (ScionDatagramChannel sender = ScionDatagramChannel.newBuilder().service(service).open()) {
      println("Resolved local address: ");
      println("  " + localAddress);
      printPath(path);

      System.out.println(
          "PACKET "
              + cli.destinationIa
              + ","
              + cli.destinationIp.getHostAddress()
              + ": local_port="
              + cli.localPort);

      String msg = "Hello there, SNAP!22!!3";
      ByteBuffer sendBuf = ByteBuffer.wrap(msg.getBytes());
      int n = sender.send(sendBuf, path);

      System.out.println("--- n = " + n + " ---");
      System.out.println("Sent from : " + sender.getLocalAddress());
    } finally {
      Scion.closeDefault();
    }
  }

  private static void printPath(Path path) {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder();
    sb.append("Using path:").append(nl);
    sb.append("  Hops: ").append(ScionUtil.toStringPath(path.getMetadata()));
    sb.append(" MTU: ").append(path.getMetadata().getMtu());
    if (path.getFirstHopAddress() != null) {
      sb.append(" NextHop: ").append(path.getFirstHopAddress()).append(nl);
    }
    println(sb.toString());
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }

  private static final class Cli {
    final String destinationIa;
    final InetAddress destinationIp;
    final int localPort;
    final int timeoutMs;

    private Cli(String destinationIa, InetAddress destinationIp, int localPort, int timeoutMs) {
      this.destinationIa = destinationIa;
      this.destinationIp = destinationIp;
      this.localPort = localPort;
      this.timeoutMs = timeoutMs;
    }

    static Cli parse(String[] args) throws IOException {
      args = SnapDemoBootstrap.readArgsOrDefaultFile(args, "snap-tr-demo.txt");

      String destination = null;
      String endhostApi = null;
      String discoveryEndpoint = null;
      String snapControl = null;
      int localPort = 0;
      String authKeyFile = null;
      String snapTokenFile = null;
      int timeoutMs = 3000;

      for (int i = 0; i < args.length; i++) {
        if (args[i].trim().isEmpty() || args[i].trim().startsWith("//")) {
          continue;
        }
        switch (args[i]) {
          case "--endhost-api":
            endhostApi = args[++i];
            System.setProperty(Constants.PROPERTY_BOOTSTRAP_PATH_SERVICE, endhostApi);
            break;
          case "--discovery":
            discoveryEndpoint = args[++i];
            System.setProperty(Constants.PROPERTY_SNAP_PATH_SERVICE_DISCOVERY, discoveryEndpoint);
            break;
          case "--snap-control":
            snapControl = args[++i];
            System.setProperty(Constants.PROPERTY_SNAP_CONTROL_PLANE, snapControl);
            break;
          case "--port":
            localPort = Integer.parseInt(args[++i]);
            break;
          case "--auth-key":
            authKeyFile = args[++i];
            SnapDemoBootstrap.configureAuthKey(authKeyFile);
            break;
          case "--snap-token":
            snapTokenFile = args[++i];
            SnapDemoBootstrap.configureSnapToken(snapTokenFile);
            break;
          case "--timeout-ms":
            timeoutMs = Integer.parseInt(args[++i]);
            break;
          case "--log":
            SnapDemoBootstrap.configureLogging(args[++i]);
            break;
          default:
            if (args[i].startsWith("--")) {
              throw new IllegalArgumentException("Unknown arg: " + args[i]);
            }
            if (destination != null) {
              throw new IllegalArgumentException("Only one destination may be specified");
            }
            destination = args[i];
        }
      }

      if (destination == null || (snapTokenFile == null && authKeyFile == null)) {
        throw new IllegalArgumentException(usage());
      }

      SnapDemoBootstrap.Destination dst = SnapDemoBootstrap.parseDestination(destination);

      if (endhostApi == null && discoveryEndpoint == null) {
        throw new IllegalArgumentException(usage());
      }

      System.out.println(
          "Using SNAP underlay via Endhost API "
              + SnapDemoBootstrap.endhostApiDescription(endhostApi, discoveryEndpoint));
      return new Cli(dst.isdAs, dst.ip, localPort, timeoutMs);
    }

    private static String usage() {
      return "Usage: SnapPacketDemo DEST_IA,[IP] (--endhost-api URL | --discovery URL) "
          + "--port PORT --snap-token FILE [--snap-control URL] [--timeout-ms MS] "
          + "[--log LEVEL]";
    }
  }
}
