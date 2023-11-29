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

import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * A ResponsePath is created/returned when receiving a packet. Besides being a Path, it contains
 * ISD/AS, IP and port of the local host. This is mostly for convenience to avoid looking up this
 * information, but it also ensures that the return packet header contains the exact information
 * sent/expected by the client.
 */
public class ResponsePath extends Path {

  private final InetSocketAddress firstHopAddress;
  // The ResponsePath gets source information from the incoming packet.
  private final long srcIsdAs;
  private final byte[] srcAddress;
  private final int srcPort;

  public static ResponsePath create(
      byte[] rawPath,
      long srcIsdAs,
      byte[] srcIP,
      int srcPort,
      long dstIsdAs,
      byte[] dstIP,
      int dstPort,
      InetSocketAddress firstHopAddress) {
    return new ResponsePath(
        rawPath, srcIsdAs, srcIP, srcPort, dstIsdAs, dstIP, dstPort, firstHopAddress);
  }

  private ResponsePath(
      byte[] rawPath,
      long srcIsdAs,
      byte[] srcIP,
      int srcPort,
      long dstIsdAs,
      byte[] dstIP,
      int dstPort,
      InetSocketAddress firstHopAddress) {
    super(rawPath, dstIsdAs, dstIP, dstPort);
    this.firstHopAddress = firstHopAddress;
    this.srcIsdAs = srcIsdAs;
    this.srcAddress = srcIP;
    this.srcPort = srcPort;
  }

  @Override
  public InetSocketAddress getFirstHopAddress() {
    return firstHopAddress;
  }

  public long getSourceIsdAs() {
    return srcIsdAs;
  }

  public byte[] getSourceAddress() {
    return srcAddress;
  }

  public int getSourcePort() {
    return srcPort;
  }

  @Override
  public String toString() {
    return "ResponsePath{"
        + super.toString()
        + ", firstHopAddress="
        + firstHopAddress
        + ", srcIsdAs="
        + srcIsdAs
        + ", srcAddress="
        + Arrays.toString(srcAddress)
        + ", srcPort="
        + srcPort
        + '}';
  }
}
