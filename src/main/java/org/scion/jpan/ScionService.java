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
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.bootstrap.ScionBootstrapper;
import org.scion.jpan.internal.paths.*;
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
  private final Thread shutdownHook;

  protected enum Mode {
    DAEMON,
    BOOTSTRAP_SERVER_IP,
    BOOTSTRAP_VIA_DNS,
    BOOTSTRAP_TOPO_FILE,
    BOOTSTRAP_PATH_SERVICE
  }

  interface Constructor<T> {
    T create(
        LocalAS localAS,
        ControlServiceGrpc controlService,
        PathServiceRpc pathService,
        DaemonServiceGrpc daemonService);
  }

  protected ScionService(
      LocalAS localAS,
      ControlServiceGrpc controlService,
      PathServiceRpc pathService,
      DaemonServiceGrpc daemonService) {
    this.localAS = localAS;
    this.controlService = controlService;
    this.pathService = pathService;
    this.daemonService = daemonService;
    this.shutdownHook = addShutdownHook();
  }

  static <T> T create(String addressOrHost, Mode mode, Constructor<T> constructor) {
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
        return constructor.create(localAS, null, null, daemonService);
      case BOOTSTRAP_PATH_SERVICE:
        LOG.info("Bootstrapping with path service: {}", addressOrHost);
        localAS = checkStartShim(ScionBootstrapper.fromPathService(addressOrHost));
        PathServiceRpc pathService = PathServiceRpc.create(localAS);
        return constructor.create(localAS, null, pathService, null);
      case BOOTSTRAP_VIA_DNS:
        LOG.info("Bootstrapping control service via DNS: {}", addressOrHost);
        localAS = checkStartShim(ScionBootstrapper.fromDns(addressOrHost));
        return constructor.create(localAS, ControlServiceGrpc.create(localAS), null, null);
      case BOOTSTRAP_SERVER_IP:
        LOG.info("Bootstrapping control service with IP address: {}", addressOrHost);
        localAS = checkStartShim(ScionBootstrapper.fromBootstrapServerIP(addressOrHost));
        return constructor.create(localAS, ControlServiceGrpc.create(localAS), null, null);
      case BOOTSTRAP_TOPO_FILE:
        LOG.info("Bootstrapping control service from file: {}", addressOrHost);
        localAS = checkStartShim(ScionBootstrapper.fromTopoFile(addressOrHost));
        return constructor.create(localAS, ControlServiceGrpc.create(localAS), null, null);
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
        defaultService = create(fileName, Mode.BOOTSTRAP_TOPO_FILE, ScionService::new);
        return defaultService;
      }

      String pathService = Config.getPathService();
      if (pathService != null) {
        defaultService = create(pathService, Mode.BOOTSTRAP_PATH_SERVICE, ScionService::new);
        return defaultService;
      }

      String server = ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_HOST, ENV_BOOTSTRAP_HOST);
      if (server != null) {
        defaultService = create(server, Mode.BOOTSTRAP_SERVER_IP, ScionService::new);
        return defaultService;
      }

      String naptrName =
          ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_NAPTR_NAME, ENV_BOOTSTRAP_NAPTR_NAME);
      if (naptrName != null) {
        defaultService = create(naptrName, Mode.BOOTSTRAP_VIA_DNS, ScionService::new);
        return defaultService;
      }

      // try daemon
      String daemon = ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON, ENV_DAEMON, DEFAULT_DAEMON);
      try {
        defaultService = create(daemon, Mode.DAEMON, ScionService::new);
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
          defaultService = create(dnsResolver, Mode.BOOTSTRAP_SERVER_IP, ScionService::new);
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
    for (PathMetadata meta : paths) {
      scionPaths.add(RequestPath.create(meta, dstAddress.getInetAddress(), dstPort));
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

  public Set<Long> getLocalIsdAses() {
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
      for (Long srcIsdAs : getLocalIsdAses()) {
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

  InetSocketAddress getBorderRouterAddress(int interfaceID) {
    return localAS.getBorderRouterAddress(interfaceID);
  }

  ControlServiceGrpc getControlServiceConnection() {
    return controlService;
  }

  DaemonServiceGrpc getDaemonConnection() {
    return daemonService;
  }

  LocalAS getLocalAS() {
    return localAS;
  }
}
