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

/**
 * TODO fix
 * ScionSocketAddress is an InetSocketAddress + ISD/AS information.
 *
 * <p>A ScionPath may be assigned at construction of dynamically requested from the PathService when
 * calling getPath().
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

  @Deprecated // TODO rename to DST...
  public long getIsdAs() {
    return dstIsdAs;
  }

  @Deprecated // TODO rename to DST...
  public int getIsd() {
    return ScionUtil.extractIsd(dstIsdAs);
  }

  // TODO doc
  public byte[] getRawPath() {
    return pathRaw;
  }

  public abstract InetSocketAddress getFirstHopAddress() throws UnknownHostException;

  @Deprecated // TODO do we need this?
  public InetAddress getAddress() throws UnknownHostException {
    return InetAddress.getByAddress(dstAddress);
  }

  public int getPort() {
    return dstPort;
  }

  public byte[] getDestinationAddress() {
    return dstAddress;
  }

  @Deprecated // TODO rename? Move in ScionAddres??
  public long getDestinationIsdAs() {
    return dstIsdAs;
  }
}
