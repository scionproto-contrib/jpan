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
import java.util.Arrays;

/**
 * A Path is an InetSocketAddress/ISD/AS of a destination host plu a path to that host.
 *
 * <p>This class is threadsafe.
 */
public abstract class Path {
  private final byte[] pathRaw;
  private final long dstIsdAs;
  private final byte[] dstAddress;
  private final int dstPort;

  protected Path(byte[] rawPath, long dstIsdAs, byte[] dstIP, int dstPort) {
    this.pathRaw = rawPath;
    this.dstIsdAs = dstIsdAs;
    this.dstAddress = dstIP;
    this.dstPort = dstPort;
  }

  public byte[] getRawPath() {
    return pathRaw;
  }

  public abstract InetSocketAddress getFirstHopAddress() throws UnknownHostException;

  public int getDestinationPort() {
    return dstPort;
  }

  public byte[] getDestinationAddress() {
    return dstAddress;
  }

  public long getDestinationIsdAs() {
    return dstIsdAs;
  }

  @Override
  public String toString() {
    return "Path{"
        + "dstIsdAs="
        + ScionUtil.toStringIA(dstIsdAs)
        + ", dstAddress="
        + Arrays.toString(dstAddress)
        + ", dstPort="
        + dstPort
        + ", pathRaw="
        + Arrays.toString(pathRaw)
        + '}';
  }
}
