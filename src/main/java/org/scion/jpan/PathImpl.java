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
abstract class PathImpl extends InetSocketAddress implements Path {
  private final long dstIsdAs;

  protected PathImpl(long dstIsdAs, InetAddress dstIP, int dstPort) {
    super(dstIP, dstPort);
    this.dstIsdAs = dstIsdAs;
  }

  public abstract byte[] getRawPath();

  public abstract InetSocketAddress getFirstHopAddress() throws UnknownHostException;

  @Deprecated // Use getPort() instead
  public int getRemotePort() {
    return getPort();
  }

  @Deprecated // Use getAddress() instead
  public InetAddress getRemoteAddress() {
    return getAddress();
  }

  @Deprecated // Use getIsdAs() instead
  public long getRemoteIsdAs() {
    return dstIsdAs;
  }

  /**
   * @return ISD/AS of the remote host.
   */
  public long getIsdAs() {
    return dstIsdAs;
  }

  @Override
  public String toString() {
    return "Path{"
        + "ISD/AS="
        + ScionUtil.toStringIA(dstIsdAs)
        + ", address="
        + super.toString()
        + ", pathRaw="
        + Arrays.toString(getRawPath())
        + '}';
  }
}
