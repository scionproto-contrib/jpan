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
 * ScionSocketAddress is an InetSocketAddress + ISD/AS information.
 * <p>
 * A ScionPath may be assigned at construction of dynamically requested from
 * the PathService when calling getPath().
 * <p>
 * This class is threadsafe.
 */
public class ScionSocketAddress extends InetSocketAddress {
  private final long isdAs;
  private volatile ScionPath path;

  private ScionSocketAddress(long isdAs, String hostName, int port, ScionPath path) {
    // TODO this probably causes a DNS lookup, can we avoid that? Check!
    super(hostName, port);
    this.isdAs = isdAs;
    this.path = path;
  }

  private ScionSocketAddress(long isdAs, InetAddress inetAddress, int port, ScionPath path) {
    super(inetAddress, port);
    this.isdAs = isdAs;
    this.path = path;
  }

  // TODO clean up create() methods: which ones are needed/useful? Order of arguments?

  public static ScionSocketAddress create(String isdAs, String hostName, int port) {
    long isdAsCode = ScionUtil.parseIA(isdAs);
    return new ScionSocketAddress(isdAsCode, hostName, port, null);
  }

  public static ScionSocketAddress create(String hostName, int port, ScionPath path) {
    return new ScionSocketAddress(path.getDestinationIsdAs(), hostName, port, path);
  }

  @Deprecated
  static ScionSocketAddress create(long isdAs, String hostName, int port) {
    return new ScionSocketAddress(isdAs, hostName, port, null);
  }

  private static ScionSocketAddress createUnresolved() {
    // We hide the public static method from InetSocketAddress
    throw new UnsupportedOperationException();
  }

  public static ScionSocketAddress create(InetSocketAddress address) {
    ScionAddress addr = ScionService.defaultService().getScionAddress(address.getHostString());
    // TODO address.getHostName() vs addr.getHostName()?
    return new ScionSocketAddress(
        addr.getIsdAs(), addr.getHostName(), address.getPort(), null);
  }

  public static ScionSocketAddress create(long isdAs, InetAddress addr, int port, ScionPath path) {
    return new ScionSocketAddress(isdAs, addr, port, path);
  }

  public long getIsdAs() {
    return isdAs;
  }

  public int getIsd() {
    return ScionUtil.extractIsd(isdAs);
  }

  /**
   * Return a path to the address represented by this object. If no path is associated it will try
   * to create one.
   *
   * @return The path associated with this address. If no path is associated, this method will first
   *     look up the local ISD/AS and then look up a path to the remote ISD/AS.
   */
  public ScionPath getPath() {
    if (path == null) {
      path = ScionService.defaultService().getPath(isdAs);
    }
    return path;
  }

  public boolean hasPath() {
    return path != null;
  }

  public ScionAddress getScionAddress() {
    return new ScionAddress(isdAs, getHostName(), super.getAddress());
  }
}
