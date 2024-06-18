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
import java.util.List;
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

  private RequestPath(Daemon.Path path, long dstIsdAs, InetAddress dstIP, int dstPort) {
    super(path.getRaw().toByteArray(), dstIsdAs, dstIP, dstPort);
    this.metadata = PathMetadata.create(path, dstIP, dstPort);
  }

  @Override
  public InetSocketAddress getFirstHopAddress() throws UnknownHostException {
    return metadata.getFirstHopAddress();
  }

  /**
   * @return Interface for exiting the local AS using this path.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public PathMetadata.Interface getInterface() {
    return metadata.getInterface();
  }

  /**
   * @return The list of interfaces the path is composed of.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public List<PathMetadata.PathInterface> getInterfacesList() {
    return metadata.getInterfacesList();
  }

  /**
   * @return The maximum transmission unit (MTU) on the path.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public int getMtu() {
    return metadata.getMtu();
  }

  /**
   * @return The point in time when this path expires. In seconds since UNIX epoch.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public long getExpiration() {
    return metadata.getExpiration();
  }

  /**
   * @return Latency lists the latencies between any two consecutive interfaces. Entry i describes
   *     the latency between interface i and i+1. Consequently, there are N-1 entries for N
   *     interfaces. A 0-value indicates that the AS did not announce a latency for this hop.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public List<Integer> getLatencyList() {
    return metadata.getLatencyList();
  }

  /**
   * @return Bandwidth lists the bandwidth between any two consecutive interfaces, in Kbit/s. Entry
   *     i describes the bandwidth between interfaces i and i+1. A 0-value indicates that the AS did
   *     not announce a bandwidth for this hop.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public List<Long> getBandwidthList() {
    return metadata.getBandwidthList();
  }

  /**
   * @return Geo lists the geographical position of the border routers along the path. Entry i
   *     describes the position of the router for interface i. A 0-value indicates that the AS did
   *     not announce a position for this router.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public List<PathMetadata.GeoCoordinates> getGeoList() {
    return metadata.getGeoList();
  }

  /**
   * @return LinkType contains the announced link type of inter-domain links. Entry i describes the
   *     link between interfaces 2*i and 2*i+1.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public List<PathMetadata.LinkType> getLinkTypeList() {
    return metadata.getLinkTypeList();
  }

  /**
   * @return InternalHops lists the number of AS internal hops for the ASes on path. Entry i
   *     describes the hop between interfaces 2*i+1 and 2*i+2 in the same AS. Consequently, there
   *     are no entries for the first and last ASes, as these are not traversed completely by the
   *     path.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public List<Integer> getInternalHopsList() {
    return metadata.getInternalHopsList();
  }

  /**
   * @return Notes contains the notes added by ASes on the path, in the order of occurrence. Entry i
   *     is the note of AS i on the path.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public List<String> getNotesList() {
    return metadata.getNotesList();
  }

  /**
   * @return EpicAuths contains the EPIC authenticators used to calculate the PHVF and LHVF.
   * @throws IllegalStateException if this is path is only a raw path
   * @deprecated To be removed in 0.3.0
   */
  @Deprecated // To be removed in 0.3.0
  public PathMetadata.EpicAuths getEpicAuths() {
    return metadata.getEpicAuths();
  }

  public PathMetadata getMetadata() {
    return metadata;
  }
}
