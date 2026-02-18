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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.scion.jpan.proto.daemon.Daemon;

/**
 * PathMetadata contains the raw path and meta information such as bandwidth, latency or geo
 * coordinates. PathMetadata is available from Paths that are created/returned by the ScionService
 * when requesting a new path from the control service.
 */
public class PathMetadata {

  private final byte[] pathRaw;
  private final Interface firstInterface;
  private final List<PathInterface> pathInterfaces;
  private final int mtu;
  private final long expiration;
  private final List<Integer> latencyList;
  private final List<Long> bandwidthList;
  private final List<GeoCoordinates> geoList;
  private final List<LinkType> linkTypeList;
  private final List<Integer> internalHopList;
  private final List<String> notesList;
  private final EpicAuths epicAuths;

  public static Builder newBuilder() {
    return new Builder();
  }

  public static PathMetadata create(Daemon.Path path) {
    return new PathMetadata(path);
  }

  private PathMetadata(
      byte[] pathRaw,
      Interface firstInterface,
      List<PathInterface> pathInterfaces,
      int mtu,
      long expiration,
      List<Integer> latencyList,
      List<Long> bandwidthList,
      List<GeoCoordinates> geoList,
      List<LinkType> linkTypeList,
      List<Integer> internalHopList,
      List<String> notesList,
      EpicAuths epicAuths) {
    this.pathRaw = pathRaw;
    this.firstInterface = firstInterface;
    this.pathInterfaces = pathInterfaces;
    this.mtu = mtu;
    this.expiration = expiration;
    this.latencyList = latencyList;
    this.bandwidthList = bandwidthList;
    this.geoList = geoList;
    this.linkTypeList = linkTypeList;
    this.internalHopList = internalHopList;
    this.notesList = notesList;
    this.epicAuths = epicAuths;
  }

  private PathMetadata(Daemon.Path path) {
    this.pathRaw = path.getRaw().toByteArray();

    pathInterfaces =
        Collections.unmodifiableList(
            path.getInterfacesList().stream().map(PathInterface::new).collect(Collectors.toList()));
    firstInterface = new Interface(path.getInterface());
    mtu = path.getMtu();
    expiration = path.getExpiration().getSeconds();
    latencyList =
        Collections.unmodifiableList(
            path.getLatencyList().stream()
                .map(
                    time ->
                        (time.getSeconds() < 0 || time.getNanos() < 0)
                            ? -1
                            : (int) (time.getSeconds() * 1_000 + time.getNanos() / 1_000_000))
                .collect(Collectors.toList()));
    bandwidthList = path.getBandwidthList();
    geoList =
        Collections.unmodifiableList(
            path.getGeoList().stream().map(GeoCoordinates::new).collect(Collectors.toList()));
    linkTypeList =
        Collections.unmodifiableList(
            path.getLinkTypeList().stream()
                .map(linkType -> LinkType.values()[linkType.getNumber()])
                .collect(Collectors.toList()));
    internalHopList = path.getInternalHopsList();
    notesList = path.getNotesList();
    epicAuths = new EpicAuths(path.getEpicAuths());
  }

  public byte[] getRawPath() {
    return pathRaw;
  }

  /**
   * @return Interface for exiting the local AS using this path.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public Interface getInterface() {
    return firstInterface;
  }

  /**
   * @return The list of interfaces the path is composed of.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<PathInterface> getInterfacesList() {
    return pathInterfaces;
  }

  /**
   * @return The maximum transmission unit (MTU) on the path.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public int getMtu() {
    return mtu;
  }

  /**
   * @return The point in time when this path expires. In seconds since UNIX epoch.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public long getExpiration() {
    return expiration;
  }

  /**
   * @return Latency lists the latencies between any two consecutive interfaces. Entry i describes
   *     the latency between interface i and i+1. Consequently, there are N-1 entries for N
   *     interfaces. A 0-value indicates that the AS did not announce a latency for this hop.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<Integer> getLatencyList() {
    return latencyList;
  }

  /**
   * @return Bandwidth lists the bandwidth between any two consecutive interfaces, in Kbit/s. Entry
   *     i describes the bandwidth between interfaces i and i+1. A 0-value indicates that the AS did
   *     not announce a bandwidth for this hop.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<Long> getBandwidthList() {
    return bandwidthList;
  }

  /**
   * @return Geo lists the geographical position of the border routers along the path. Entry i
   *     describes the position of the router for interface i. A 0-value indicates that the AS did
   *     not announce a position for this router.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<GeoCoordinates> getGeoList() {
    return geoList;
  }

  /**
   * @return LinkType contains the announced link type of inter-domain links. Entry i describes the
   *     link between interfaces 2*i and 2*i+1.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<LinkType> getLinkTypeList() {
    return linkTypeList;
  }

  /**
   * @return InternalHops lists the number of AS internal hops for the ASes on path. Entry i
   *     describes the hop between interfaces 2*i+1 and 2*i+2 in the same AS. Consequently, there
   *     are no entries for the first and last ASes, as these are not traversed completely by the
   *     path.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<Integer> getInternalHopsList() {
    return internalHopList;
  }

  /**
   * @return Notes contains the notes added by ASes on the path, in the order of occurrence. Entry i
   *     is the note of AS i on the path.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public List<String> getNotesList() {
    return notesList;
  }

  /**
   * @return EpicAuths contains the EPIC authenticators used to calculate the PHVF and LHVF.
   * @throws IllegalStateException if this is path is only a raw path
   */
  public EpicAuths getEpicAuths() {
    return epicAuths;
  }

  public enum LinkType {
    /** Unspecified link type. */
    UNSPECIFIED, // = 0
    /** Direct physical connection. */
    DIRECT, // = 1
    /** Connection with local routing/switching. */
    MULTI_HOP, // = 2
    /** Connection overlayed over publicly routed Internet. */
    OPEN_NET, // = 3
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

  /** Address of the first hop, e.g., 192.0.2.1:10000 or [2001:db8::1]:10000 . */
  public static class Interface {
    private final String address;

    public static Interface create(String address) {
      return new Interface(address);
    }

    private Interface(String address) {
      this.address = address;
    }

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

    public static PathInterface create(long isdAs, long id) {
      return new PathInterface(isdAs, id);
    }

    private PathInterface(long isdAs, long id) {
      this.isdAs = isdAs;
      this.id = id;
    }

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

    public static GeoCoordinates create(float latitude, float longitude, String address) {
      return new GeoCoordinates(latitude, longitude, address);
    }

    private GeoCoordinates(float latitude, float longitude, String address) {
      this.latitude = latitude;
      this.longitude = longitude;
      this.address = address;
    }

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

  public static class Builder {
    private byte[] pathRaw = {};
    private Interface localInterface;
    private List<PathInterface> pathInterfaces = new ArrayList<>();
    private int mtu;
    private long expiration;
    private List<Integer> latencyList = new ArrayList<>();
    private List<Long> bandwidthList = new ArrayList<>();
    private List<GeoCoordinates> geoList = new ArrayList<>();
    private List<LinkType> linkTypeList = new ArrayList<>();
    private List<Integer> internalHopList = new ArrayList<>();
    private List<String> notesList = new ArrayList<>();
    private EpicAuths epicAuths;

    public Builder from(PathMetadata other) {
      pathRaw = other.pathRaw;
      localInterface = other.firstInterface;
      pathInterfaces = other.pathInterfaces;
      mtu = other.mtu;
      expiration = other.expiration;
      latencyList = other.latencyList;
      bandwidthList = other.bandwidthList;
      geoList = other.geoList;
      linkTypeList = other.linkTypeList;
      internalHopList = other.internalHopList;
      notesList = other.notesList;
      epicAuths = other.epicAuths;
      return this;
    }

    public long getExpiration() {
      return expiration;
    }

    public Builder setExpiration(long expiration) {
      this.expiration = expiration;
      return this;
    }

    public Builder setLocalInterface(Interface interfaceAddr) {
      this.localInterface = interfaceAddr;
      return this;
    }

    public List<PathInterface> getInterfaces() {
      return pathInterfaces;
    }

    public int getMtu() {
      return mtu;
    }

    public Builder setMtu(int mtu) {
      this.mtu = mtu;
      return this;
    }

    public byte[] getRaw() {
      return pathRaw;
    }

    public Builder setRaw(byte[] raw) {
      this.pathRaw = raw;
      return this;
    }

    public Builder setRaw(ByteBuffer rawBB) {
      pathRaw = new byte[rawBB.remaining()];
      rawBB.get(pathRaw);
      return this;
    }

    public Builder addLatency(int latencyMilliSeconds) {
      latencyList.add(latencyMilliSeconds);
      return this;
    }

    public Builder addBandwidth(long bandwidth) {
      bandwidthList.add(bandwidth);
      return this;
    }

    public Builder addGeo(GeoCoordinates geo) {
      geoList.add(geo);
      return this;
    }

    public Builder addLinkType(LinkType linkType) {
      linkTypeList.add(linkType);
      return this;
    }

    public Builder addInternalHops(int i) {
      internalHopList.add(i);
      return this;
    }

    public Builder addNotes(String note) {
      notesList.add(note);
      return this;
    }

    public Builder addInterfaces(PathInterface pathInterface) {
      pathInterfaces.add(pathInterface);
      return this;
    }

    public PathMetadata build() {
      return new PathMetadata(
          pathRaw,
          localInterface,
          pathInterfaces,
          mtu,
          expiration,
          latencyList,
          bandwidthList,
          geoList,
          linkTypeList,
          internalHopList,
          notesList,
          epicAuths);
    }
  }
}
