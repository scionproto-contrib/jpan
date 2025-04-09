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

import static org.scion.jpan.Constants.*;

import io.grpc.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import org.scion.jpan.internal.*;
import org.scion.jpan.proto.daemon.Daemon;
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
  private final ControlServiceGrpc controlService;
  private final DaemonServiceGrpc daemonService;

  private final boolean minimizeRequests;
  private Thread shutdownHook;
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
      daemonService = DaemonServiceGrpc.create(addressOrHost);
      controlService = null;
      try {
        bootstrapper = ScionBootstrapper.createViaDaemon(daemonService);
      } catch (RuntimeException e) {
        // If this fails for whatever reason we want to make sure that the channel is closed.
        close();
        throw new ScionRuntimeException("Could not connect to daemon at: " + addressOrHost, e);
      }
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
      daemonService = null;
      controlService = ControlServiceGrpc.create(bootstrapper.getLocalTopology());
    }
    shutdownHook = addShutdownHook();
    try {
      checkStartShim();
    } catch (RuntimeException e) {
      // If this fails for whatever reason we want to make sure that the channel is closed.
      try {
        close();
      } catch (Exception ex) {
        // Ignore, we just want to get out.
      }
      throw e;
    }
  }

  private void checkStartShim() {
    // Start SHIM unless we have port range 'ALL'. However, config overrides this setting.
    String config = ScionUtil.getPropertyOrEnv(Constants.PROPERTY_SHIM, Constants.ENV_SHIM);
    boolean hasAllPorts = getLocalPortRange().hasPortRangeALL();
    boolean start = config != null ? Boolean.parseBoolean(config) : !hasAllPorts;
    if (start) {
      Shim.install();
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
        defaultService.close();
        defaultService = null;
      }
    }
  }

  private Thread addShutdownHook() {
    Thread hook =
        new Thread(
            () -> {
              if (defaultService != null) {
                defaultService.shutdownHook = null;
                defaultService.close();
              }
            });
    Runtime.getRuntime().addShutdownHook(hook);
    return hook;
  }

  public void close() {
    if (daemonService != null) {
      daemonService.close();
    }
    if (controlService != null) {
      controlService.close();
    }
    if (shutdownHook != null) {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }
  }

  public ScionDatagramChannel openChannel() throws IOException {
    return ScionDatagramChannel.open(this);
  }

  public ScionDatagramChannel openChannel(java.nio.channels.DatagramChannel channel)
      throws IOException {
    return ScionDatagramChannel.open(this, channel);
  }

  private List<Daemon.Path> getPathList(long srcIsdAs, long dstIsdAs) {
    if (daemonService != null) {
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
      response = daemonService.paths(request);
    } catch (StatusRuntimeException e) {
      throw new ScionRuntimeException(e);
    }

    return response.getPathsList();
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
   * @deprecated Use {@link #lookupPaths(String, int)} instead.
   */
  @Deprecated // TODO remove for 0.6.0
  public Path lookupAndGetPath(String hostName, int port, PathPolicy policy) throws ScionException {
    if (policy == null) {
      policy = PathPolicy.DEFAULT;
    }
    List<Path> paths = policy.filter(lookupPaths(hostName, port));
    return paths.isEmpty() ? null : paths.get(0);
  }

  /**
   * Resolves the address to a SCION address, request paths, and selects a path using the policy.
   *
   * @param dstAddr Destination address
   * @param policy Path policy. 'null' means PathPolicy.DEFAULT.
   * @return All paths returned by the path service.
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   * @deprecated Use {@link #lookupPaths(InetSocketAddress)} instead.
   */
  @Deprecated // TODO remove for 0.6.0
  public Path lookupAndGetPath(InetSocketAddress dstAddr, PathPolicy policy) throws ScionException {
    if (policy == null) {
      policy = PathPolicy.DEFAULT;
    }
    List<Path> paths = policy.filter(lookupPaths(dstAddr));
    return paths.isEmpty() ? null : paths.get(0);
  }

  /**
   * Resolves the address to a SCION address, request paths, and selects a path using the policy.
   *
   * @param hostName Destination host name
   * @param port Destination port
   * @return All paths returned by the path service.
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public List<Path> lookupPaths(String hostName, int port) throws ScionException {
    return getPaths(lookupAddress(hostName), port);
  }

  /**
   * Resolves the address to a SCION address, request paths, and selects a path using the policy.
   *
   * @param dstAddr Destination address
   * @return All paths returned by the path service.
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public List<Path> lookupPaths(InetSocketAddress dstAddr) throws ScionException {
    return getPaths(lookupAddress(dstAddr.getHostString()), dstAddr.getPort());
  }

  /**
   * Request paths from the local ISD/AS to the destination.
   *
   * @param dstAddress Destination SCION address
   * @return All paths returned by the path service.
   */
  private List<Path> getPaths(ScionAddress dstAddress, int dstPort) {
    long srcIsdAs = getLocalIsdAs();
    List<Daemon.Path> paths = getPathList(srcIsdAs, dstAddress.getIsdAs());
    List<Path> scionPaths = new ArrayList<>(paths.size());
    for (Daemon.Path path : paths) {
      scionPaths.add(
          RequestPath.create(path, dstAddress.getIsdAs(), dstAddress.getInetAddress(), dstPort));
    }
    return scionPaths;
  }

  public long getLocalIsdAs() {
    return bootstrapper.getLocalTopology().getIsdAs();
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
        Segments.getPaths(controlService, bootstrapper, srcIsdAs, dstIsdAs, minimizeRequests);
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
   * Determine the IPs that should be used as SRC address in a SCION header. These may differ from
   * the external IP in case we are behind a NAT. The source address should be the NAT mapped
   * address.
   *
   * @param channel channel
   * @return Mapping of external addresses, potentially one for each border router.
   */
  NatMapping getNatMapping(DatagramChannel channel) {
    List<InetSocketAddress> interfaces =
        bootstrapper.getLocalTopology().getBorderRouters().stream()
            .map(LocalTopology.BorderRouter::getInternalAddress)
            .collect(Collectors.toList());
    return NatMapping.createMapping(getLocalIsdAs(), channel, interfaces);
  }

  LocalTopology.DispatcherPortRange getLocalPortRange() {
    return bootstrapper.getLocalTopology().getPortRange();
  }

  InetSocketAddress getBorderRouterAddress(int interfaceID) {
    return bootstrapper.getLocalTopology().getBorderRouterAddress(interfaceID);
  }

  ControlServiceGrpc getControlServiceConnection() {
    return controlService;
  }

  DaemonServiceGrpc getDaemonConnection() {
    return daemonService;
  }
}
