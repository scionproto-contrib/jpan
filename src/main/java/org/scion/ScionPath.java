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

import com.google.protobuf.ByteString;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.scion.proto.daemon.Daemon;

/**
 * A SCION path represents a single path from a source to a destination. Paths can be retrieved from
 * the ScionService.
 *
 * <p>This class is threadsafe.
 */
public class ScionPath {
  private final Daemon.Path pathProtoc;
  // ScionPath is basically immutable, it may be accessed in multiple thread concurrently.
  private volatile byte[] pathRaw;
  private final long srcIsdAs;
  private final long dstIsdAs;
  private final InetSocketAddress firstHopAddress;

  private ScionPath(Daemon.Path path, long srcIsdAs, long dstIsdAs) {
    this.pathProtoc = path;
    this.pathRaw = null;
    this.srcIsdAs = srcIsdAs;
    this.dstIsdAs = dstIsdAs;
    this.firstHopAddress = null;
  }

  private ScionPath(byte[] path, long srcIsdAs, long dstIsdAs, InetSocketAddress firstHopAddress) {
    this.pathProtoc = null;
    this.pathRaw = path;
    this.srcIsdAs = srcIsdAs;
    this.dstIsdAs = dstIsdAs;
    this.firstHopAddress = firstHopAddress;
  }

  public static ScionPath create(
      byte[] rawPath, long srcIsdAs, long dstIsdAs, InetSocketAddress firstHopAddress) {
    return new ScionPath(rawPath, srcIsdAs, dstIsdAs, firstHopAddress);
  }

  static ScionPath createUnresolved(Daemon.Path path, long srcIsdAs, long dstIsdAs) {
    return new ScionPath(path, srcIsdAs, dstIsdAs);
  }

  public long getDestinationIsdAs() {
    return dstIsdAs;
  }

  public long getSourceIsdAs() {
    return srcIsdAs;
  }

  public InetSocketAddress getFirstHopAddress() throws UnknownHostException {
    return firstHopAddress != null ? firstHopAddress : getFirstHopAddress(protoPath());
  }

  private InetSocketAddress getFirstHopAddress(Daemon.Path internalPath)
      throws UnknownHostException {
    String underlayAddressString = internalPath.getInterface().getAddress().getAddress();
    int splitIndex = underlayAddressString.indexOf(':');
    InetAddress underlayAddress =
        InetAddress.getByName(underlayAddressString.substring(0, splitIndex));
    int underlayPort = Integer.parseUnsignedInt(underlayAddressString.substring(splitIndex + 1));
    return new InetSocketAddress(underlayAddress, underlayPort);
  }

  public byte[] getRawPath() {
    if (pathRaw == null) {
      ByteString bs = pathProtoc.getRaw();
      pathRaw = new byte[bs.size()];
      for (int i = 0; i < bs.size(); i++) {
        pathRaw[i] = bs.byteAt(i);
      }
    }
    return pathRaw;
  }

  private Daemon.Path protoPath() {
    if (pathProtoc == null) {
      throw new IllegalStateException(
          "Information is only available for paths that"
              + " were retrieved directly from a path server.");
    }
    return pathProtoc;
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
    LINK_TYPE_UNSPECIFIED, // = 0;
    /** Direct physical connection. */
    LINK_TYPE_DIRECT, // = 1;
    /** Connection with local routing/switching. */
    LINK_TYPE_MULTI_HOP, // = 2;
    /** Connection overlayed over publicly routed Internet. */
    LINK_TYPE_OPEN_NET, // = 3;
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
