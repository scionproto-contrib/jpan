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

package org.scion.jpan.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;

/**
 * ScionAddress is an InetAddress + ISD/AS information.
 *
 * <p>This class is threadsafe.
 */
public class ScionAddress {
  private final long isdAs;
  private final InetAddress ipAddress;

  private ScionAddress(long isdAs, InetAddress ip) {
    this.ipAddress = ip;
    this.isdAs = isdAs;
  }

  public static ScionAddress create(long isdAs, InetAddress address) {
    return new ScionAddress(isdAs, address);
  }

  static ScionAddress create(long isdAs, String hostName, byte[] ipBytes) {
    try {
      InetAddress ip = InetAddress.getByAddress(hostName, ipBytes);
      return new ScionAddress(isdAs, ip);
    } catch (UnknownHostException e) {
      // This should never happen because we always call getByName() with an IP address
      throw new ScionRuntimeException(e);
    }
  }

  public long getIsdAs() {
    return isdAs;
  }

  public String getHostName() {
    return ipAddress.getHostName();
  }

  public InetAddress getInetAddress() {
    return ipAddress;
  }

  public int getIsd() {
    return ScionUtil.extractIsd(isdAs);
  }
}
