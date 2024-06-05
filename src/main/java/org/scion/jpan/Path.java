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
 *
 * <p>Design considerations:<br>
 * - Having Path subclass InetSocketAddress may feel a bit awkward, not least because
 * getAddress()/getPort() are not immediately clear to refer to the remote host. However,
 * subclassing InetSocketAddress allows paths to be returned by DatagramChannel.receive(), which
 * would otherwise not be possible.<br>
 * - Having two sublasses of Path ensures that RequestPaths and ResponsePath are never mixed up.<br>
 * - The design also allows immutability, and thus thread safety.<br>
 * - Having metadata in a separate class makes the API cleaner.
 */
public abstract class Path extends InetSocketAddress {
  private final byte[] pathRaw;
  private final long dstIsdAs;

  protected Path(byte[] rawPath, long dstIsdAs, InetAddress dstIP, int dstPort) {
    super(dstIP, dstPort);
    this.pathRaw = rawPath;
    this.dstIsdAs = dstIsdAs;
  }

  public byte[] getRawPath() {
    return pathRaw;
  }

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
        + Arrays.toString(pathRaw)
        + '}';
  }
}
