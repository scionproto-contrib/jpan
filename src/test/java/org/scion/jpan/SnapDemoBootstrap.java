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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared CLI/bootstrap plumbing for the SNAP demos ({@link SnapPacketDemo}, {@link SnapEchoDemo},
 * {@link SnapTracerouteDemo}), which otherwise all duplicate this logic verbatim.
 */
final class SnapDemoBootstrap {

  private SnapDemoBootstrap() {}

  /**
   * Returns {@code args} unchanged if non-empty, otherwise reads and tokenizes {@code
   * defaultFile} (one arg per line, blank lines and {@code //}-prefixed comment lines dropped).
   */
  static String[] readArgsOrDefaultFile(String[] args, String defaultFile) throws IOException {
    if (args.length != 0) {
      return args;
    }
    List<String> lines = Files.readAllLines(Paths.get(defaultFile));
    lines =
        lines.stream().map(String::trim).filter(s -> !s.startsWith("//")).collect(Collectors.toList());
    return lines.toArray(new String[0]);
  }

  /** A parsed {@code ISD-AS,[IP]} destination, e.g. {@code 64-2:0:9c,[::1]}. */
  static final class Destination {
    final String isdAs;
    final InetAddress ip;

    private Destination(String isdAs, InetAddress ip) {
      this.isdAs = isdAs;
      this.ip = ip;
    }
  }

  static Destination parseDestination(String destination) throws IOException {
    int separator = destination.indexOf(",[");
    if (separator <= 0 || !destination.endsWith("]")) {
      throw new IllegalArgumentException(
          "Destination must be in the form ISD-AS,[IP], for example 64-2:0:9c,[::1]");
    }
    String isdAs = destination.substring(0, separator);
    String ipLiteral = destination.substring(separator + 2, destination.length() - 1);
    return new Destination(isdAs, InetAddress.getByName(ipLiteral));
  }

  /** Reads an API key from {@code authKeyFile} and configures the AA auth-key properties. */
  static void configureAuthKey(String authKeyFile) throws IOException {
    String authKey = readAuthKeyFile(authKeyFile);
    System.setProperty(Constants.PROPERTY_SNAP_AUTH_SERVICE, "auth.scion.anapaya.net");
    System.setProperty(Constants.PROPERTY_SNAP_AUTH_KEY, authKey);
  }

  /** Reads a SNAP token from {@code snapTokenFile} and configures the SNAP auth-token property. */
  static void configureSnapToken(String snapTokenFile) throws IOException {
    System.setProperty(Constants.PROPERTY_SNAP_AUTH_TOKEN, readTokenFile(snapTokenFile));
  }

  /** Configures slf4j-simple to log at {@code level} with timestamps. */
  static void configureLogging(String level) {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
    System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
  }

  private static String readTokenFile(String snapTokenFile) throws IOException {
    String snapToken =
        new String(Files.readAllBytes(Paths.get(snapTokenFile)), StandardCharsets.UTF_8).trim();
    if (snapToken.isEmpty()) {
      throw new IllegalArgumentException("Token file is empty: " + snapTokenFile);
    }
    return snapToken;
  }

  private static String readAuthKeyFile(String authKeyFile) throws IOException {
    String authKey =
        new String(Files.readAllBytes(Paths.get(authKeyFile)), StandardCharsets.UTF_8).trim();
    if (authKey.isEmpty()) {
      throw new IllegalArgumentException("Auth key file is empty: " + authKeyFile);
    }
    return authKey;
  }

  /** Human-readable description of which endhost API source is in use, for demo log output. */
  static String endhostApiDescription(String endhostApi, String discoveryEndpoint) {
    return discoveryEndpoint != null ? "discovery:" + discoveryEndpoint : endhostApi;
  }
}
