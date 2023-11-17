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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

  private final DaemonServiceGrpc.DaemonServiceBlockingStub blockingStub;

  private final ManagedChannel channel;
  private static final long ISD_AS_NOT_SET = -1;
  private final AtomicLong localIsdAs = new AtomicLong(ISD_AS_NOT_SET);

  protected ScionService(String daemonAddress) {
    // TODO InsecureChannelCredentials?
    channel = Grpc.newChannelBuilder(daemonAddress, InsecureChannelCredentials.create()).build();
    blockingStub = DaemonServiceGrpc.newBlockingStub(channel);
    LOG.info("Path service started on " + channel.toString() + " " + daemonAddress);
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * @return default instance
   */
  public static synchronized ScionService defaultService() {
    if (DEFAULT == null) {
      DEFAULT = new ScionService(DAEMON_HOST + ":" + DAEMON_PORT);
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
    LOG.info("*** GetASInfo ***");

    Daemon.ASRequest request =
        Daemon.ASRequest.newBuilder().setIsdAs(0).build(); // TODO getDefaultInstance()?

    Daemon.ASResponse response;
    try {
      response = blockingStub.aS(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException("Error while getting AS info", e);
    }

    return response;
  }

  Map<Long, Daemon.Interface> getInterfaces() throws ScionException {
    LOG.info("*** GetInterfaces ***");

    Daemon.InterfacesRequest request =
        Daemon.InterfacesRequest.newBuilder().build(); // TODO getDefaultInstance()?

    Daemon.InterfacesResponse response;
    try {
      response = blockingStub.interfaces(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }

    return response.getInterfacesMap();
  }

  // TODO do not expose proto types on API
  List<Daemon.Path> getPathList(long srcIsdAs, long dstIsdAs) throws ScionException {
    LOG.info("*** GetPath: src={} dst={}", srcIsdAs, dstIsdAs);

    Daemon.PathsRequest request =
        Daemon.PathsRequest.newBuilder()
            .setSourceIsdAs(srcIsdAs)
            .setDestinationIsdAs(dstIsdAs)
            .build();

    Daemon.PathsResponse response;
    try {
      response = blockingStub.paths(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }

    return response.getPathsList();
  }

  /**
   * Request and return a path from the local ISD/AS to dstIsdAs.
   *
   * @param dstIsdAs Destination ISD + AS
   * @return The first path is returned by the path service.
   * @throws ScionException if an errors occurs while querying paths.
   */
  public ScionPath getPath(long dstIsdAs) throws ScionException {
    return getPath(getLocalIsdAs(), dstIsdAs);
  }

  /**
   * Request and return a path from srcIsdAs to dstIsdAs.
   *
   * @param srcIsdAs Source ISD + AS
   * @param dstIsdAs Destination ISD + AS
   * @return The first path is returned by the path service or 'null' if no path could be found.
   * @throws ScionException if an errors occurs while querying paths.
   */
  public ScionPath getPath(long srcIsdAs, long dstIsdAs) throws ScionException {
    List<Daemon.Path> paths = getPathList(srcIsdAs, dstIsdAs);
    if (paths.isEmpty()) {
      return null;
    }
    return new ScionPath(paths.get(0), srcIsdAs, dstIsdAs);
  }

  Map<String, Daemon.ListService> getServices() throws ScionException {
    LOG.info("*** GetServices ***");

    Daemon.ServicesRequest request =
        Daemon.ServicesRequest.newBuilder().build(); // TODO getDefaultInstance()?

    Daemon.ServicesResponse response;
    try {
      response = blockingStub.services(request);
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

  protected void close() throws IOException {
    try {
      channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  /**
   * TODO Have a Daemon singleton + Move this method into ScionAddress + Create ScionSocketAddress
   * class.
   *
   * @param hostName hostName of the host to resolve
   * @return A ScionAddress
   * @throws ScionException if the URL did not return a SCION address.
   */
  public ScionAddress getScionAddress(String hostName) throws ScionException {
    // $ dig +short TXT ethz.ch | grep "scion="
    // "scion=64-2:0:9,129.132.230.98"

    // Look for TXT in properties
    String txtFromProperties = findTxtRecordInProperties(hostName);
    if (txtFromProperties != null) {
      return parseTxtRecord(txtFromProperties, hostName);
    }

    // Use local ISD/AS for localhost addresses
    if (hostName.startsWith("127.0.0.")
        || "::1".equals(hostName)
        || "0:0:0:0:0:0:0:1".equals(hostName)) {
      long isdAs = ScionService.defaultService().getLocalIsdAs();
      return ScionAddress.create(isdAs, hostName, hostName);
    }

    // DNS lookup
    try {
      Record[] records = new Lookup(hostName, Type.TXT).run();
      if (records == null) {
        // throw new UnknownHostException("No DNS entry found for host: " + hostName); // TODO ?
        throw new ScionException("No DNS entry found for host: " + hostName); // TODO test
      }
      for (int i = 0; i < records.length; i++) {
        TXTRecord txt = (TXTRecord) records[i];
        String entry = txt.rdataToString();
        if (entry.startsWith("\"scion=")) {
          return parseTxtRecord(entry, hostName);
        }
      }
    } catch (TextParseException e) {
      throw new ScionException(e.getMessage());
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

    throw new ScionException("Host has no SCION TXT entry: " + hostName); // TODO test
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

  private ScionAddress parseTxtRecord(String txtEntry, String hostName) {
    // dnsEntry example: "scion=64-2:0:9,129.132.230.98"
    int posComma = txtEntry.indexOf(',');
    long isdAs = ScionUtil.parseIA(txtEntry.substring(7, posComma));
    return ScionAddress.create(
        isdAs, hostName, txtEntry.substring(posComma + 1, txtEntry.length() - 1));
  }
}
