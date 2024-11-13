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

import static org.scion.jpan.Constants.DEFAULT_DAEMON;
import static org.scion.jpan.Constants.DEFAULT_DAEMON_PORT;
import static org.scion.jpan.Constants.DEFAULT_USE_OS_SEARCH_DOMAINS;
import static org.scion.jpan.Constants.ENV_BOOTSTRAP_HOST;
import static org.scion.jpan.Constants.ENV_BOOTSTRAP_NAPTR_NAME;
import static org.scion.jpan.Constants.ENV_BOOTSTRAP_TOPO_FILE;
import static org.scion.jpan.Constants.ENV_DAEMON;
import static org.scion.jpan.Constants.ENV_DNS_SEARCH_DOMAINS;
import static org.scion.jpan.Constants.ENV_USE_OS_SEARCH_DOMAINS;
import static org.scion.jpan.Constants.PROPERTY_BOOTSTRAP_HOST;
import static org.scion.jpan.Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME;
import static org.scion.jpan.Constants.PROPERTY_BOOTSTRAP_TOPO_FILE;
import static org.scion.jpan.Constants.PROPERTY_DAEMON;
import static org.scion.jpan.Constants.PROPERTY_DNS_SEARCH_DOMAINS;
import static org.scion.jpan.Constants.PROPERTY_USE_OS_SEARCH_DOMAINS;

import com.google.protobuf.Empty;
import io.grpc.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.scion.jpan.internal.*;
import org.scion.jpan.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ScionService provides information such as: <br>
 * - Paths from A to B - The local ISD/AS numbers <br>
 * - Lookup op ISD/AS for host names via DNS. <br>
 *
 * <p>The ScionService is intended as singleton. There should usually be only one instance that is
 * shared by all users. However, it may sometimes be desirable to have multiple instances, e.g. for
 * connecting to a different daemon or for better concurrency.
 *
 * <p>The default instance is of type ScionService. All other ScionService are of type {@code
 * Scion.CloseableService} which extends {@code AutoCloseable}.
 *
 * @see Scion.CloseableService
 */
public class ScionService {

  private static final Logger LOG = LoggerFactory.getLogger(ScionService.class.getName());

  private static final String DNS_TXT_KEY = "scion";
  private static final Object LOCK = new Object();
  private static final String ERR_INVALID_TXT = "Invalid TXT entry: ";
  private static final String ERR_INVALID_TXT_LOG = ERR_INVALID_TXT + "{}";
  private static final String ERR_INVALID_TXT_LOG2 = ERR_INVALID_TXT + "{} {}";
  private static ScionService defaultService = null;

  private final ScionBootstrapper bootstrapper;
  private final DaemonServiceGrpc.DaemonServiceBlockingStub daemonStub;
  private final SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub;
  private LocalTopology.DispatcherPortRange portRange;

  private final boolean minimizeRequests;
  private final ManagedChannel channel;
  private static final long ISD_AS_NOT_SET = -1;
  private final AtomicLong localIsdAs = new AtomicLong(ISD_AS_NOT_SET);
  private Thread shutdownHook;
  private java.nio.channels.DatagramChannel ifDiscoveryChannel = null;
  private final Map<InetAddress, InetAddress> ifDiscoveryMap = new HashMap<>();
  private final HostsFileParser hostsFile = new HostsFileParser();
  private final SimpleCache<String, ScionAddress> scionAddressCache = new SimpleCache<>(100);

  protected enum Mode {
    DAEMON,
    BOOTSTRAP_SERVER_IP,
    BOOTSTRAP_VIA_DNS,
    BOOTSTRAP_TOPO_FILE
  }

  protected ScionService(String addressOrHost, Mode mode) {
    minimizeRequests =
        ScionUtil.getPropertyOrEnv(
            Constants.PROPERTY_RESOLVER_MINIMIZE_REQUESTS,
            Constants.ENV_RESOLVER_MINIMIZE_REQUESTS,
            Constants.DEFAULT_RESOLVER_MINIMIZE_REQUESTS);
    if (mode == Mode.DAEMON) {
      addressOrHost = IPHelper.ensurePortOrDefault(addressOrHost, DEFAULT_DAEMON_PORT);
      LOG.info("Bootstrapping with daemon: target={}", addressOrHost);
      channel = Grpc.newChannelBuilder(addressOrHost, InsecureChannelCredentials.create()).build();
      daemonStub = DaemonServiceGrpc.newBlockingStub(channel);
      segmentStub = null;
      bootstrapper = null;
    } else {
      LOG.info("Bootstrapping with control service: mode={} target={}", mode.name(), addressOrHost);
      if (mode == Mode.BOOTSTRAP_VIA_DNS) {
        bootstrapper = ScionBootstrapper.createViaDns(addressOrHost);
      } else if (mode == Mode.BOOTSTRAP_SERVER_IP) {
        bootstrapper = ScionBootstrapper.createViaBootstrapServerIP(addressOrHost);
      } else if (mode == Mode.BOOTSTRAP_TOPO_FILE) {
        java.nio.file.Path file = Paths.get(addressOrHost);
        bootstrapper = ScionBootstrapper.createViaTopoFile(file);
      } else {
        throw new UnsupportedOperationException();
      }
      String csHost = bootstrapper.getLocalTopology().getControlServerAddress();
      LOG.info("Bootstrapping with control service: {}", csHost);
      localIsdAs.set(bootstrapper.getLocalTopology().getIsdAs());
      // TODO InsecureChannelCredentials: Implement authentication!
      channel = Grpc.newChannelBuilder(csHost, InsecureChannelCredentials.create()).build();
      daemonStub = null;
      segmentStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
    }
    shutdownHook = addShutdownHook();
    Shim.install(this);
    try {
      getLocalIsdAs(); // Init
    } catch (RuntimeException e) {
      // If this fails for whatever reason we want to make sure that the channel is closed.
      try {
        close();
      } catch (IOException ex) {
        // Ignore, we just want to get out.
      }
      throw e;
    }
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * @return default instance
   */
  static ScionService defaultService() {
    synchronized (LOCK) {
      // This is not 100% thread safe, but the worst that can happen is that
      // we call close() on a Service that has already been closed.
      if (defaultService != null) {
        return defaultService;
      }
      // try bootstrap service IP
      String fileName =
          ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_TOPO_FILE, ENV_BOOTSTRAP_TOPO_FILE);
      if (fileName != null) {
        defaultService = new ScionService(fileName, Mode.BOOTSTRAP_TOPO_FILE);
        return defaultService;
      }

      String server = ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_HOST, ENV_BOOTSTRAP_HOST);
      if (server != null) {
        defaultService = new ScionService(server, Mode.BOOTSTRAP_SERVER_IP);
        return defaultService;
      }

      String naptrName =
          ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_NAPTR_NAME, ENV_BOOTSTRAP_NAPTR_NAME);
      if (naptrName != null) {
        defaultService = new ScionService(naptrName, Mode.BOOTSTRAP_VIA_DNS);
        return defaultService;
      }

      // try daemon
      String daemon = ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON, ENV_DAEMON, DEFAULT_DAEMON);
      try {
        defaultService = new ScionService(daemon, Mode.DAEMON);
        return defaultService;
      } catch (ScionRuntimeException e) {
        LOG.info(e.getMessage());
        if (ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON, ENV_DAEMON) != null) {
          throw e;
        }
      }

      // try normal network
      String searchDomain =
          ScionUtil.getPropertyOrEnv(PROPERTY_DNS_SEARCH_DOMAINS, ENV_DNS_SEARCH_DOMAINS);
      if (ScionUtil.getPropertyOrEnv(
              PROPERTY_USE_OS_SEARCH_DOMAINS,
              ENV_USE_OS_SEARCH_DOMAINS,
              DEFAULT_USE_OS_SEARCH_DOMAINS)
          || searchDomain != null) {
        String dnsResolver = DNSHelper.searchForDiscoveryService();
        if (dnsResolver != null) {
          defaultService = new ScionService(dnsResolver, Mode.BOOTSTRAP_SERVER_IP);
          return defaultService;
        }
        LOG.info("No DNS record found for bootstrap server.");
        throw new ScionRuntimeException(
            "No DNS record found for bootstrap server. This means "
                + "the DNS server may not have NAPTR records for the bootstrap server or your host "
                + "may not have the search domains configured in /etc/resolv.conf or similar.");
      }
      throw new ScionRuntimeException("Could not connect to daemon, DNS or bootstrap resource.");
    }
  }

  public static void closeDefault() {
    synchronized (LOCK) {
      if (defaultService != null) {
        try {
          defaultService.close();
        } catch (IOException e) {
          throw new ScionRuntimeException(e);
        } finally {
          defaultService = null;
        }
      }
    }
  }

  private Thread addShutdownHook() {
    Thread hook =
        new Thread(
            () -> {
              try {
                if (defaultService != null) {
                  defaultService.shutdownHook = null;
                  defaultService.close();
                }
              } catch (IOException e) {
                // Ignore, we just want to get out.
              }
            });
    Runtime.getRuntime().addShutdownHook(hook);
    return hook;
  }

  public void close() throws IOException {
    try {
      if (!channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)) {
        if (!channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)) {
          LOG.error("Failed to shut down ScionService gRPC ManagedChannel");
        }
      }
      synchronized (ifDiscoveryMap) {
        try {
          if (ifDiscoveryChannel != null) {
            ifDiscoveryChannel.close();
          }
          ifDiscoveryChannel = null;
          ifDiscoveryMap.clear();
        } catch (IOException e) {
          throw new ScionRuntimeException(e);
        }
      }
      if (shutdownHook != null) {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  public ScionDatagramChannel openChannel() throws IOException {
    return ScionDatagramChannel.open(this);
  }

  public ScionDatagramChannel openChannel(java.nio.channels.DatagramChannel channel)
      throws IOException {
    return ScionDatagramChannel.open(this, channel);
  }

  Daemon.ASResponse getASInfo() {
    Daemon.ASRequest request = Daemon.ASRequest.newBuilder().setIsdAs(0).build();
    Daemon.ASResponse response;
    try {
      response = daemonStub.aS(request);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        throw new ScionRuntimeException("Could not connect to SCION daemon: " + e.getMessage(), e);
      }
      throw new ScionRuntimeException("Error while getting AS info: " + e.getMessage(), e);
    }
    return response;
  }

  Map<Long, Daemon.Interface> getInterfaces() {
    Daemon.InterfacesRequest request = Daemon.InterfacesRequest.newBuilder().build();
    Daemon.InterfacesResponse response;
    try {
      response = daemonStub.interfaces(request);
    } catch (StatusRuntimeException e) {
      throw new ScionRuntimeException(e);
    }
    return response.getInterfacesMap();
  }

  private List<Daemon.Path> getPathList(long srcIsdAs, long dstIsdAs) {
    if (daemonStub != null) {
      return getPathListDaemon(srcIsdAs, dstIsdAs);
    }
    return getPathListCS(srcIsdAs, dstIsdAs);
  }

  // do not expose proto types on API
  List<Daemon.Path> getPathListDaemon(long srcIsdAs, long dstIsdAs) {
    Daemon.PathsRequest request =
        Daemon.PathsRequest.newBuilder()
            .setSourceIsdAs(srcIsdAs)
            .setDestinationIsdAs(dstIsdAs)
            .build();

    Daemon.PathsResponse response;
    try {
      response = daemonStub.paths(request);
    } catch (StatusRuntimeException e) {
      throw new ScionRuntimeException(e);
    }

    return response.getPathsList();
  }

  /**
   * Requests paths from the local ISD/AS to the destination.
   *
   * @param dstAddress Destination IP address. It will try to perform a DNS look up to map the
   *     hostName to SCION address.
   * @return All paths returned by the path service.
   * @throws IOException if an errors occurs while querying paths.
   * @deprecated Please use lookup() instead
   */
  @Deprecated // Please use lookup() instead
  public List<Path> getPaths(InetSocketAddress dstAddress) throws IOException {
    // Use getHostString() to avoid DNS reverse lookup.
    ScionAddress sa = getScionAddress(dstAddress.getHostString());
    return getPaths(sa.getIsdAs(), sa.getInetAddress(), dstAddress.getPort());
  }

  /**
   * Request paths from the local ISD/AS to the destination.
   *
   * @param dstIsdAs Destination ISD/AS
   * @param dstScionAddress Destination IP address. Must belong to a SCION enabled end host.
   * @return All paths returned by the path service.
   */
  public List<Path> getPaths(long dstIsdAs, InetSocketAddress dstScionAddress) {
    if (dstScionAddress instanceof ScionSocketAddress) {
      return getPaths(((ScionSocketAddress) dstScionAddress).getPath());
    }
    return getPaths(dstIsdAs, dstScionAddress.getAddress(), dstScionAddress.getPort());
  }

  /**
   * Request paths to the same destination as the provided path.
   *
   * @param path A path
   * @return All paths returned by the path service.
   */
  public List<Path> getPaths(Path path) {
    return getPaths(path.getRemoteIsdAs(), path.getRemoteAddress(), path.getRemotePort());
  }

  /**
   * Request paths from the local ISD/AS to the destination.
   *
   * @param dstIsdAs Destination ISD/AS
   * @param dstAddress A SCION-enabled Destination IP address
   * @param dstPort Destination port
   * @return All paths returned by the path service. Returns an empty list if no paths are found.
   */
  public List<Path> getPaths(long dstIsdAs, InetAddress dstAddress, int dstPort) {
    return getPaths(ScionAddress.create(dstIsdAs, dstAddress), dstPort);
  }

  /**
   * Resolves the address to a SCION address, request paths, and selects a path using the policy.
   *
   * @param hostName Destination host name
   * @param port Destination port
   * @param policy Path policy. 'null' means PathPolicy.DEFAULT.
   * @return All paths returned by the path service.
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public Path lookupAndGetPath(String hostName, int port, PathPolicy policy) throws ScionException {
    if (policy == null) {
      policy = PathPolicy.DEFAULT;
    }
    return policy.filter(getPaths(lookupAddress(hostName), port));
  }

  /**
   * Resolves the address to a SCION address, request paths, and selects a path using the policy.
   *
   * @param dstAddr Destination address
   * @param policy Path policy. 'null' means PathPolicy.DEFAULT.
   * @return All paths returned by the path service.
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public Path lookupAndGetPath(InetSocketAddress dstAddr, PathPolicy policy) throws ScionException {
    if (policy == null) {
      policy = PathPolicy.DEFAULT;
    }
    return policy.filter(getPaths(lookupAddress(dstAddr.getHostString()), dstAddr.getPort()));
  }

  /**
   * Request paths from the local ISD/AS to the destination.
   *
   * @param dstAddress Destination SCION address
   * @return All paths returned by the path service.
   * @deprecated Do not use - will be removed (or made private) in 0.4.0
   */
  @Deprecated
  public List<Path> getPaths(ScionAddress dstAddress, int dstPort) {
    long srcIsdAs = getLocalIsdAs();
    List<Daemon.Path> paths = getPathList(srcIsdAs, dstAddress.getIsdAs());
    List<Path> scionPaths = new ArrayList<>(paths.size());
    for (int i = 0; i < paths.size(); i++) {
      scionPaths.add(
          RequestPath.create(
              paths.get(i), dstAddress.getIsdAs(), dstAddress.getInetAddress(), dstPort));
    }
    return scionPaths;
  }

  Map<String, Daemon.ListService> getServices() throws ScionException {
    Daemon.ServicesRequest request = Daemon.ServicesRequest.newBuilder().build();
    Daemon.ServicesResponse response;
    try {
      response = daemonStub.services(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }
    return response.getServicesMap();
  }

  public long getLocalIsdAs() {
    if (localIsdAs.get() == ISD_AS_NOT_SET) {
      // Yes, this may be called multiple time by different threads, but it should be
      // faster than `synchronize`.
      localIsdAs.set(getASInfo().getIsdAs());
    }
    return localIsdAs.get();
  }

  /**
   * @param hostName hostName of the host to resolve
   * @return The ISD/AS code for a hostname
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public long getIsdAs(String hostName) throws ScionException {
    ScionAddress scionAddress = scionAddressCache.get(hostName);
    if (scionAddress != null) {
      return scionAddress.getIsdAs();
    }

    // Look for TXT in application properties
    String txtFromProperties = findTxtRecordInProperties(hostName);
    if (txtFromProperties != null) {
      Long result = parseTxtRecordToIA(txtFromProperties);
      if (result != null) {
        return result;
      }
      throw new ScionException(ERR_INVALID_TXT + txtFromProperties);
    }

    // Check /etc/scion/hosts
    HostsFileParser.HostEntry entry = hostsFile.find(hostName);
    if (entry != null) {
      return entry.getIsdAs();
    }

    // Use local ISD/AS for localhost addresses
    if (IPHelper.isLocalhost(hostName)) {
      return getLocalIsdAs();
    }

    // DNS lookup
    Long fromDNS = DNSHelper.queryTXT(hostName, DNS_TXT_KEY, this::parseTxtRecordToIA);
    if (fromDNS != null) {
      return fromDNS;
    }

    throw new ScionException("No DNS TXT entry \"scion\" found for host: " + hostName);
  }

  @Deprecated // Please use lookupScionAddress() instead.
  public ScionAddress getScionAddress(String hostName) throws ScionException {
    return lookupAddress(hostName);
  }

  /**
   * Uses DNS and hostfiles to look up a SCION enabled IP address for a give host string.
   *
   * @param hostName hostName of the host to resolve
   * @return A ScionAddress
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  private ScionAddress lookupAddress(String hostName) throws ScionException {
    ScionAddress scionAddress = scionAddressCache.get(hostName);
    if (scionAddress != null) {
      return scionAddress;
    }

    // Look for TXT in application properties
    String txtFromProperties = findTxtRecordInProperties(hostName);
    if (txtFromProperties != null) {
      ScionAddress address = parseTxtRecord(txtFromProperties, hostName);
      if (address == null) {
        throw new ScionException(ERR_INVALID_TXT + txtFromProperties);
      }
      return address;
    }

    // Check /etc/scion/hosts
    HostsFileParser.HostEntry entry = hostsFile.find(hostName);
    if (entry != null) {
      return ScionAddress.create(entry.getIsdAs(), entry.getAddress());
    }

    // Use local ISD/AS for localhost addresses
    byte[] localBytes = IPHelper.lookupLocalhost(hostName);
    if (localBytes != null) {
      return ScionAddress.create(getLocalIsdAs(), hostName, localBytes);
    }

    // DNS lookup
    ScionAddress fromDNS =
        DNSHelper.queryTXT(hostName, DNS_TXT_KEY, x -> parseTxtRecord(x, hostName));
    if (fromDNS != null) {
      return addToCache(fromDNS);
    }

    throw new ScionException("No DNS TXT entry \"scion\" found for host: " + hostName);
  }

  private ScionAddress addToCache(ScionAddress address) {
    scionAddressCache.put(address.getHostName(), address);
    scionAddressCache.put(address.getInetAddress().getHostAddress(), address);
    return address;
  }

  private String findTxtRecordInProperties(String hostName) throws ScionException {
    String props = System.getProperty(Constants.DEBUG_PROPERTY_MOCK_DNS_TXT);
    if (props == null) {
      return null;
    }
    int posHost = props.indexOf(hostName);
    char nextChar = props.charAt(posHost + hostName.length());
    char prevChar = posHost <= 0 ? ';' : props.charAt(posHost - 1);
    if (posHost >= 0
        && (nextChar == '=' || nextChar == '"')
        && (prevChar == ';' || prevChar == ',')) {
      int posStart;
      int posEnd;
      if (prevChar == ',') {
        // This is an IP match, not a host match
        posStart = props.substring(0, posHost).lastIndexOf("=\"");
        posEnd = props.indexOf(';', posHost);
      } else {
        // normal case: hostname match
        posStart = props.indexOf('=', posHost + 1);
        posEnd = props.indexOf(';', posStart + 1);
      }

      String txtRecord;
      if (posEnd > 0) {
        txtRecord = props.substring(posStart + 1, posEnd);
      } else {
        txtRecord = props.substring(posStart + 1);
      }
      if (!txtRecord.startsWith("\"" + DNS_TXT_KEY + "=") || !txtRecord.endsWith("\"")) {
        throw new ScionException(ERR_INVALID_TXT + txtRecord);
      }
      // No more checking here, we assume that properties are save
      return txtRecord.substring(DNS_TXT_KEY.length() + 2, txtRecord.length() - 1);
    }
    return null;
  }

  private ScionAddress parseTxtRecord(String txtEntry, String hostName) {
    // dnsEntry example: "scion=64-2:0:9,129.x.x.x"
    int posComma = txtEntry.indexOf(',');
    if (posComma < 0) {
      LOG.info(ERR_INVALID_TXT_LOG, txtEntry);
      return null;
    }
    try {
      long isdAs = ScionUtil.parseIA(txtEntry.substring(0, posComma));
      byte[] bytes = IPHelper.toByteArray(txtEntry.substring(posComma + 1));
      return ScionAddress.create(isdAs, hostName, bytes);
    } catch (IllegalArgumentException e) {
      LOG.info(ERR_INVALID_TXT_LOG2, txtEntry, e.getMessage());
      return null;
    }
  }

  private Long parseTxtRecordToIA(String txtEntry) {
    // dnsEntry example: "scion=64-2:0:9,129.x.x.x"
    int posComma = txtEntry.indexOf(',');
    if (posComma < 0) {
      LOG.info(ERR_INVALID_TXT_LOG, txtEntry);
      return null;
    }
    return ScionUtil.parseIA(txtEntry.substring(0, posComma));
  }

  // Do not expose protobuf types on API!
  List<Daemon.Path> getPathListCS(long srcIsdAs, long dstIsdAs) {
    List<Daemon.Path> list =
        Segments.getPaths(segmentStub, bootstrapper, srcIsdAs, dstIsdAs, minimizeRequests);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Path found between {} and {}: {}",
          ScionUtil.toStringIA(srcIsdAs),
          ScionUtil.toStringIA(dstIsdAs),
          list.size());
    }
    return list;
  }

  /**
   * Determine the network interface and external IP used for connecting to the specified address.
   *
   * @param firstHopAddress Reachable address.
   */
  InetAddress getExternalIP(InetSocketAddress firstHopAddress) {
    // We currently keep a map with BR->externalIP. This may be overkill, probably all BR in
    // a given AS are reachable via the same interface.
    // TODO
    // Moreover, it DOES NOT WORK with multiple AS, because BR IPs are not unique across ASes.
    // However, switching ASes is not currently implemented...
    synchronized (ifDiscoveryMap) {
      return ifDiscoveryMap.computeIfAbsent(
          firstHopAddress.getAddress(),
          firstHop -> {
            try {
              if (ifDiscoveryChannel == null) {
                ifDiscoveryChannel = java.nio.channels.DatagramChannel.open();
              }
              ifDiscoveryChannel.connect(firstHopAddress);
              SocketAddress address = ifDiscoveryChannel.getLocalAddress();
              ifDiscoveryChannel.disconnect();
              return ((InetSocketAddress) address).getAddress();
            } catch (IOException e) {
              throw new ScionRuntimeException(e);
            }
          });
    }
  }

  LocalTopology.DispatcherPortRange getLocalPortRange() {
    if (portRange == null) {
      if (bootstrapper != null) {
        portRange = bootstrapper.getLocalTopology().getPortRange();
      } else if (daemonStub != null) {
        // try daemon
        Daemon.PortRangeResponse response;
        try {
          response = daemonStub.portRange(Empty.getDefaultInstance());
          portRange =
              LocalTopology.DispatcherPortRange.create(
                  response.getDispatchedPortStart(), response.getDispatchedPortEnd());
        } catch (StatusRuntimeException e) {
          LOG.warn("ERROR getting port range from daemon: {}", e.getMessage());
          // Daemon doesn't support port range.
          portRange = LocalTopology.DispatcherPortRange.createEmpty();
        }
      } else {
        portRange = LocalTopology.DispatcherPortRange.createAll();
      }
    }
    return portRange;
  }

  InetSocketAddress getBorderRouterAddress(int interfaceID) {
    if (daemonStub != null) {
      final String MSG = "No border router found for interfaceID: ";
      String address =
          getInterfaces().entrySet().stream()
              .filter(entry -> entry.getKey() == interfaceID)
              .findAny()
              .orElseThrow(() -> new ScionRuntimeException(MSG + interfaceID))
              .getValue()
              .getAddress()
              .getAddress();
      return IPHelper.toInetSocketAddress(address);
    } else {
      String address = bootstrapper.getLocalTopology().getBorderRouterAddress(interfaceID);
      return IPHelper.toInetSocketAddress(address);
    }
  }
}
