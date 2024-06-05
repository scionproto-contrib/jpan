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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Instant;

import org.scion.jpan.internal.IPHelper;
import org.scion.jpan.proto.daemon.Daemon;

/**
 * A RequestPath is a Path with additional meta information such as bandwidth, latency or geo
 * coordinates. RequestPaths are created/returned by the ScionService when requesting a new path
 * from the control service.
 */
public class RequestPath extends Path {

  private final PathMetadata metadata;
  // We store the first hop separately to void creating unnecessary objects.
  private final InetSocketAddress firstHop;

  static RequestPath create(Daemon.Path path, long dstIsdAs, InetAddress dstIP, int dstPort) {
    return new RequestPath(path, dstIsdAs, dstIP, dstPort);
  }

  private RequestPath(Daemon.Path path, long dstIsdAs, InetAddress dstIP, int dstPort) {
    super(path.getRaw().toByteArray(), dstIsdAs, dstIP, dstPort);
    this.metadata = PathMetadata.create(path);
    if (getRawPath().length == 0) {
      // local AS has path length 0
      firstHop = new InetSocketAddress(getRemoteAddress(), getRemotePort());
    } else {
      firstHop = getFirstHopAddress(path);
    }
  }

  @Override
  public InetSocketAddress getFirstHopAddress() throws UnknownHostException {
    return firstHop;
  }

  private InetSocketAddress getFirstHopAddress(Daemon.Path internalPath) {
    try {
      String underlayAddressString = internalPath.getInterface().getAddress().getAddress();
      int splitIndex = underlayAddressString.indexOf(':');
      InetAddress ip = IPHelper.toInetAddress(underlayAddressString.substring(0, splitIndex));
      int port = Integer.parseUnsignedInt(underlayAddressString.substring(splitIndex + 1));
      return new InetSocketAddress(ip, port);
    } catch (UnknownHostException e) {
      // This really should never happen, the first hop is a literal IP address.
      throw new UncheckedIOException(e);
    }
  }

  public PathMetadata getMetadata() {
    return metadata;
  }

//  /**
//   * @param service Service for obtaining new paths when required
//   * @param pathPolicy PathPolicy for selecting a new path when required
//   * @param expiryMargin Expiry margin, i.e. a path is considered "expired" if expiry is less than
//   *     expiryMargin seconds away.
//   * @return `false` if the path is `null`, it it is a ResponsePath or if it didn't need updating.
//   *     Returns `true` only if the path was updated.
//   */
//  synchronized boolean refreshPath(ScionService service, PathPolicy pathPolicy, int expiryMargin)
//          throws IOException {
////    if (path == null) {
////      path = pathPolicy.filter(service.getPaths(this));
////      if (path == null) {
////        throw new IOException("Address is not resolvable in SCION: " + super.toString());
////      }
////      return true;
////    }
////
////    if (!(path instanceof RequestPath)) {
////      return false;
////    }
////
////    RequestPath requestPath = (RequestPath) path;
//    if (Instant.now().getEpochSecond() + expiryMargin <= getMetadata().getExpiration()) {
//      return false;
//    }
//    // expired, get new path
//    path = pathPolicy.filter(service.getPaths(requestPath));
//    return true;
//  }
}
