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

import java.net.*;

public class ScionSocketAddress extends InetSocketAddress {
  private final long isdAs;

  private ScionSocketAddress(long isdAs, String hostName,int port) {
    super(hostName, port);
    this.isdAs = isdAs;
  }

//  static ScionSocketAddress create(long isdAs, String hostName, String ipString, int port) {
//    InetAddress ip;
//    try {
//      if (ipString.indexOf('.') > 0) {
//        ip = Inet4Address.getByName(ipString);
//      } else {
//        // Must be IPv6 or invalid
//        ip = Inet6Address.getByName(ipString);
//      }
//    } catch (UnknownHostException e) {
//      // This should never happen because we always call getByName() with an IP address
//      throw new RuntimeException(e);
//    }
//    return new ScionSocketAddress(isdAs, hostName, port);
//  }

  public static ScionSocketAddress create(String isdAs, String hostName, int port) {
    long isdAsCode = ScionUtil.ParseIA(isdAs);
    return new ScionSocketAddress(isdAsCode, hostName, port);
  }

  public long getIsdAs() {
    return isdAs;
  }

  public int getIsd() {
    return ScionUtil.extractIsd(isdAs);
  }
}
