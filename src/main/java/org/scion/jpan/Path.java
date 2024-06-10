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

package org.scion.jpan;

import java.net.*;
import java.util.Arrays;

/**
 * A Path is an InetSocketAddress/ISD/AS of a destination host plus a path to that host.
 *
 * <p>This class is thread safe.
 */
public abstract class Path {

  private final long dstIsdAs;
  private final InetAddress dstAddress;
  private final int dstPort;

  protected Path(long dstIsdAs, InetAddress dstIP, int dstPort) {
    this.dstIsdAs = dstIsdAs;
    this.dstAddress = dstIP;
    this.dstPort = dstPort;
  }

  public abstract byte[] getRawPath();

  public abstract InetSocketAddress getFirstHopAddress() throws UnknownHostException;

  public int getRemotePort() {
    return dstPort;
  }

  public InetAddress getRemoteAddress() {
    return dstAddress;
  }

  public long getRemoteIsdAs() {
    return dstIsdAs;
  }

  @Override
  public String toString() {
    try {
      return "Path{"
          + "rmtIsdAs="
          + ScionUtil.toStringIA(dstIsdAs)
          + ", rmtAddress="
          + dstAddress
          + ", rmtPort="
          + dstPort
          + ", firstHop="
          + getFirstHopAddress()
          + ", pathRaw="
          + Arrays.toString(getRawPath())
          + '}';
    } catch (UnknownHostException e) {
      throw new ScionRuntimeException(e);
    }
  }
}
