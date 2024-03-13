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

import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.scion.proto.daemon.Daemon;

/**
 * A RequestPath is a Path with additional meta information such as bandwidth, latency or geo
 * coordinates. RequestPaths are created/returned by the ScionService when requesting a new path
 * from the control service.
 */
public class RequestPath extends Path {

  private final Daemon.Path pathProtoc;
  // We store the first hop separately to void creating unnecessary objects.
  private final InetSocketAddress firstHop;

  static RequestPath create(Daemon.Path path, long dstIsdAs, byte[] dstIP, int dstPort) {
    return new RequestPath(path, dstIsdAs, dstIP, dstPort);
  }

  private RequestPath(Daemon.Path path, long dstIsdAs, byte[] dstIP, int dstPort) {
    super(path.getRaw().toByteArray(), dstIsdAs, dstIP, dstPort);
    this.pathProtoc = path;
    if (getRawPath().length == 0) {
      // local AS has path length 0
      try {
        InetAddress address = InetAddress.getByAddress(getDestinationAddress());
        firstHop = new InetSocketAddress(address, getDestinationPort());
      } catch (UnknownHostException e) {
        // This is impossible, an IP address cannot be unknown
        throw new UncheckedIOException(e);
      }
    } else {
      firstHop = getFirstHopAddress(pathProtoc);
    }
  }

  private Daemon.Path protoPath() {
    if (pathProtoc == null) {
      throw new IllegalStateException(
          "Information is only available for paths that"
              + " were retrieved directly from a path server.");
    }
    return pathProtoc;
  }

  @Override
  public InetSocketAddress getFirstHopAddress() throws UnknownHostException {
    return firstHop;
  }

  private InetSocketAddress getFirstHopAddress(Daemon.Path internalPath) {
    try {
      String underlayAddressString = internalPath.getInterface().getAddress().getAddress();
      int splitIndex = underlayAddressString.indexOf(':');
      InetAddress ip = InetAddress.getByName(underlayAddressString.substring(0, splitIndex));
      int port = Integer.parseUnsignedInt(underlayAddressString.substring(splitIndex + 1));
      return new InetSocketAddress(ip, port);
    } catch (UnknownHostException e) {
      // This really should never happen, the first hop is a literal IP address.
      throw new UncheckedIOException(e);
    }
  }

  /**
   * @return Interface for exiting the local AS using this path.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public Interface getInterface() {
    return new Interface(protoPath().getInterface());
  }

  /**
   * @return The list of interfaces the path is composed of.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<PathInterface> getInterfacesList() {
    return Collections.unmodifiableList(
        protoPath().getInterfacesList().stream()
            .map(PathInterface::new)
            .collect(Collectors.toList()));
  }

  /**
   * @return The maximum transmission unit (MTU) on the path.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public int getMtu() {
    return protoPath().getMtu();
  }

  /**
   * @return The point in time when this path expires. In seconds since UNIX epoch.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public long getExpiration() {
    return protoPath().getExpiration().getSeconds();
  }

  /**
   * @return Latency lists the latencies between any two consecutive interfaces. Entry i describes
   *     the latency between interface i and i+1. Consequently, there are N-1 entries for N
   *     interfaces. A 0-value indicates that the AS did not announce a latency for this hop.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<Integer> getLatencyList() {
    return Collections.unmodifiableList(
        protoPath().getLatencyList().stream()
            .map(time -> (int) (time.getSeconds() * 1_000 + time.getNanos() / 1_000_000))
            .collect(Collectors.toList()));
  }

  /**
   * @return Bandwidth lists the bandwidth between any two consecutive interfaces, in Kbit/s. Entry
   *     i describes the bandwidth between interfaces i and i+1. A 0-value indicates that the AS did
   *     not announce a bandwidth for this hop.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<Long> getBandwidthList() {
    return protoPath().getBandwidthList();
  }

  /**
   * @return Geo lists the geographical position of the border routers along the path. Entry i
   *     describes the position of the router for interface i. A 0-value indicates that the AS did
   *     not announce a position for this router.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<GeoCoordinates> getGeoList() {
    return Collections.unmodifiableList(
        protoPath().getGeoList().stream().map(GeoCoordinates::new).collect(Collectors.toList()));
  }

  /**
   * @return LinkType contains the announced link type of inter-domain links. Entry i describes the
   *     link between interfaces 2*i and 2*i+1.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<LinkType> getLinkTypeList() {
    return Collections.unmodifiableList(
        protoPath().getLinkTypeList().stream()
            .map(linkType -> LinkType.values()[linkType.getNumber()])
            .collect(Collectors.toList()));
  }

  /**
   * @return InternalHops lists the number of AS internal hops for the ASes on path. Entry i
   *     describes the hop between interfaces 2*i+1 and 2*i+2 in the same AS. Consequently, there
   *     are no entries for the first and last ASes, as these are not traversed completely by the
   *     path.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<Integer> getInternalHopsList() {
    return protoPath().getInternalHopsList();
  }

  /**
   * @return Notes contains the notes added by ASes on the path, in the order of occurrence. Entry i
   *     is the note of AS i on the path.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<String> getNotesList() {
    return protoPath().getNotesList();
  }

  /**
   * @return EpicAuths contains the EPIC authenticators used to calculate the PHVF and LHVF.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public EpicAuths getEpicAuths() {
    return new EpicAuths(protoPath().getEpicAuths());
  }

  public enum LinkType {
    /** Unspecified link type. */
    LINK_TYPE_UNSPECIFIED, // = 0
    /** Direct physical connection. */
    LINK_TYPE_DIRECT, // = 1
    /** Connection with local routing/switching. */
    LINK_TYPE_MULTI_HOP, // = 2
    /** Connection overlayed over publicly routed Internet. */
    LINK_TYPE_OPEN_NET, // = 3
  }

  public static class EpicAuths {
    private final byte[] authPhvf;
    private final byte[] authLhvf;

    private EpicAuths(Daemon.EpicAuths epicAuths) {
      this.authPhvf = epicAuths.getAuthPhvf().toByteArray();
      this.authLhvf = epicAuths.getAuthLhvf().toByteArray();
    }

    /**
     * @return AuthPHVF is the authenticator use to calculate the PHVF.
     */
    public byte[] getAuthPhvf() {
      return authPhvf;
    }

    /**
     * @return AuthLHVF is the authenticator use to calculate the LHVF.
     */
    public byte[] getAuthLhvf() {
      return authLhvf;
    }
  }

  public static class Interface {
    private final String address;

    private Interface(Daemon.Interface inter) {
      this.address = inter.getAddress().getAddress();
    }

    /**
     * @return Underlay address to exit through the interface.
     */
    public String getAddress() {
      return address;
    }
  }

  public static class PathInterface {
    private final long isdAs;

    private final long id;

    private PathInterface(Daemon.PathInterface pathInterface) {
      this.isdAs = pathInterface.getIsdAs();
      this.id = pathInterface.getId();
    }

    /**
     * @return ISD-AS the interface belongs to.
     */
    public long getIsdAs() {
      return isdAs;
    }

    /**
     * @return ID of the interface in the AS.
     */
    public long getId() {
      return id;
    }
  }

  public static class GeoCoordinates {
    private final float latitude;
    private final float longitude;
    private final String address;

    private GeoCoordinates(Daemon.GeoCoordinates geoCoordinates) {
      this.latitude = geoCoordinates.getLatitude();
      this.longitude = geoCoordinates.getLongitude();
      this.address = geoCoordinates.getAddress();
    }

    /**
     * @return Latitude of the geographic coordinate, in the WGS 84 datum.
     */
    public float getLatitude() {
      return latitude;
    }

    /**
     * @return Longitude of the geographic coordinate, in the WGS 84 datum.
     */
    public float getLongitude() {
      return longitude;
    }

    /**
     * @return Civic address of the location.
     */
    public String getAddress() {
      return address;
    }
  }
}
