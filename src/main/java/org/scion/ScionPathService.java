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

import io.grpc.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import static org.scion.ScionConstants.DEFAULT_DAEMON_HOST;
import static org.scion.ScionConstants.DEFAULT_DAEMON_PORT;
import static org.scion.ScionConstants.ENV_DAEMON_HOST;
import static org.scion.ScionConstants.ENV_DAEMON_PORT;
import static org.scion.ScionConstants.PROPERTY_DAEMON_HOST;
import static org.scion.ScionConstants.PROPERTY_DAEMON_PORT;

public class ScionPathService implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ScionPathService.class.getName());
  private static final String DAEMON_HOST =
      ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON_HOST, ENV_DAEMON_HOST, DEFAULT_DAEMON_HOST);
  private static final String DAEMON_PORT =
      ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON_PORT, ENV_DAEMON_PORT, DEFAULT_DAEMON_PORT);

  private final DaemonServiceGrpc.DaemonServiceBlockingStub blockingStub;

  private final ManagedChannel channel;

  private ScionPathService(String daemonAddress) {
    channel = Grpc.newChannelBuilder(daemonAddress, InsecureChannelCredentials.create()).build();
    blockingStub = DaemonServiceGrpc.newBlockingStub(channel);
    LOG.info("Path service started on " + channel.toString() + " " + daemonAddress);
  }

  public static ScionPathService create(String daemonHost, int daemonPort) {
    return create(daemonHost + ":" + daemonPort);
  }

  public static ScionPathService create(String daemonAddress) {
    return new ScionPathService(daemonAddress);
  }

  public static ScionPathService create(InetSocketAddress address) {
    return create(address.getHostName(), address.getPort());
  }

  public static ScionPathService create() {
    return create(DAEMON_HOST + ":" + DAEMON_PORT);
  }

  Daemon.ASResponse getASInfo() {
    LOG.info("*** GetASInfo ***");

    Daemon.ASRequest request =
        Daemon.ASRequest.newBuilder().setIsdAs(0).build(); // TODO getDefaultInstance()?

    Daemon.ASResponse response;
    try {
      response = blockingStub.aS(request);
    } catch (StatusRuntimeException e) {
      throw new ScionException(e);
    }

    return response;
  }

  Map<Long, Daemon.Interface> getInterfaces() {
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

  // TODO do not expose proto types
  List<Daemon.Path> getPathList(long srcIsdAs, long dstIsdAs) {
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
   * Request and return a path from srcIsdAs to dstIsdAs.
   * @param srcIsdAs Source ISD + AS
   * @param dstIsdAs Destination ISD + AS
   * @return The first path is returned by the path service.
   */
  public ScionPath getPath(long srcIsdAs, long dstIsdAs) {
    List<Daemon.Path> paths = getPathList(srcIsdAs, dstIsdAs);
    if (paths.isEmpty()) {
      throw new ScionException("No path found from " + ScionUtil.toStringIA(srcIsdAs) + " to " + ScionUtil.toStringIA(dstIsdAs));
    }
    return new ScionPath(paths.get(0));
  }

  Map<String, Daemon.ListService> getServices() {
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

  public long getLocalIsdAs() {
    return getASInfo().getIsdAs();
  }

  @Override
  public void close() throws IOException {
    try {
      channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  /**
   * TODO Have a Daemon singleton + Move this method into ScionAddress + Create ScionSocketAddress
   *    class.
   *
   * @param addr IP address of the host to resolve
   * @return A ScionAddress
   * @throws UnknownHostException if the URL could not be resolved.
   * @throws ScionException if the URL did not return a SCION address.
   */
  public ScionAddress getScionAddress(InetAddress addr) throws UnknownHostException {
    // $ dig +short TXT ethz.ch | grep "scion="
    // "scion=64-2:0:9,129.132.230.98"
    String hostname = addr.getHostName();
    try {
      Record[] records = new Lookup(hostname, Type.TXT).run();
      for (int i = 0; i < records.length; i++) {
        TXTRecord txt = (TXTRecord) records[i];
        String entry = txt.rdataToString();
        if (entry.startsWith("\"scion=")) {
          // dnsEntry example: "scion=64-2:0:9,129.132.230.98"
          int posComma = entry.indexOf(',');
          long isdAs = ScionUtil.ParseIA(entry.substring(7, posComma));
          System.out.println("Name: " + txt.getName());
          return ScionAddress.fromDnsEntry(hostname, isdAs, entry.substring(posComma + 1, entry.length() - 1));
        }
      }
    } catch (TextParseException e) {
      throw new ScionException(e.getMessage());
    }

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

//    final String TXT_ATTR = "TXT";
//    final Properties env = new Properties();
//    // TODO add /etc/scion-hosts or /etc/scion/hosts
//    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
//    try {
//      InitialDirContext idc = new InitialDirContext(env);
//      // TODO add /etc/scion-hosts or /etc/scion/hosts
//      //env.put(javax.naming.Context.PROVIDER_URL, "dns://"+dnsServer);
//      final Attributes attrs = idc.getAttributes(hostname, new String[]{TXT_ATTR});
//      final Attribute attrT = attrs.get(TXT_ATTR);
//      if (attrT != null) {
//        for (int i = 0; i < attrT.size(); i++)  {
//          String entry = (String) attrT.get(i);
//          System.out.println("TXT: " + attrT.get(i));
//          if (entry.startsWith("scion=")) {
//            return ScionAddress.fromDnsEntry(hostname, entry);
//          }
//        }
//      }
//    } catch (NamingException e) {
//      throw new RuntimeException(e);
//    }

    throw new ScionException("Host has no SCION entry: " + hostname); // TODO test
  }
}
