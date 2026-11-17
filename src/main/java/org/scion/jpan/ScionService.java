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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.*;
import java.util.stream.Collectors;
import org.scion.jpan.internal.*;
import org.scion.jpan.internal.bootstrap.DNSHelper;
import org.scion.jpan.internal.bootstrap.EndhostApiDiscoveryClient;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.bootstrap.ScionBootstrapper;
import org.scion.jpan.internal.paths.*;
import org.scion.jpan.internal.snap.*;
import org.scion.jpan.internal.util.Config;
import org.scion.jpan.internal.util.IPHelper;
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

  private static final Object LOCK = new Object();
  private static ScionService defaultService = null;

  private final LocalAS localAS;
  private final ControlServiceGrpc controlService;
  private final PathServiceRpc pathService;
  private final DaemonServiceGrpc daemonService;
  private final SnapDataplaneDetails snapDataplaneDetails;

  private final Thread shutdownHook;

  protected enum Mode {
    DAEMON,
    BOOTSTRAP_SERVER_IP,
    BOOTSTRAP_VIA_DNS,
    BOOTSTRAP_TOPO_FILE,
    BOOTSTRAP_PATH_SERVICE,
    BOOTSTRAP_SNAP
  }

  protected ScionService(Builder<?> builder) {
    this.localAS = builder.localAS;
    this.controlService = builder.controlService;
    this.pathService = builder.pathService;
    this.daemonService = builder.daemonService;
    this.snapDataplaneDetails = builder.snapDataplaneDetails;
    this.shutdownHook = addShutdownHook();
  }

  static <T extends ScionService> T create(String addressOrHost, Mode mode, Builder<T> builder) {
    final LocalAS localAS;

    switch (mode) {
      case DAEMON:
        LOG.info("Bootstrapping with daemon service: {}", addressOrHost);
        addressOrHost = IPHelper.ensurePortOrDefault(addressOrHost, DEFAULT_DAEMON_PORT);
        DaemonServiceGrpc daemonService = DaemonServiceGrpc.create(addressOrHost);
        try {
          localAS = checkStartShim(ScionBootstrapper.fromDaemon(daemonService));
        } catch (RuntimeException e) {
          // If this fails for whatever reason we want to make sure that the channel is closed.
          daemonService.close();
          throw new ScionRuntimeException("Could not connect to daemon at: " + addressOrHost, e);
        }
        return builder.localAs(localAS).daemonService(daemonService).build();
      case BOOTSTRAP_PATH_SERVICE:
      case BOOTSTRAP_SNAP:
        LOG.info("Bootstrapping with path service: {}", addressOrHost);
        String auth = builder.snapApiToken;
        localAS = checkStartShim(ScionBootstrapper.fromPathService(addressOrHost, auth));
        PathServiceRpc pathService = PathServiceRpc.create(localAS);
        SnapDataplaneDetails snapDataplaneDetails = initializeSnapDataPlaneIfEnabled(localAS);
        return builder
            .localAs(localAS)
            .pathService(pathService)
            .snapDetails(snapDataplaneDetails)
            .build();
      case BOOTSTRAP_VIA_DNS:
        LOG.info("Bootstrapping control service via DNS: {}", addressOrHost);
        localAS = checkStartShim(ScionBootstrapper.fromDns(addressOrHost));
        return builder.localAs(localAS).controlService(ControlServiceGrpc.create(localAS)).build();
      case BOOTSTRAP_SERVER_IP:
        LOG.info("Bootstrapping control service with IP address: {}", addressOrHost);
        localAS = checkStartShim(ScionBootstrapper.fromBootstrapServerIP(addressOrHost));
        return builder.localAs(localAS).controlService(ControlServiceGrpc.create(localAS)).build();
      case BOOTSTRAP_TOPO_FILE:
        LOG.info("Bootstrapping control service from file: {}", addressOrHost);
        localAS = checkStartShim(ScionBootstrapper.fromTopoFile(addressOrHost));
        return builder.localAs(localAS).controlService(ControlServiceGrpc.create(localAS)).build();
      default:
        throw new UnsupportedOperationException();
    }
  }

  private static LocalAS checkStartShim(LocalAS localAS) {
    // Start SHIM unless we have port range 'ALL'. However, config overrides this setting.
    String config = ScionUtil.getPropertyOrEnv(Constants.PROPERTY_SHIM, Constants.ENV_SHIM);
    boolean hasAllPorts = localAS.getPortRange().hasPortRangeALL();
    boolean start = config != null ? Boolean.parseBoolean(config) : !hasAllPorts;
    if (start) {
      Shim.install();
    }
    return localAS;
  }

  protected static void setDefaultService(ScionService newDefaultService) {
    synchronized (LOCK) {
      defaultService = newDefaultService;
    }
  }

  private static SnapDataplaneDetails initializeSnapDataPlaneIfEnabled(LocalAS localAS) {
    if (!Config.isUnderlaySnapAllowed()) {
      return null;
    }
    boolean isSnapRequired = Config.getUnderlayMode() == Config.UnderlayMode.SNAP;
    SnapControlClient snapControlClient = SnapControlClient.create(localAS);
    if (snapControlClient == null) {
      if (isSnapRequired) {
        throw new ScionRuntimeException("No SNAP control endpoint is available");
      }
      return null;
    }
    SnapDataplaneDetails dataPlane = snapControlClient.getDataPlaneAddress(isSnapRequired);
    if (dataPlane != null) {
      LOG.info(
          "SNAP mode enabled: control={} dataplane={} snap_tun_control={}",
          snapControlClient.getUrl(),
          dataPlane.getAddress(),
          dataPlane.getSnapTunControlAddress());
    }
    return dataPlane;
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * @return default instance
   */
  static ScionService defaultService() {
    return defaultService(new Builder<>());
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * <p>If no instance has been created yet, a new instance will be created with the provided
   * builder.
   *
   * @return default instance
   */
  @SuppressWarnings("unchecked")
  protected static <T extends ScionService> T defaultService(Builder<T> builder) {
    synchronized (LOCK) {
      // This is not 100% thread safe, but the worst that can happen is that
      // we call close() on a Service that has already been closed.
      if (defaultService != null) {
        return (T) defaultService;
      }
      T newService = bootstrapService(builder);
      defaultService = newService;
      return newService;
    }
  }

  /**
   * Performs service bootstrapping (detecting daemon, path service, ...),injects the detected
   * service into the builder and calls `build()`.
   *
   * <p>This method can be used to bootstrap custom subclasses of `ScionService`.
   *
   * @return new ScionService.
   */
  protected static <T extends ScionService> T bootstrapService(Builder<T> builder) {
    // try bootstrap service IP
    String fileName =
        ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_TOPO_FILE, ENV_BOOTSTRAP_TOPO_FILE);
    if (fileName != null) {
      return create(fileName, Mode.BOOTSTRAP_TOPO_FILE, builder);
    }

    // Try SNAP discovery service
    if (Config.isUnderlaySnapAllowed()) {
      String authKey = Config.getSnapAuthKey();
      String authService = Config.getSnapAuthenticationService();
      if (authService != null && authKey != null) {
        return serviceFromSnapAuth(authKey, authService, builder);
      } else if (authService != null) {
        throw new ScionRuntimeException("SNAP auth service configured but no auth key is given");
      } else if (authKey != null) {
        throw new ScionRuntimeException("SNAP auth key is configured but no auth service is given");
      }
    }

    String pathService = Config.getPathService();
    if (pathService != null) {
      return create(pathService, Mode.BOOTSTRAP_PATH_SERVICE, builder);
    }

    String server = ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_HOST, ENV_BOOTSTRAP_HOST);
    if (server != null) {
      return create(server, Mode.BOOTSTRAP_SERVER_IP, builder);
    }

    String naptrName =
        ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_NAPTR_NAME, ENV_BOOTSTRAP_NAPTR_NAME);
    if (naptrName != null) {
      return create(naptrName, Mode.BOOTSTRAP_VIA_DNS, builder);
    }

    // try daemon
    String daemon = ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON, ENV_DAEMON, DEFAULT_DAEMON);
    try {
      return create(daemon, Mode.DAEMON, builder);
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
        return create(dnsResolver, Mode.BOOTSTRAP_SERVER_IP, builder);
      }
      LOG.info("No DNS record found for bootstrap server.");
      throw new ScionRuntimeException(
          "No DNS record found for bootstrap server. This means "
              + "the DNS server may not have NAPTR records for the bootstrap server or your host "
              + "may not have the search domains configured in /etc/resolv.conf or similar.");
    }
    throw new ScionRuntimeException("Could not connect to daemon, DNS or bootstrap resource.");
  }

  private static <T extends ScionService> T serviceFromSnapAuth(
      String authKey, String authService, Builder<T> builder) {
    String apiToken = Config.getSnapAuthToken();
    if (apiToken != null && !apiToken.isEmpty()) {
      LOG.info("Using explicit endhost API token.");
    }
    String pathServices = Config.getPathService();
    String discoveryClient = Config.getSnapPathServiceDiscovery();
    if (!isEmpty(pathServices)) {
      LOG.info("Using explicitly configured path services: {}", pathServices);
    }
    if (!isEmpty(discoveryClient)) {
      LOG.info("Using explicitly configured path service discovery: {}", discoveryClient);
    }
    // implicit path service, explicit discovery service
    if (isEmpty(pathServices) && !isEmpty(discoveryClient)) {
      List<String> candidates = EndhostApiDiscoveryClient.discoverEndhostApis(discoveryClient);
      if (candidates.isEmpty()) {
        LOG.warn("Endhost API discovery returned no candidates: {}", discoveryClient);
      } else {
        pathServices = String.join(";", candidates);
      }
    }
    // implicit API token
    if (isEmpty(apiToken)) {
      AAClient.Result result = AAClient.fetchAll(authKey, authService);
      apiToken = result.snapToken;
      // implicit path service, implicit API token, implicit discovery service
      if (isEmpty(pathServices) && !isEmpty(result.endhostApiDiscoveryUrl)) {
        String discoveryEndpoint = result.endhostApiDiscoveryUrl;
        List<String> candidates = EndhostApiDiscoveryClient.discoverEndhostApis(discoveryEndpoint);
        pathServices = String.join(";", candidates);
      }
    }
    if (isEmpty(pathServices)) {
      throw new ScionRuntimeException("SNAP is configured but no PathService is given.");
    }
    builder.snapApiToken(apiToken);
    return create(pathServices, Mode.BOOTSTRAP_SNAP, builder);
  }

  private static boolean isEmpty(String s) {
    return s == null || s.isEmpty();
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
    // We do not set defaultService to null here. We are in the process of shutting down,
    // so it should be necessary, and it avoids having to deal with locks during shutdown.
    Thread hook = new Thread(this::closeDuringShutdown);
    Runtime.getRuntime().addShutdownHook(hook);
    return hook;
  }

  public void close() {
    closeDuringShutdown();
    // We have to avoid calling this during shutdown, it will throw an exception.
    // THis may be null if the close() is called from the constructor.
    if (shutdownHook != null) {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }
  }

  private void closeDuringShutdown() {
    if (daemonService != null) {
      daemonService.close();
    }
    if (controlService != null) {
      controlService.close();
    }
    if (pathService != null) {
      pathService.close();
    }
  }

  public ScionDatagramChannel openChannel() throws IOException {
    return ScionDatagramChannel.open(this);
  }

  public ScionDatagramChannel openChannel(java.nio.channels.DatagramChannel channel)
      throws IOException {
    return ScionDatagramChannel.open(this, channel);
  }

  /**
   * Resolves the address to a SCION address.
   *
   * @param hostName Destination host name
   * @return A ScionSocketAddress
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public ScionSocketAddress lookup(String hostName, int port) throws ScionException {
    ScionAddress sa = AddressLookupService.lookupAddress(hostName);
    return ScionSocketAddress.from(sa.getIsdAs(), sa.getInetAddress(), port);
  }

  /**
   * Resolves the address to a SCION address.
   *
   * @param dstAddr Destination address
   * @return A ScionSocketAddress
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public ScionSocketAddress lookup(InetAddress dstAddr, int port) throws ScionException {
    ScionAddress sa = AddressLookupService.lookupAddress(dstAddr.getHostName());
    return ScionSocketAddress.from(sa.getIsdAs(), sa.getInetAddress(), port);
  }

  /**
   * Resolves the address to a SCION address or returns the address if it is already resolve (i.e.
   * if it is already an instance of ScionSocketAddress).
   *
   * @param dstAddr Destination address
   * @return A ScionSocketAddress
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public ScionSocketAddress lookup(InetSocketAddress dstAddr) throws ScionException {
    if (dstAddr instanceof ScionSocketAddress) {
      return (ScionSocketAddress) dstAddr;
    }
    ScionAddress sa = AddressLookupService.lookupAddress(dstAddr.getHostString());
    return ScionSocketAddress.from(sa.getIsdAs(), sa.getInetAddress(), dstAddr.getPort());
  }

  /**
   * Request paths from the local ISD/AS to the destination.
   *
   * @param dstIsdAs Destination ISD/AS
   * @param dstScionAddress Destination IP address. Must belong to a SCION enabled end host.
   * @return All paths returned by the path service.
   */
  public List<Path> getPaths(long dstIsdAs, InetSocketAddress dstScionAddress) {
    return getPaths(dstIsdAs, dstScionAddress.getAddress(), dstScionAddress.getPort());
  }

  /**
   * Request paths to the same destination as the provided path.
   *
   * @param dstAddress Destination IP address. Must belong to a SCION enabled end host.
   * @return All paths returned by the path service.
   */
  public List<Path> getPaths(ScionSocketAddress dstAddress) {
    return getPaths(dstAddress.getIsdAs(), dstAddress.getAddress(), dstAddress.getPort());
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
   * @return All paths returned by the path service.
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public List<Path> lookupPaths(String hostName, int port) throws ScionException {
    return getPaths(AddressLookupService.lookupAddress(hostName), port);
  }

  /**
   * Resolves the address to a SCION address, request paths, and selects a path using the policy.
   *
   * @param dstAddr Destination address
   * @return All paths returned by the path service.
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public List<Path> lookupPaths(InetSocketAddress dstAddr) throws ScionException {
    return getPaths(AddressLookupService.lookupAddress(dstAddr.getHostString()), dstAddr.getPort());
  }

  /**
   * Request paths from the local ISD/AS to the destination.
   *
   * @param dstAddress Destination SCION address
   * @return All paths returned by the path service.
   */
  private List<Path> getPaths(ScionAddress dstAddress, int dstPort) {
    List<PathMetadata> paths = getPathList(dstAddress.getIsdAs());
    List<Path> scionPaths = new ArrayList<>(paths.size());
    boolean isSnap = snapDataplaneDetails != null;
    for (PathMetadata meta : paths) {
      scionPaths.add(
          RequestPath.create(meta, dstAddress.getInetAddress(), dstPort, localAS, isSnap));
    }
    return scionPaths;
  }

  /**
   * @return local ISD/AS. If multiple are available, it will return a random one.
   * @deprecated To be removed in 0.8.0.
   */
  @Deprecated
  public long getLocalIsdAs() {
    return localAS.getIsdAs();
  }

  /**
   * @return all local ISD/ASes
   */
  public List<Long> getLocalIsdAsList() {
    return localAS.getIsdAses();
  }

  /**
   * @param hostName hostName of the host to resolve
   * @return The ISD/AS code for a hostname
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public long getIsdAs(String hostName) throws ScionException {
    return AddressLookupService.getIsdAs(hostName);
  }

  private List<PathMetadata> getPathList(long dstIsdAs) {
    List<PathMetadata> list;
    if (pathService != null) {
      // query path service (new endhost API)
      list = new ArrayList<>();
      for (Long srcIsdAs : getLocalIsdAsList()) {
        list.addAll(getPathList(srcIsdAs, dstIsdAs));
      }
    } else {
      // query daemon or control service
      // TODO implement multi-ISD capability
      list = getPathList(getLocalIsdAs(), dstIsdAs);
    }
    return list;
  }

  List<PathMetadata> getPathList(long srcIsdAs, long dstIsdAs) {
    List<PathMetadata> list;
    if (pathService != null) {
      list = PathBuilder.getPathsPS(pathService, localAS, srcIsdAs, dstIsdAs);
    } else if (daemonService != null) {
      list = daemonService.pathsAsMetadata(srcIsdAs, dstIsdAs);
    } else {
      list = PathBuilder.getPathsCS(controlService, localAS, srcIsdAs, dstIsdAs);
    }
    if (LOG.isInfoEnabled()) {
      String src = ScionUtil.toStringIA(srcIsdAs);
      String dst = ScionUtil.toStringIA(dstIsdAs);
      LOG.info("Paths found from {} to {}: {}", src, dst, list.size());
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
        localAS.getBorderRouters().stream()
            .map(LocalAS.BorderRouter::getInternalAddress)
            .collect(Collectors.toList());
    return NatMapping.createMapping(channel, interfaces);
  }

  LocalAS.DispatcherPortRange getLocalPortRange() {
    return localAS.getPortRange();
  }

  protected InetSocketAddress getBorderRouterAddress(int interfaceID) {
    return localAS.getBorderRouterAddress(interfaceID);
  }

  // Public (not just package-private) so that org.scion.jpan.internal.SelectingDatagramChannel
  // (used by ScionDatagramSocket) can wire up SNAP the same way ScionDatagramChannel.Builder does.
  @Deprecated // Internal method, do not use!
  public SnapDataplaneDetails getSnapDataPlane() {
    return snapDataplaneDetails;
  }

  protected ControlServiceGrpc getControlServiceConnection() {
    return controlService;
  }

  protected DaemonServiceGrpc getDaemonConnection() {
    return daemonService;
  }

  protected LocalAS getLocalAS() {
    return localAS;
  }

  public static class Builder<T extends ScionService> {
    protected LocalAS localAS;
    protected ControlServiceGrpc controlService;
    protected PathServiceRpc pathService;
    protected DaemonServiceGrpc daemonService;
    protected SnapDataplaneDetails snapDataplaneDetails;
    protected String snapApiToken;

    public static Builder<ScionService> create() {
      return new Builder<>();
    }

    @SuppressWarnings("unchecked")
    public T build() {
      return (T) new ScionService(this);
    }

    public Builder<T> localAs(LocalAS localAS) {
      this.localAS = localAS;
      return this;
    }

    public Builder<T> controlService(ControlServiceGrpc controlService) {
      this.controlService = controlService;
      return this;
    }

    public Builder<T> daemonService(DaemonServiceGrpc daemonService) {
      this.daemonService = daemonService;
      return this;
    }

    public Builder<T> pathService(PathServiceRpc pathService) {
      this.pathService = pathService;
      return this;
    }

    public Builder<T> snapDetails(SnapDataplaneDetails snapDataplaneDetails) {
      this.snapDataplaneDetails = snapDataplaneDetails;
      return this;
    }

    public Builder<T> snapApiToken(String apiToken) {
      this.snapApiToken = apiToken;
      return this;
    }
  }
}
