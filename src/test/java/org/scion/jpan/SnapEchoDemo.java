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

/** SCMP echo demo for JPAN using Endhost API bootstrap and SNAP underlay encapsulation. */
public class SnapEchoDemo {

  private SnapEchoDemo() {}

  public static void main(String[] args) throws Exception {
    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");

    Cli cli = Cli.parse(args);

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

  private static final class Cli {
    final String destinationIa;
    final InetAddress destinationIp;
    final int localPort;
    final int count;
    final int timeoutMs;
    final int intervalMs;
    final String payload;

    private Cli(
        String destinationIa,
        InetAddress destinationIp,
        int localPort,
        int count,
        int timeoutMs,
        int intervalMs,
        String payload) {
      this.destinationIa = destinationIa;
      this.destinationIp = destinationIp;
      this.localPort = localPort;
      this.count = count;
      this.timeoutMs = timeoutMs;
      this.intervalMs = intervalMs;
      this.payload = payload;
    }

    static Cli parse(String[] args) throws IOException {
      args = SnapDemoBootstrap.readArgsOrDefaultFile(args, "snap-ping-demo.txt");

      String destination = null;
      String endhostApi = null;
      String discoveryEndpoint = null;
      String snapControl = null;
      int localPort = 0;
      int count = 1;
      String authKeyFile = null;
      String snapTokenFile = null;
      int timeoutMs = 3000;
      int intervalMs = 1000;
      String payload = "";

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
          case "--count":
            count = Integer.parseInt(args[++i]);
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
          case "--interval-ms":
            intervalMs = Integer.parseInt(args[++i]);
            break;
          case "--payload":
            payload = args[++i];
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

      return new Cli(dst.isdAs, dst.ip, localPort, count, timeoutMs, intervalMs, payload);
    }

    private static String usage() {
      return "Usage: SnapEchoDemo DEST_IA,[IP] (--endhost-api URL | --discovery URL) "
          + "--port PORT --count N --snap-token FILE [--snap-control URL] [--timeout-ms MS] "
          + "[--interval-ms MS] [--payload TEXT] [--log LEVEL]";
    }
  }
}
