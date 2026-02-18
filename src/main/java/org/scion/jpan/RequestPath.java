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

import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.proto.daemon.Daemon;

/**
 * A RequestPath is a Path with additional meta information such as bandwidth, latency or geo
 * coordinates. RequestPaths are created/returned by the ScionService when requesting a new path
 * from the control service.
 */
public class RequestPath extends Path {

  private final PathMetadata metadata;

  static RequestPath create(
      Daemon.Path path, long srcIsdAs, long dstIsdAs, InetAddress dstIP, int dstPort) {
    // path length 0 means "local AS"
    InetSocketAddress firstHop;
    if (path.getRaw().isEmpty()) {
      firstHop = new InetSocketAddress(dstIP, dstPort);
    } else {
      firstHop = getFirstHopAddress(path);
    }
    return new RequestPath(path, firstHop, srcIsdAs, dstIsdAs, dstIP, dstPort);
  }

  static RequestPath create(
      PathMetadata metadata, long srcIsdAs, long dstIsdAs, InetAddress dstIP, int dstPort) {
    // path length 0 means "local AS"
    InetSocketAddress firstHop;
    if (metadata.getRawPath().length == 0) {
      firstHop = new InetSocketAddress(dstIP, dstPort);
    } else {
      firstHop = IPHelper.toInetSocketAddress(metadata.getInterface().getAddress());
    }
    return new RequestPath(metadata, firstHop, srcIsdAs, dstIsdAs, dstIP, dstPort);
  }

  @Override
  public Path copy(InetAddress dstIP, int dstPort) {
    return new RequestPath(
        metadata, getFirstHopAddress(), getLocalIsdAs(), getRemoteIsdAs(), dstIP, dstPort);
  }

  private RequestPath(
      Daemon.Path path,
      InetSocketAddress firstHop,
      long srcIsdAs,
      long dstIsdAs,
      InetAddress dstIP,
      int dstPort) {
    super(path.getRaw().toByteArray(), firstHop, srcIsdAs, dstIsdAs, dstIP, dstPort);
    this.metadata = PathMetadata.create(path);
  }

  private RequestPath(
      PathMetadata metadata,
      InetSocketAddress firstHop,
      long srcIsdAs,
      long dstIsdAs,
      InetAddress dstIP,
      int dstPort) {
    super(metadata.getRawPath(), firstHop, srcIsdAs, dstIsdAs, dstIP, dstPort);
    this.metadata = metadata;
  }

  private static InetSocketAddress getFirstHopAddress(Daemon.Path internalPath) {
    try {
      String underlayAddressString = internalPath.getInterface().getAddress().getAddress();
      int splitIndex = underlayAddressString.lastIndexOf(':');
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

  @Override
  public boolean equals(Object o) {
    // We do NOT compare the metadata.
    return super.equals(o);
  }

  @Override
  public int hashCode() {
    // We do NOT compare the metadata.
    return super.hashCode();
  }
}
