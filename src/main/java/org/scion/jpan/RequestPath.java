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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;
import org.scion.jpan.internal.util.IPHelper;

/**
 * A RequestPath is a Path with additional meta information such as bandwidth, latency or geo
 * coordinates. RequestPaths are created/returned by the ScionService when requesting a new path
 * from the control service.
 */
public class RequestPath extends Path {

  private final PathMetadata metadata;

  @Deprecated
  static RequestPath create(
      PathMetadata metadata, long srcIsdAs, long dstIsdAs, InetAddress dstIP, int dstPort) {
    // path length 0 means "local AS"
    InetSocketAddress firstHop;
    if (metadata.getRawPath().length == 0) {
      firstHop = new InetSocketAddress(dstIP, dstPort);
    } else {
      firstHop = IPHelper.toInetSocketAddress(metadata.getLocalInterface().getAddress());
    }
    return new RequestPath(metadata, firstHop, srcIsdAs, dstIsdAs, dstIP, dstPort);
  }

  static RequestPath create(
      PathMetadata metadata, Set<Long> isdAses, InetAddress dstIP, int dstPort) {
    // path length 0 means "local AS"
    InetSocketAddress firstHop;
    long srcIsdAs;
    long dstIsdAs;
    if (metadata.getRawPath().length == 0) {
      firstHop = new InetSocketAddress(dstIP, dstPort);
      if (isdAses.isEmpty()) {
        srcIsdAs = 0;
      } else {
        srcIsdAs = isdAses.iterator().next();
      }
      dstIsdAs = srcIsdAs;
    } else {
      firstHop = IPHelper.toInetSocketAddress(metadata.getLocalInterface().getAddress());
      srcIsdAs = metadata.getSrcIdsAs();
      dstIsdAs = metadata.getDstIdsAs();
    }
    return new RequestPath(metadata, firstHop, srcIsdAs, dstIsdAs, dstIP, dstPort);
  }

  @Override
  public Path copy(InetAddress dstIP, int dstPort) {
    return new RequestPath(
        metadata, getFirstHopAddress(), getLocalIsdAs(), getRemoteIsdAs(), dstIP, dstPort);
  }

  @Deprecated
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

  private RequestPath(
      PathMetadata metadata, InetSocketAddress firstHop, InetAddress dstIP, int dstPort) {
    super(
        metadata.getRawPath(),
        firstHop,
        metadata.getSrcIdsAs(),
        metadata.getDstIdsAs(),
        dstIP,
        dstPort);
    this.metadata = metadata;
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
