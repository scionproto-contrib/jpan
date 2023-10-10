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

import org.scion.internal.PathHeaderScion;

import java.net.*;

public class ScionSocketAddress extends InetSocketAddress {
  private final long isdAs;
  private final ScionPath path;
  private final PathHeaderScion pathHeader;
  private boolean isOwnedBySocket = false;

  private ScionSocketAddress(long isdAs, String hostName, int port, ScionPath path, PathHeaderScion pathHeader) {
    // TODO this probably causes a DNS lookup, can we avoid that? Check!
    super(hostName, port);
    this.isdAs = isdAs;
    this.path = path;
    this.pathHeader = pathHeader;
  }

  public static ScionSocketAddress create(String isdAs, String hostName, int port) {
    long isdAsCode = ScionUtil.ParseIA(isdAs);
    return new ScionSocketAddress(isdAsCode, hostName, port, null, null);
  }

  public static ScionSocketAddress create(String isdAs, String hostName, int port, ScionPath path) {
    long isdAsCode = ScionUtil.ParseIA(isdAs);
    return new ScionSocketAddress(isdAsCode, hostName, port, path, null);
  }

  static ScionSocketAddress create(long isdAs, String hostName, int port, PathHeaderScion pathHeader) {
    return new ScionSocketAddress(isdAs, hostName, port, null, pathHeader);
  }

  public long getIsdAs() {
    return isdAs;
  }

  public int getIsd() {
    return ScionUtil.extractIsd(isdAs);
  }

  public boolean hasPath() {
    return path != null || pathHeader != null;
  }

  /**
   * @param flag Indicating whether this instance is currently "owned" by a DatagramChannel.
   *             If it is owned, then the owner can do as they like with the instance, e.g. reverse it.
   *             If it is owned by another DatagramChannel then it must be copied before it can be modified.
   *             // TODO reverse it defensively when received -> can be used by all Channels
   *             //     or by a single channel
   *             //     -> Potentially problematic: Timestamp/timeout and varying TTL
   *             //        -> Can be written during send()
   */
  @Deprecated // See TODO -> remove this if possible
  synchronized void setOwnedBySocket(boolean flag) {
    isOwnedBySocket = flag;
  }

  synchronized boolean getOwnedBySocket() {
    return isOwnedBySocket;
  }
}
