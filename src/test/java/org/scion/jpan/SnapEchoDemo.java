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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/** SCMP echo demo for JPAN using Endhost API bootstrap and SNAP underlay encapsulation. */
public class SnapEchoDemo {

  private SnapEchoDemo() {}

  public static void main(String[] args) throws Exception {
    Cli cli = Cli.parse(args);

    configureSnap(cli);

    ScionService service = Scion.defaultService();
    long destinationIa = ScionUtil.parseIA(cli.destinationIa);

    try (ScmpSender sender =
        Scmp.newSenderBuilder().setService(service).setLocalPort(cli.localPort).build()) {
      sender.setTimeOut(cli.timeoutMs);

      Path path = service.getPaths(destinationIa, cli.destinationIp, Constants.SCMP_PORT).get(0);
      byte[] payload = cli.payload.getBytes(StandardCharsets.UTF_8);

      System.out.println(
          "PING "
              + cli.destinationIa
              + ","
              + cli.destinationIp.getHostAddress()
              + ": pld="
              + payload.length
              + "B local_port="
              + cli.localPort);
      System.out.println(
          "Using SNAP underlay via Endhost API "
              + SnapDemoBootstrap.endhostApiDescription(cli.endhostApi, cli.discoveryEndpoint));

      int transmitted = 0;
      int received = 0;
      for (int sequence = 0; sequence < cli.count; sequence++) {
        transmitted++;
        Scmp.EchoMessage reply = sender.sendEchoRequest(path, ByteBuffer.wrap(payload));
        if (reply.isTimedOut()) {
          System.out.println("Request timeout for scmp_seq=" + sequence);
        } else {
          received++;
          double millis = reply.getNanoSeconds() / 1_000_000.0;
          System.out.println(
              reply.getSizeReceived()
                  + " bytes from "
                  + cli.destinationIa
                  + ","
                  + cli.destinationIp.getHostAddress()
                  + ": scmp_seq="
                  + reply.getSequenceNumber()
                  + " time="
                  + String.format(java.util.Locale.ROOT, "%.3f", millis)
                  + "ms");
        }

        if (sequence + 1 < cli.count) {
          Thread.sleep(cli.intervalMs);
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

  private static void configureSnap(Cli cli) {
    if (cli.apiKey != null) {
      System.setProperty(Constants.PROPERTY_SNAP_AUTH_SERVICE, "auth.scion.anapaya.net");
      System.setProperty(Constants.PROPERTY_SNAP_AUTH_KEY, cli.apiKey);
    }

    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");
    System.setProperty(
        Constants.PROPERTY_BOOTSTRAP_PATH_SERVICE,
        SnapDemoBootstrap.resolveBootstrapAddress(cli.endhostApi, cli.discoveryEndpoint));
    if (cli.snapControl != null) {
      System.setProperty(Constants.PROPERTY_SNAP_PATH_SERVICE, cli.snapControl);
    }
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
    final int count;
    final String snapToken;
    final int timeoutMs;
    final int intervalMs;
    final String payload;
    final String logLevel;
    final String apiKey;

    private Cli(
        String destinationIa,
        InetAddress destinationIp,
        String endhostApi,
        String discoveryEndpoint,
        String snapControl,
        int localPort,
        int count,
        String snapToken,
        int timeoutMs,
        int intervalMs,
        String payload,
        String logLevel,
        String apiKey) {
      this.destinationIa = destinationIa;
      this.destinationIp = destinationIp;
      this.endhostApi = endhostApi;
      this.discoveryEndpoint = discoveryEndpoint;
      this.snapControl = snapControl;
      this.localPort = localPort;
      this.count = count;
      this.snapToken = snapToken;
      this.timeoutMs = timeoutMs;
      this.intervalMs = intervalMs;
      this.payload = payload;
      this.logLevel = logLevel;
      this.apiKey = apiKey;
    }

    static Cli parse(String[] args) throws IOException {
      if (args.length == 0) {
        List<String> cl = Files.readAllLines(Paths.get("snap-ping-demo.txt"));
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
      int count = 1;
      String authKeyFile = null;
      String snapTokenFile = null;
      int timeoutMs = 3000;
      int intervalMs = 1000;
      String payload = "";
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
          case "--count":
            count = Integer.parseInt(args[++i]);
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
          case "--interval-ms":
            intervalMs = Integer.parseInt(args[++i]);
            break;
          case "--payload":
            payload = args[++i];
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
          count,
          snapToken,
          timeoutMs,
          intervalMs,
          payload,
          logLevel,
          tokenResolution.apiKey);
    }

    private static String usage() {
      return "Usage: SnapEchoDemo DEST_IA,[IP] (--endhost-api URL | --discovery URL) "
          + "--port PORT --count N --snap-token FILE [--snap-control URL] [--timeout-ms MS] "
          + "[--interval-ms MS] [--payload TEXT] [--log LEVEL]";
    }
  }
}
