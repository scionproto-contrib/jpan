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

package org.scion;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * ScionAddress is an InetAddress + ISD/AS information.
 *
 * <p>This class is threadsafe.
 */
public class ScionAddress {
  private final long isdAs;
  private final String hostName;
  private final InetAddress ipAddress;

  private ScionAddress(long isdAs, String hostName, InetAddress ip) {
    this.hostName = hostName;
    this.ipAddress = ip;
    this.isdAs = isdAs;
  }

  static ScionAddress create(long isdAs, String hostName, String ipString) {
    InetAddress ip;
    try {
      if (ipString.indexOf('.') > 0) {
        ip = Inet4Address.getByName(ipString);
      } else {
        // Must be IPv6 or invalid
        ip = Inet6Address.getByName(ipString);
      }
    } catch (UnknownHostException e) {
      // This should never happen because we always call getByName() with an IP address
      throw new RuntimeException(e);
    }
    return new ScionAddress(isdAs, hostName, ip);
  }

  public long getIsdAs() {
    return isdAs;
  }

  public String getHostName() {
    return hostName;
  }

  public InetAddress getInetAddress() {
    return ipAddress;
  }

  public int getIsd() {
    return ScionUtil.extractIsd(isdAs);
  }
}
