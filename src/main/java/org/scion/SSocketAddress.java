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

import java.io.IOException;
import java.net.*;

/**
 * ScionSocketAddress is an InetSocketAddress + ISD/AS information.
 *
 * <p>A ScionPath may be assigned at construction of dynamically requested from the PathService when
 * calling getPath().
 *
 * <p>This class is threadsafe.
 */
public class SSocketAddress extends InetSocketAddress {
  private final long isdAs;
  private volatile BasePath path;

  private SSocketAddress(long isdAs, String hostName, int port, BasePath path) {
    // TODO this probably causes a DNS lookup, can we avoid that? Check!
    super(hostName, port);
    this.isdAs = isdAs;
    this.path = path;
  }

  private SSocketAddress(long isdAs, InetAddress inetAddress, int port, BasePath path) {
    super(inetAddress, port);
    this.isdAs = isdAs;
    this.path = path;
  }

  public static SSocketAddress create(String hostName, int port, BasePath path) {
    return new SSocketAddress(path.getDestinationIsdAs(), hostName, port, path);
  }

  public static SSocketAddress create(long isdAs, String hostName, int port) {
    return new SSocketAddress(isdAs, hostName, port, null);
  }

  private static SSocketAddress createUnresolved() {
    // DO NOT REMOVE! We hide the public static method from InetSocketAddress.
    throw new UnsupportedOperationException();
  }

  public static SSocketAddress create(InetSocketAddress address) throws ScionException {
    ScionAddress addr = ScionService.defaultService().getScionAddress(address.getHostString());
    // We need to use addr.HostName() because it is the SCION host!
    return new SSocketAddress(addr.getIsdAs(), address.getHostName(), address.getPort(), null);
  }

  public static SSocketAddress create(long isdAs, InetAddress addr, int port, BasePath path) {
    return new SSocketAddress(isdAs, addr, port, path);
  }

  public long getIsdAs() {
    return isdAs;
  }

  public int getIsd() {
    return ScionUtil.extractIsd(isdAs);
  }

//  /**
//   * Return a path to the address represented by this object. If no path is associated it will try
//   * to create one.
//   *
//   * @return The path associated with this address. If no path is associated, this method will first
//   *     look up the local ISD/AS and then look up a path to the remote ISD/AS.
//   * @throws ScionException if an errors occurs while querying paths.
//   */
//  public BasePath getPath() throws IOException {
//    return getPath(PathPolicy.DEFAULT);
//  }

  /**
   * Return a path to the address represented by this object. If no path is associated it will try
   * to create one.
   *
   * @return The path associated with this address. If no path is associated, this method will first
   *     look up the local ISD/AS and then look up a path to the remote ISD/AS.
   * @throws ScionException if an errors occurs while querying paths.
   */
  // TODO Think about how to do this better
  // TODO passing in a pathPolicy when it may not be used seems wrong!
  //    -> Rename method to getOrCreate()
  //       Also, make it explicit that this  method may be costly -> path lookup?
  //    -> Rename or create class SocketAddressWithPath / ResolvedSocketAddress
  //          to indicate that/if it has a path....?

  // TODO idea: A SocketAddress should really be fixed (like InetSocketAddress(?)!
  //      -> Either it has a path or is does not. Resolution happens externally.
  //      -> Resolves problems with
  //         - unintentional look up (performance)
  //         - pathPolicy parameter ambiguity (may not apply to returned path)
  //         - usage of correct daemon service instance.
  //      -> DOCUMENT THIS!

//  public BasePath getPath(PathPolicy pathPolicy) throws IOException {
//    if (path == null) {
//      long localIA = ScionService.defaultService().getLocalIsdAs();
//      path = ScionService.defaultService().getPath(localIA, isdAs, pathPolicy);
//    }
//    return path;
//  }
}
