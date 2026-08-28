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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/** SCMP traceroute demo for JPAN using Endhost API bootstrap and SNAP underlay encapsulation. */
public class SnapTracerouteDemo {

  public static boolean PRINT = true;

  private SnapTracerouteDemo() {}

  public static void main(String[] args) throws Exception {
    Cli cli = Cli.parse(args);

    configureSnap(cli);

    ScionService service = Scion.defaultService();
    long destinationIa = ScionUtil.parseIA(cli.destinationIa);

    Path path = service.getPaths(destinationIa, cli.destinationIp, Constants.SCMP_PORT).get(0);
    String localAddress;
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(path.getRemoteSocketAddress());
      // We determine the address separately because SCMP will always have 0.0.0.0 as local address
      localAddress = channel.getLocalAddress().getAddress().getHostAddress();
    }

    try (ScmpSender sender =
        Scmp.newSenderBuilder().setService(service).setLocalPort(cli.localPort).build()) {
      println("Listening on port " + sender.getLocalAddress().getPort() + " ...");
      println("Resolved local address: ");
      println("  " + localAddress);
      printPath(path);

      sender.setTimeOut(cli.timeoutMs);

      System.out.println(
          "TRACEROUTE "
              + cli.destinationIa
              + ","
              + cli.destinationIp.getHostAddress()
              + ": local_port="
              + cli.localPort);
      System.out.println(
          "Using SNAP underlay via Endhost API "
              + SnapDemoBootstrap.endhostApiDescription(cli.endhostApi, cli.discoveryEndpoint));

      int transmitted = 0;
      int received = 0;
      List<Scmp.TracerouteMessage> replies = sender.sendTracerouteRequest(path);
      for (Scmp.TracerouteMessage reply : replies) {
        transmitted++;
        if (reply.isTimedOut()) {
          System.out.println("Request timeout for scmp_seq=" + reply.getIdentifier());
        } else {
          received++;
          String millis = String.format("%.4f", reply.getNanoSeconds() / (double) 1_000_000);
          String out = "" + reply.getSequenceNumber();
          out += " " + ScionUtil.toStringIA(reply.getIsdAs());
          out += " " + reply.getPath().getRemoteAddress().getHostAddress();
          out += " IfID=" + reply.getIfID();
          out += " " + millis + "ms";
          System.out.println(out);
        }
      }

      int lossPercent = transmitted == 0 ? 0 : ((transmitted - received) * 100) / transmitted;
      System.out.println(
          "--- "
              + cli.destinationIa
              + ","
              + cli.destinationIp.getHostAddress()
              + " statistics ---");
      System.out.println(
          transmitted
              + " packets transmitted, "
              + received
              + " received, "
              + lossPercent
              + "% packet loss");
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
    if (path.getMetadata().getLocalInterface() != null) {
      sb.append(" NextHop: ")
          .append(path.getMetadata().getLocalInterface().getAddress())
          .append(nl);
    }
    println(sb.toString());
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }

  private static void configureSnap(Cli cli) {
    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");
    System.setProperty(
        Constants.PROPERTY_BOOTSTRAP_PATH_SERVICE,
        SnapDemoBootstrap.resolveBootstrapAddress(cli.endhostApi, cli.discoveryEndpoint));
    if (cli.snapControl != null) {
      System.setProperty(Constants.PROPERTY_SNAP_PATH_SERVICE, cli.snapControl);
    }
    System.setProperty(Constants.PROPERTY_PATH_SERVICE_AUTH_TOKEN, cli.snapToken);
    System.setProperty(Constants.PROPERTY_SNAP_AUTH_TOKEN, cli.snapToken);

    if (cli.logLevel != null) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", cli.logLevel);
      System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
      System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    }
  }

  private static final class Cli {
    final String destinationIa;
    final InetAddress destinationIp;
    final String endhostApi;
    final String discoveryEndpoint;
    final String snapControl;
    final int localPort;
    final String snapToken;
    final int timeoutMs;
    final String logLevel;

    private Cli(
        String destinationIa,
        InetAddress destinationIp,
        String endhostApi,
        String discoveryEndpoint,
        String snapControl,
        int localPort,
        String snapToken,
        int timeoutMs,
        String logLevel) {
      this.destinationIa = destinationIa;
      this.destinationIp = destinationIp;
      this.endhostApi = endhostApi;
      this.discoveryEndpoint = discoveryEndpoint;
      this.snapControl = snapControl;
      this.localPort = localPort;
      this.snapToken = snapToken;
      this.timeoutMs = timeoutMs;
      this.logLevel = logLevel;
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
      Integer localPort = null;
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
            break;
          case "--discovery":
            discoveryEndpoint = args[++i];
            break;
          case "--snap-control":
            snapControl = args[++i];
            break;
          case "--port":
            localPort = Integer.parseInt(args[++i]);
            break;
          case "--auth-key":
            authKeyFile = args[++i];
            break;
          case "--snap-token":
            snapTokenFile = args[++i];
            break;
          case "--timeout-ms":
            timeoutMs = Integer.parseInt(args[++i]);
            break;
          case "--log":
            logLevel = args[++i];
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

      if (destination == null
          || localPort == null
          || (snapTokenFile == null && authKeyFile == null)) {
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

      SnapDemoBootstrap.TokenResolution tokenResolution =
          SnapDemoBootstrap.resolveSnapToken(snapTokenFile, authKeyFile);
      String snapToken = tokenResolution.snapToken;
      if (snapTokenFile == null) {
        System.err.println("Snap toke: " + snapToken);
      }
      if (endhostApi == null
          && discoveryEndpoint == null
          && tokenResolution.endhostApiDiscoveryUrl != null) {
        discoveryEndpoint = tokenResolution.endhostApiDiscoveryUrl;
      }
      if (endhostApi == null && discoveryEndpoint == null) {
        throw new IllegalArgumentException(usage());
      }

      return new Cli(
          destinationIa,
          destinationIp,
          endhostApi,
          discoveryEndpoint,
          snapControl,
          localPort,
          snapToken,
          timeoutMs,
          logLevel);
    }

    private static String usage() {
      return "Usage: SnapTracerouteDemo DEST_IA,[IP] (--endhost-api URL | --discovery URL) "
          + "--port PORT --snap-token FILE [--snap-control URL] [--timeout-ms MS] "
          + "[--log LEVEL]";
    }
  }
}
