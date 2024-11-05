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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.xbill.DNS.Address;

public class IPHelper {
  private IPHelper() {}

  public static boolean isLocalhost(String hostName) {
    return hostName.startsWith("127.0.0.")
        || "::1".equals(hostName)
        || "0:0:0:0:0:0:0:1".equals(hostName)
        || "localhost".equals(hostName)
        || "ip6-localhost".equals(hostName);
  }

  public static byte[] lookupLocalhost(String hostName) {
    if ("localhost".equals(hostName)) {
      return new byte[] {127, 0, 0, 1};
    }
    if (hostName.startsWith("127.0.0.")) {
      return Address.toByteArray(hostName, Address.IPv4);
    }

    if ("::1".equals(hostName)
        || "0:0:0:0:0:0:0:1".equals(hostName)
        || "ip6-localhost".equals(hostName)) {
      return new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    }
    return null;
  }

  public static byte[] toByteArray(String s) {
    if ("localhost".equals(s)) {
      return new byte[] {127, 0, 0, 1};
    }
    if ("ip6-localhost".equals(s)) {
      return new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    }
    if (s.startsWith("[")) {
      if (s.endsWith("]")) {
        s = s.substring(1, s.length() - 1);
      } else {
        return null; // Missing closing bracket. Something is wrong.
      }
    }
    int family = Address.isDottedQuad(s) ? Address.IPv4 : Address.IPv6;
    return Address.toByteArray(s, family);
  }

  public static int toPort(String s) {
    int posPort = s.lastIndexOf(":");
    return Integer.parseInt(s.substring(posPort + 1));
  }

  public static InetAddress toInetAddress(String s) throws UnknownHostException {
    byte[] bytes = toByteArray(s);
    return InetAddress.getByAddress(bytes);
  }

  public static InetSocketAddress toInetSocketAddress(String s) {
    int posPort = s.lastIndexOf(":");
    int port = Integer.parseInt(s.substring(posPort + 1));
    byte[] bytes = toByteArray(s.substring(0, posPort));
    try {
      return new InetSocketAddress(InetAddress.getByAddress(bytes), port);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static String extractIP(String s) {
    int posPort = s.lastIndexOf(":");
    return s.substring(0, posPort);
  }

  /**
   * Checks if the address string contains a port. If not, it appends the default port.
   *
   * @param address Address string with or without port
   * @param port default port
   * @return address with port.
   */
  public static String ensurePortOrDefault(String address, int port) {
    // IPv4? ('.')
    if (address.indexOf('.') >= 0) {
      if (address.indexOf(':') < 0) {
        return address + ":" + port;
      }
      return address;
    }
    // IPv6? (multiple ':')
    if (address.indexOf(':') != address.lastIndexOf(':')) {
      if (address.endsWith("]")) {
        return address + ":" + port;
      }
      if (address.contains("]")) {
        return address;
      }
      return "[" + address + "]:" + port;
    }
    // something else (localhost, or port-only)
    if (address.contains(":")) {
      return address;
    }
    if (address.matches("\\d+")) {
      return "localhost:" + address;
    }
    return address + ':' + port;
  }
}
