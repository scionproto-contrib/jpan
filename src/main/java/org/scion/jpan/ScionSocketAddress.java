// Copyright 2024 ETH Zurich
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;

/**
 * A ScionSocketAddress is an InetSocketAddress with an attached path.
 *
 * <p>The address refers to a SCION enabled host. The `fromNonScionIP` method can be used to create
 * a SCION address from a non-SCION IP.
 *
 * <p>The address part is immutable and path objects are immutable, but the address can be assigned
 * a new path. This is necessary to allow paths to be refreshed once they expire, if the policy
 * changes or if the interface changes.
 *
 * <p>The address represents the destination address of the path.
 */
public class ScionSocketAddress extends InetSocketAddress {

  private final long isdAs;
  private transient Path path;

  // TODO enable?
  //  public ScionSocketAddress(long isdAs, String hostname, int port) {
  //    super(hostname, port);
  //    this.isdAs = isdAs;
  //  }

  // TODO We can make them public later
  private ScionSocketAddress(long isdAs, InetAddress addr, int port) {
    super(addr, port);
    this.isdAs = isdAs;
  }

  // TODO We can make them public later
  private ScionSocketAddress(long isdAs, InetSocketAddress addr) {
    super(addr.getAddress(), addr.getPort());
    this.isdAs = isdAs;
  }

  public static ScionSocketAddress fromPath(Path path) {
    ScionSocketAddress a =
        new ScionSocketAddress(
            path.getRemoteIsdAs(), path.getRemoteAddress(), path.getRemotePort());
    a.path = path;
    return a;
  }

  public static ScionSocketAddress fromScionAddress(ScionAddress address, int port) {
    return new ScionSocketAddress(address.getIsdAs(), address.getInetAddress(), port);
  }

  public static ScionSocketAddress fromNonScionIP(InetSocketAddress address) throws ScionException {
    return fromNonScionIP(Scion.defaultService(), address.getAddress(), address.getPort());
  }

  public static ScionSocketAddress fromNonScionIP(InetAddress address, int port)
      throws ScionException {
    return fromNonScionIP(Scion.defaultService(), address, port);
  }

  public static ScionSocketAddress fromNonScionIP(
      ScionService service, InetAddress address, int port) throws ScionException {
    return service.lookupSocketAddress(address.getHostName(), port);
  }

  public static ScionSocketAddress fromScionIP(long isdAs, InetAddress address, int port) {
    return new ScionSocketAddress(isdAs, address, port);
  }

  /**
   * @return the path currently associated with this address. May return 'null'.
   */
  public Path getPath() {
    return this.path;
  }

  /**
   * @param service Service for obtaining new paths when required
   * @param pathPolicy PathPolicy for selecting a new path when required
   * @param expiryMargin Expiry margin, i.e. a path is considered "expired" if expiry is less than
   *     expiryMargin seconds away.
   * @return `false` if the path is `null`, it it is a ResponsePath or if it didn't need updating.
   *     Returns `true` only if the path was updated.
   */
  synchronized boolean refreshPath(ScionService service, PathPolicy pathPolicy, int expiryMargin)
      throws IOException {
    if (path == null) {
      path = pathPolicy.filter(service.getPaths(this));
      if (path == null) {
        throw new IOException("Address is not resolvable in SCION: " + super.toString());
      }
      return true;
    }

    if (!(path instanceof RequestPath)) {
      return false;
    }

    RequestPath requestPath = (RequestPath) path;
    if (Instant.now().getEpochSecond() + expiryMargin <= requestPath.getExpiration()) {
      return false;
    }
    // expired, get new path
    path = pathPolicy.filter(service.getPaths(requestPath));
    return true;
  }

  /**
   * @return 'true' iff the path associated with this address is an instance of RequestPath.
   */
  public boolean isRequestAddress() {
    return path instanceof RequestPath;
  }

  public long getIsdAs() {
    return isdAs;
  }
}
