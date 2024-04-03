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

import static org.scion.Constants.DEFAULT_DAEMON;
import static org.scion.Constants.DEFAULT_USE_OS_SEARCH_DOMAINS;
import static org.scion.Constants.ENV_BOOTSTRAP_HOST;
import static org.scion.Constants.ENV_BOOTSTRAP_NAPTR_NAME;
import static org.scion.Constants.ENV_BOOTSTRAP_TOPO_FILE;
import static org.scion.Constants.ENV_DAEMON;
import static org.scion.Constants.ENV_USE_OS_SEARCH_DOMAINS;
import static org.scion.Constants.PROPERTY_BOOTSTRAP_HOST;
import static org.scion.Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME;
import static org.scion.Constants.PROPERTY_BOOTSTRAP_TOPO_FILE;
import static org.scion.Constants.PROPERTY_DAEMON;
import static org.scion.Constants.PROPERTY_USE_OS_SEARCH_DOMAINS;

import io.grpc.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.scion.internal.DNSHelper;
import org.scion.internal.HostsFileParser;
import org.scion.internal.ScionBootstrapper;
import org.scion.internal.Segments;
import org.scion.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
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

  private final ManagedChannel channel;
  private static final long ISD_AS_NOT_SET = -1;
  private final AtomicLong localIsdAs = new AtomicLong(ISD_AS_NOT_SET);
  private Thread shutdownHook;
  private final java.nio.channels.DatagramChannel[] ifDiscoveryChannel = {null};
  private final HostsFileParser hostsFile = new HostsFileParser();

  protected enum Mode {
    DAEMON,
    BOOTSTRAP_SERVER_IP,
    BOOTSTRAP_VIA_DNS,
    BOOTSTRAP_TOPO_FILE
  }

  protected ScionService(String addressOrHost, Mode mode) {
    if (mode == Mode.DAEMON) {
      channel = Grpc.newChannelBuilder(addressOrHost, InsecureChannelCredentials.create()).build();
      daemonStub = DaemonServiceGrpc.newBlockingStub(channel);
      segmentStub = null;
      bootstrapper = null;
      LOG.info("Path service started with daemon {} {}", channel, addressOrHost);
    } else {
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
      String csHost = bootstrapper.getControlServerAddress();
      localIsdAs.set(bootstrapper.getLocalIsdAs());
      // TODO InsecureChannelCredentials: Implement authentication!
      channel = Grpc.newChannelBuilder(csHost, InsecureChannelCredentials.create()).build();
      daemonStub = null;
      segmentStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
      LOG.info("Path service started with control service {} {}", channel, csHost);
    }
    shutdownHook = addShutdownHook();
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
        // Ignore!
      }

      // try normal network
      if (ScionUtil.getPropertyOrEnv(
          PROPERTY_USE_OS_SEARCH_DOMAINS,
          ENV_USE_OS_SEARCH_DOMAINS,
          DEFAULT_USE_OS_SEARCH_DOMAINS)) {
        String dnsResolver = DNSHelper.searchForDiscoveryService();
        defaultService = new ScionService(dnsResolver, Mode.BOOTSTRAP_SERVER_IP);
        return defaultService;
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
      if (shutdownHook != null) {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  // NOTE for 0.1.0 reviewers: THis method is used internally in ScionService, see review part 1.
  // Protobuf is not exposed in the public API.
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
   * @param hostName hostName of the host to resolve
   * @return A ScionAddress
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public long getIsdAs(String hostName) throws ScionException {
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
    if (isLocalhost(hostName)) {
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
   * @param hostName hostName of the host to resolve
   * @return A ScionAddress
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public ScionAddress getScionAddress(String hostName) throws ScionException {
    // Look for TXT in application properties
    String txtFromProperties = findTxtRecordInProperties(hostName);
    if (txtFromProperties != null) {
      ScionAddress address = parseTxtRecord(txtFromProperties, hostName);
      if (address == null) {
        throw new ScionException(ERR_INVALID_TXT + txtFromProperties);
      }
      return address;
    }

    // Use local ISD/AS for localhost addresses
    if (isLocalhost(hostName)) {
      return ScionAddress.create(getLocalIsdAs(), hostName, hostName);
    }

    // DNS lookup
    ScionAddress fromDNS =
        DNSHelper.queryTXT(hostName, DNS_TXT_KEY, x -> parseTxtRecord(x, hostName));
    if (fromDNS != null) {
      return fromDNS;
    }

    throw new ScionException("No DNS TXT entry \"scion\" found for host: " + hostName);
  }

  private boolean isLocalhost(String hostName) {
    return hostName.startsWith("127.0.0.")
        || "::1".equals(hostName)
        || "0:0:0:0:0:0:0:1".equals(hostName)
        || "localhost".equals(hostName)
        || "ip6-localhost".equals(hostName);
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
      return ScionAddress.create(isdAs, hostName, txtEntry.substring(posComma + 1));
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
    return Segments.getPaths(segmentStub, bootstrapper, srcIsdAs, dstIsdAs);
  }
}
