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

import static org.scion.ScionConstants.DEFAULT_DAEMON_HOST;
import static org.scion.ScionConstants.DEFAULT_DAEMON_PORT;
import static org.scion.ScionConstants.ENV_DAEMON_HOST;
import static org.scion.ScionConstants.ENV_DAEMON_PORT;
import static org.scion.ScionConstants.PROPERTY_DAEMON_HOST;
import static org.scion.ScionConstants.PROPERTY_DAEMON_PORT;

import io.grpc.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.scion.proto.control_plane.Seg;
import org.scion.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

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
  private static final String DAEMON_HOST =
      ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON_HOST, ENV_DAEMON_HOST, DEFAULT_DAEMON_HOST);
  private static final String DAEMON_PORT =
      ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON_PORT, ENV_DAEMON_PORT, DEFAULT_DAEMON_PORT);

  private static ScionService DEFAULT;

  // TODO create subclasses for these two?
  private final DaemonServiceGrpc.DaemonServiceBlockingStub daemonStub;
  private final SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub;

  private final ManagedChannel channel;
  private static final long ISD_AS_NOT_SET = -1;
  private final AtomicLong localIsdAs = new AtomicLong(ISD_AS_NOT_SET);

  protected enum Mode {
    DAEMON,
    CONTROL_SERVICE_IP,
    CONTROL_SERVICE_DNS
  }

  protected ScionService(String addressOrHost, Mode mode) {
    if (mode == Mode.DAEMON) {
      // TODO InsecureChannelCredentials?
      channel = Grpc.newChannelBuilder(addressOrHost, InsecureChannelCredentials.create()).build();
      daemonStub = DaemonServiceGrpc.newBlockingStub(channel);
      segmentStub = null;
      LOG.info("Path service started with daemon " + channel.toString() + " " + addressOrHost);
    } else if (mode == Mode.CONTROL_SERVICE_DNS) {
      String csHost = ScionBootsrapper.defaultService(addressOrHost).getControlServerAddress();
      // TODO InsecureChannelCredentials?
      channel = Grpc.newChannelBuilder(csHost, InsecureChannelCredentials.create()).build();
      daemonStub = null;
      segmentStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
      LOG.info("Path service started with control service " + channel.toString() + " " + csHost);
    } else if (mode == Mode.CONTROL_SERVICE_IP) {
      // TODO InsecureChannelCredentials?
      channel = Grpc.newChannelBuilder(addressOrHost, InsecureChannelCredentials.create()).build();
      daemonStub = null;
      segmentStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
      LOG.info(
          "Path service started with control service " + channel.toString() + " " + addressOrHost);
    } else {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * @return default instance
   */
  public static synchronized ScionService defaultService() {
    if (DEFAULT == null) {
      DEFAULT = new ScionService(DAEMON_HOST + ":" + DAEMON_PORT, Mode.DAEMON);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      DEFAULT.close();
                    } catch (IOException e) {
                      e.printStackTrace(System.err);
                    }
                  }));
    }
    return DEFAULT;
  }

  Daemon.ASResponse getASInfo() throws ScionException {
    // LOG.info("*** GetASInfo ***");
    Daemon.ASRequest request = Daemon.ASRequest.newBuilder().setIsdAs(0).build();
    Daemon.ASResponse response;
    try {
      response = daemonStub.aS(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException("Error while getting AS info: " + e.getMessage(), e);
    }
    return response;
  }

  Map<Long, Daemon.Interface> getInterfaces() throws ScionException {
    // LOG.info("*** GetInterfaces ***");
    Daemon.InterfacesRequest request = Daemon.InterfacesRequest.newBuilder().build();
    Daemon.InterfacesResponse response;
    try {
      response = daemonStub.interfaces(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }
    return response.getInterfacesMap();
  }

  // TODO do not expose proto types on API
  @Deprecated
  List<Daemon.Path> getPathList(long srcIsdAs, long dstIsdAs) throws ScionException {
    // LOG.info("*** GetPath: src={} dst={}", srcIsdAs, dstIsdAs);
    Daemon.PathsRequest request =
        Daemon.PathsRequest.newBuilder()
            .setSourceIsdAs(srcIsdAs)
            .setDestinationIsdAs(dstIsdAs)
            .build();

    Daemon.PathsResponse response;
    try {
      response = daemonStub.paths(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }

    return response.getPathsList();
  }

  /**
   * Requests paths from the local ISD/AS to the destination.
   *
   * @param dstAddress Destination IP address
   * @return All paths returned by the path service.
   * @throws IOException if an errors occurs while querying paths.
   */
  public List<RequestPath> getPaths(InetSocketAddress dstAddress) throws IOException {
    long dstIsdAs = getIsdAs(dstAddress.getHostName());
    return getPaths(dstIsdAs, dstAddress);
  }

  /**
   * Request paths from the local ISD/AS to the destination.
   *
   * @param dstIsdAs Destination ISD/AS
   * @param dstAddress Destination IP address
   * @return All paths returned by the path service.
   * @throws IOException if an errors occurs while querying paths.
   */
  public List<RequestPath> getPaths(long dstIsdAs, InetSocketAddress dstAddress)
      throws IOException {
    return getPaths(dstIsdAs, dstAddress.getAddress().getAddress(), dstAddress.getPort());
  }

  /**
   * Request paths to the same destination as the provided path.
   *
   * @param path A path
   * @return All paths returned by the path service.
   * @throws IOException if an errors occurs while querying paths.
   */
  public List<RequestPath> getPaths(RequestPath path) throws IOException {
    return getPaths(
        path.getDestinationIsdAs(), path.getDestinationAddress(), path.getDestinationPort());
  }

  /**
   * Request paths from the local ISD/AS to the destination.
   *
   * @param dstIsdAs Destination ISD/AS
   * @param dstAddress Destination IP address
   * @param dstPort Destination port
   * @return All paths returned by the path service.
   * @throws IOException if an errors occurs while querying paths.
   */
  public List<RequestPath> getPaths(long dstIsdAs, byte[] dstAddress, int dstPort)
      throws IOException {
    long srcIsdAs = getLocalIsdAs();
    List<Daemon.Path> paths = getPathList(srcIsdAs, dstIsdAs);
    if (paths.isEmpty()) {
      return Collections.emptyList();
    }
    List<RequestPath> scionPaths = new ArrayList<>(paths.size());
    for (int i = 0; i < paths.size(); i++) {
      scionPaths.add(RequestPath.create(paths.get(i), dstIsdAs, dstAddress, dstPort));
    }
    return scionPaths;
  }

  Map<String, Daemon.ListService> getServices() throws ScionException {
    // LOG.info("*** GetServices ***");
    Daemon.ServicesRequest request = Daemon.ServicesRequest.newBuilder().build();
    Daemon.ServicesResponse response;
    try {
      response = daemonStub.services(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }
    return response.getServicesMap();
  }

  public long getLocalIsdAs() throws ScionException {
    if (localIsdAs.get() == ISD_AS_NOT_SET) {
      // Yes, this may be called multiple time by different threads, but it should be
      // faster than `synchronize`.
      localIsdAs.set(getASInfo().getIsdAs());
    }
    return localIsdAs.get();
  }

  public void close() throws IOException {
    try {
      if (!channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)) {
        if (!channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)) {
          LOG.error("Failed to shut down ScionService gRPC ManagedChannel");
        }
      }
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  /**
   * @param hostName hostName of the host to resolve
   * @return A ScionAddress
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public long getIsdAs(String hostName) throws ScionException {
    // $ dig +short TXT ethz.ch | grep "scion="
    // "scion=64-2:0:9,129.132.230.98"

    // Look for TXT in application properties
    String txtFromProperties = findTxtRecordInProperties(hostName);
    if (txtFromProperties != null) {
      return parseTxtRecordToIA(txtFromProperties);
    }

    // Use local ISD/AS for localhost addresses
    if (isLocalhost(hostName)) {
      return getLocalIsdAs();
    }

    // DNS lookup
    String txtFromDNS = findTxtRecordViaDNS(hostName);
    if (txtFromDNS != null) {
      return parseTxtRecordToIA(txtFromDNS);
    }

    throw new ScionException("Host has no SCION TXT entry: " + hostName);
  }

  /**
   * @param hostName hostName of the host to resolve
   * @return A ScionAddress
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public ScionAddress getScionAddress(String hostName) throws ScionException {
    // $ dig +short TXT ethz.ch | grep "scion="
    // "scion=64-2:0:9,129.132.230.98"

    // Look for TXT in application properties
    String txtFromProperties = findTxtRecordInProperties(hostName);
    if (txtFromProperties != null) {
      return parseTxtRecord(txtFromProperties, hostName);
    }

    // Use local ISD/AS for localhost addresses
    if (isLocalhost(hostName)) {
      return ScionAddress.create(getLocalIsdAs(), hostName, hostName);
    }

    // DNS lookup
    String txtFromDNS = findTxtRecordViaDNS(hostName);
    if (txtFromDNS != null) {
      return parseTxtRecord(txtFromDNS, hostName);
    }

    throw new ScionException("Host has no SCION TXT entry: " + hostName);
  }

  private boolean isLocalhost(String hostName) {
    return hostName.startsWith("127.0.0.")
        || "::1".equals(hostName)
        || "0:0:0:0:0:0:0:1".equals(hostName)
        || "localhost".equals(hostName)
        || "ip6-localhost".equals(hostName);
  }

  private String findTxtRecordViaDNS(String hostName) throws ScionException {
    try {
      Record[] records = new Lookup(hostName, Type.TXT).run();
      if (records == null) {
        throw new ScionException("No DNS TXT entry found for host: " + hostName);
      }
      for (int i = 0; i < records.length; i++) {
        TXTRecord txt = (TXTRecord) records[i];
        String entry = txt.rdataToString();
        if (entry.startsWith("\"scion=")) {
          return entry;
        }
      }
    } catch (TextParseException e) {
      throw new ScionException("Error parsing TXT entry: " + e.getMessage());
    }
    // TODO add /etc/scion-hosts or /etc/scion/hosts

    // Java 8
    //    List nameServers = ResolverConfiguration.open().nameservers();
    //    List searchNames = ResolverConfiguration.open().searchlist();
    //    System.out.println("NS: ");
    //    nameServers.forEach((dns)->System.out.println(dns));
    //    System.out.println("SL: ");
    //    searchNames.forEach((dns)->System.out.println(dns));
    //    System.out.println("DNS: ");

    //    // Java 9+
    //    java.net.InetAddress.NameService;
    //
    //    // Java 18+
    //    InetAddressResolverProvider p = InetAddressResolverProvider.Configuration;
    //    InetAddressResolver r = new InetAddressResolver();

    return null;
  }

  private String findTxtRecordInProperties(String hostName) {
    String props = System.getProperty(ScionConstants.DEBUG_PROPERTY_DNS_MOCK);
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
      // No more checking here, we assume that properties are save
      return txtRecord;
    }
    return null;
  }

  private ScionAddress parseTxtRecord(String txtEntry, String hostName) throws ScionException {
    // dnsEntry example: "scion=64-2:0:9,129.132.230.98"
    int posComma = txtEntry.indexOf(',');
    if (!txtEntry.startsWith("\"scion=") || !txtEntry.endsWith("\"") || posComma < 0) {
      throw new ScionException("Invalid TXT entry: " + txtEntry);
    }
    long isdAs = ScionUtil.parseIA(txtEntry.substring(7, posComma));
    return ScionAddress.create(
        isdAs, hostName, txtEntry.substring(posComma + 1, txtEntry.length() - 1));
  }

  private long parseTxtRecordToIA(String txtEntry) throws ScionException {
    // dnsEntry example: "scion=64-2:0:9,129.132.230.98"
    int posComma = txtEntry.indexOf(',');
    if (!txtEntry.startsWith("\"scion=") || !txtEntry.endsWith("\"") || posComma < 0) {
      throw new ScionException("Invalid TXT entry: " + txtEntry);
    }
    return ScionUtil.parseIA(txtEntry.substring(7, posComma));
  }

  @Deprecated // TODO experimental, do not use
  public String bootstrapViaDNS(String hostName) {
    return ScionBootsrapper.defaultService(hostName).getControlServerAddress();
  }

  @Deprecated // TODO do not use
  public void getSegments(long srcIsdAs, long dstIsdAs) throws ScionException {
    // LOG.info("*** GetASInfo ***");
    Seg.SegmentsRequest request =
        Seg.SegmentsRequest.newBuilder().setSrcIsdAs(srcIsdAs).setDstIsdAs(dstIsdAs).build();
    Seg.SegmentsResponse response;
    try {
      response = segmentStub.segments(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException("Error while getting AS info: " + e.getMessage(), e);
    }

    for (Map.Entry<Integer, Seg.SegmentsResponse.Segments> e :
        response.getSegmentsMap().entrySet()) {
      System.out.println("SEG: " + e.getKey() + " -> " + e.getValue().getSegmentsCount());
    }

    // return response;
  }
}
