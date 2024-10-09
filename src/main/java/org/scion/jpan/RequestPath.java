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
import java.net.UnknownHostException;
import org.scion.jpan.proto.daemon.Daemon;

/**
 * A RequestPath is a Path with additional meta information such as bandwidth, latency or geo
 * coordinates. RequestPaths are created/returned by the ScionService when requesting a new path
 * from the control service.
 */
public class RequestPath extends Path {

  private final PathMetadata metadata;

  static RequestPath create(Daemon.Path path, long dstIsdAs, InetAddress dstIP, int dstPort) {
    return new RequestPath(path, dstIsdAs, dstIP, dstPort);
  }

  @Override
  public Path copy(InetAddress dstIP, int dstPort) {
    return new RequestPath(metadata, getRemoteIsdAs(), dstIP, dstPort);
  }

  private RequestPath(Daemon.Path path, long dstIsdAs, InetAddress dstIP, int dstPort) {
    super(path.getRaw().toByteArray(), dstIsdAs, dstIP, dstPort);
    this.metadata = PathMetadata.create(path, dstIP, dstPort);
  }

  private RequestPath(PathMetadata metadata, long dstIsdAs, InetAddress dstIP, int dstPort) {
    super(metadata.getRawPath(), dstIsdAs, dstIP, dstPort);
    this.metadata = metadata;
  }

  @Override
  public InetSocketAddress getFirstHopAddress() throws UnknownHostException {
    return metadata.getFirstHopAddress();
  }

  public PathMetadata getMetadata() {
    return metadata;
  }
}
