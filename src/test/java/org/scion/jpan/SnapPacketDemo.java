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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

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
      // Explicit bind, matching SnapTracerouteDemo/SnapEchoDemo's setLocalPort(): without this,
      // ScionDatagramChannel has no local-port option of its own, so binding happens lazily via
      // ensureBound()'s generic dispatcher-port-range fallback, which -- for this SNAP-mode AS's
      // full 1-65535 range -- deterministically lands on the same low port (1024) every run and
      // repeatedly trips the SNAP dataplane's "no immediate port reuse" rejection. Binding
      // explicitly to cli.localPort (0 by default) instead asks the OS for a fresh ephemeral port
      // each run, same as the other demos.
      // sender.bind(new InetSocketAddress(cli.localPort));
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
    // sb.append("Actual local address:").append(nl);
    // sb.append("  ").append(channel.getLocalAddress().getAddress().getHostAddress()).append(nl);
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
      if (args.length == 0) {
        List<String> cl = Files.readAllLines(Paths.get("snap-tr-demo.txt"));
        cl =
            cl.stream()
                .map(String::trim)
                .filter(s -> !s.startsWith("//"))
                .collect(Collectors.toList());
        args = cl.toArray(new String[0]);
      }

      String destination = null;
      String endhostApi = null;
      String discoveryEndpoint = null;
      String snapControl = null;
      int localPort = 0;
      String authKeyFile = null;
      String snapTokenFile = null;
      int timeoutMs = 3000;
      String logLevel = "info";

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
            String authKey = SnapDemoBootstrap.readAuthKeyFile(authKeyFile);
            System.setProperty(Constants.PROPERTY_SNAP_AUTH_SERVICE, "auth.scion.anapaya.net");
            System.setProperty(Constants.PROPERTY_SNAP_AUTH_KEY, authKey);
            break;
          case "--snap-token":
            snapTokenFile = args[++i];
            String snapToken = SnapDemoBootstrap.readTokenFile(snapTokenFile);
            System.setProperty(Constants.PROPERTY_SNAP_AUTH_TOKEN, snapToken);
            break;
          case "--timeout-ms":
            timeoutMs = Integer.parseInt(args[++i]);
            break;
          case "--log":
            logLevel = args[++i];
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", logLevel);
            System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
            System.setProperty(
                "org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
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

      int separator = destination.indexOf(",[");
      if (separator <= 0 || !destination.endsWith("]")) {
        throw new IllegalArgumentException(
            "Destination must be in the form ISD-AS,[IP], for example 64-2:0:9c,[::1]");
      }

      String destinationIa = destination.substring(0, separator);
      String destinationIpLiteral = destination.substring(separator + 2, destination.length() - 1);
      InetAddress destinationIp = InetAddress.getByName(destinationIpLiteral);

      if (endhostApi == null && discoveryEndpoint == null) {
        throw new IllegalArgumentException(usage());
      }

      System.out.println(
          "Using SNAP underlay via Endhost API "
              + SnapDemoBootstrap.endhostApiDescription(endhostApi, discoveryEndpoint));
      return new Cli(destinationIa, destinationIp, localPort, timeoutMs);
    }

    private static String usage() {
      return "Usage: SnapPacketDemo DEST_IA,[IP] (--endhost-api URL | --discovery URL) "
          + "--port PORT --snap-token FILE [--snap-control URL] [--timeout-ms MS] "
          + "[--log LEVEL]";
    }
  }
}
