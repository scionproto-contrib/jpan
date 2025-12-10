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

package org.scion.jpan.internal;

import static org.scion.jpan.Constants.ENV_DNS_SEARCH_DOMAINS;
import static org.scion.jpan.Constants.PROPERTY_DNS_SEARCH_DOMAINS;

import java.net.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;

/**
 * This class encapsulates some DNS queries (TXT record request) as well as provides complex queries
 * for determining the search domain and discovery server.
 *
 * <p>Search domain discovery:<br>
 * - Check properties<br>
 * - OS configuration (/etc/resolv.conf, ...)<br>
 * - Reverse lookup<br>
 * -- Try own interface IP via PTR<br>
 * -- Try whoami IPv4 + IPv6 via PTR<br>
 * -- Try subnet lookup via SOA<br>
 * -- If reverse lookup succeeded, iterate through (parent) domains until discovery server is found
 * <br>
 *
 * <p>Discovery Server:<br>
 * - NAPTR records with "A" and "S" flags, IPv4 and IPv6 [RFC 2915]<br>
 * -- NAPTR->A->A/AAAA (address) + TXT record "x-sciondiscovery" (port)<br>
 * -- NAPTR->S->SRV<br>
 * - SRV records, IPv4 and IPv6 [RFC 2782]<br>
 * - DNS SD via PTR records, IPv4 and IPv6 [RFC 6763]<br>
 * -- PTR->SRV <br>
 */
public class DNSHelper {

  private static final Logger LOG = LoggerFactory.getLogger(DNSHelper.class);
  private static final String STR_SRV_TCP_PREFIX = "_sciondiscovery._tcp";
  private static final String STR_TXT_X_SCION = "x-sciondiscovery";
  private static final String STR_TXT_X_SCION_TCP = "x-sciondiscovery:tcp";
  private static final String ERR_PARSING_TXT = "Error parsing TXT entry: ";
  private static final String ERR_PARSING_TXT_LOG = ERR_PARSING_TXT + "{}";
  private static final String ERR_PARSING_TXT_LOG2 = ERR_PARSING_TXT + "{} {}";

  private DNSHelper() {}

  /**
   * Perform a DNS lookup on "hostName" for a TXT entry with key "key". All matching entries are
   * forwarded to the "valueParser" until the "valueParser" returns not "null".
   *
   * @param hostName host name
   * @param key TXT key
   * @param valueParser TXT value parsing function
   * @return The result of "valueParser" or "null" if no matching entry was found or if
   *     "valueParser" returned "null" for all matching entries.
   * @param <R> Result type.
   */
  public static <R> R queryTXT(
      String hostName, String key, Function<String, R> valueParser, Resolver resolver) {
    String nameStr = hostName.endsWith(".") ? hostName : hostName + ".";
    return queryTXT(newName(nameStr), key, valueParser, resolver);
  }

  public static <R> R queryTXT(
      Name name, String key, Function<String, R> valueParser, Resolver resolver) {
    org.xbill.DNS.Record[] records = newLookup(name, Type.TXT, resolver).run();
    if (records == null) {
      return null;
    }
    for (int i = 0; i < records.length; i++) {
      TXTRecord txt = (TXTRecord) records[i];
      String entry = txt.rdataToString();
      if (entry.startsWith("\"" + key + "=")) {
        if (entry.endsWith("\"")) {
          String data = entry.substring(key.length() + 2, entry.length() - 1);
          R result = valueParser.apply(data);
          if (result != null) {
            return result;
          }
        }
        LOG.info(ERR_PARSING_TXT_LOG, entry);
      }
    }
    return null;
  }

  private static InetAddress queryAddress(Name hostName, Resolver resolver) {
    InetAddress result = queryA(hostName, resolver);
    if (result != null) {
      return result;
    }
    return queryAAAA(hostName, resolver);
  }

  private static InetAddress queryA(Name hostName, Resolver resolver) {
    org.xbill.DNS.Record[] recordsA = newLookup(hostName, Type.A, resolver).run();
    if (recordsA == null) {
      return null;
    }
    // just return the first one for now
    return ((ARecord) recordsA[0]).getAddress();
  }

  private static InetAddress queryAAAA(Name hostName, Resolver resolver) {
    org.xbill.DNS.Record[] recordsA = newLookup(hostName, Type.AAAA, resolver).run();
    if (recordsA == null) {
      return null;
    }
    // just return the first one for now
    return ((AAAARecord) recordsA[0]).getAddress();
  }

  public static String searchForDiscoveryService() {
    return IPHelper.toString(searchForDiscoveryService(null));
  }

  static InetSocketAddress searchForDiscoveryService(Resolver resolver) {
    String searchDomains =
        ScionUtil.getPropertyOrEnv(PROPERTY_DNS_SEARCH_DOMAINS, ENV_DNS_SEARCH_DOMAINS);
    if (searchDomains != null) {
      LOG.debug("Discovery service search domain from environment: {}", searchDomains);
      for (String domain : searchDomains.split(";")) {
        try {
          Name domainName = Name.fromString(domain);
          InetSocketAddress discovery = getScionDiscoveryAddress(domainName, resolver);
          if (discovery != null) {
            return discovery;
          }
        } catch (TextParseException e) {
          // ignore exception
          LOG.error("Illegal discovery service search domain in environment: {}", domain, e);
        }
      }
    }

    List<Name> domains = Lookup.getDefaultSearchPath();
    if (domains.isEmpty()) {
      InetSocketAddress discovery = findDiscoveryServiceViaReverseLookup(resolver);
      if (discovery != null) {
        return discovery;
      } else {
        LOG.warn(
            "No DNS search domain found. Please check your /etc/resolv.conf or similar."
                + " You can also specify a domain via {} or {}",
            ENV_DNS_SEARCH_DOMAINS,
            PROPERTY_DNS_SEARCH_DOMAINS);
      }
    }
    for (Name domain : domains) {
      LOG.debug("Checking discovery service domain: {}", domain);
      InetSocketAddress address = getScionDiscoveryAddress(domain, resolver);
      if (address != null) {
        return address;
      }
    }
    return null;
  }

  public static InetSocketAddress getScionDiscoveryAddress(String hostName, Resolver resolver) {
    return getScionDiscoveryAddress(newName(hostName), resolver);
  }

  private static InetSocketAddress getScionDiscoveryAddress(Name hostName, Resolver resolver) {
    InetSocketAddress addr = getScionDiscoveryAddressNAPTR(hostName, resolver);
    if (addr != null) {
      return addr;
    }
    addr = getScionDiscoveryAddressSRV(hostName, resolver);
    if (addr != null) {
      return addr;
    }
    return getScionDiscoveryAddressSD(hostName, resolver);
  }

  private static InetSocketAddress getScionDiscoveryAddressNAPTR(Name hostName, Resolver resolver) {
    org.xbill.DNS.Record[] records = newLookup(hostName, Type.NAPTR, resolver).run();
    if (records == null) {
      LOG.debug("Checking discovery service NAPTR: no records found for {}", hostName);
      return null;
    }

    // Sort by Order (16bit), then by preference (16bit)
    Arrays.sort(
        records,
        Comparator.comparingLong(
            r -> ((NAPTRRecord) r).getOrder() * 0xFFFFL + ((NAPTRRecord) r).getPreference()));

    for (int i = 0; i < records.length; i++) {
      NAPTRRecord nr = (NAPTRRecord) records[i];
      String naptrService = nr.getService();
      if (STR_TXT_X_SCION_TCP.equals(naptrService)) {
        String naptrFlag = nr.getFlags();
        if ("A".equals(naptrFlag)) {
          int port = getScionDiscoveryPort(hostName, resolver);
          InetAddress address = queryAddress(nr.getReplacement(), resolver);
          if (address != null) {
            return new InetSocketAddress(address, port);
          }
        }
        if ("S".equals(naptrFlag)) {
          InetSocketAddress address = getScionDiscoveryAddressSRV(nr.getReplacement(), resolver);
          if (address != null) {
            return address;
          }
        }
        LOG.info("Unknown NAPTR flag: {}", naptrFlag);
      }
    }
    return null;
  }

  private static InetSocketAddress getScionDiscoveryAddressSRV(Name domain, Resolver resolver) {
    // dig +short SRV _sciondiscovery._tcp.ethz.ch
    Name domainSRV = newName(STR_SRV_TCP_PREFIX, domain);
    org.xbill.DNS.Record[] records = newLookup(domainSRV, Type.SRV, resolver).run();
    if (records == null) {
      LOG.debug("Checking discovery service SRV: no records found for {}", domainSRV);
      return null;
    }

    SRVRecord[] srcRecords = orderSrvByWeight(records);

    for (int i = 0; i < srcRecords.length; i++) {
      SRVRecord nr = srcRecords[i];
      InetAddress address = queryAddress(nr.getTarget(), resolver);
      if (address != null) {
        return new InetSocketAddress(address, nr.getPort());
      }
    }
    return null;
  }

  private static SRVRecord[] orderSrvByWeight(org.xbill.DNS.Record[] in) {
    SRVRecord[] records =
        Arrays.stream(in)
            .map(r -> (SRVRecord) r)
            .collect(Collectors.toList())
            .toArray(new SRVRecord[in.length]);

    // sort by priority and weight
    // Ordering by weight is useful for all 0-weight entries
    Arrays.sort(records, Comparator.comparingLong(r -> r.getPriority() * 0xFFFFL + r.getWeight()));

    // Sort by weight -> from https://www.rfc-editor.org/rfc/rfc2782.html
    //    To select a target to be contacted next, arrange all SRV RRs
    //            (that have not been ordered yet) in any order, except that all
    //    those with weight 0 are placed at the beginning of the list.
    //
    //    Compute the sum of the weights of those RRs, and with each RR
    //    associate the running sum in the selected order. Then choose a
    //    uniform random number between 0 and the sum computed
    //    (inclusive), and select the RR whose running sum value is the
    //    first in the selected order which is greater than or equal to
    //    the random number selected. The target host specified in the
    //    selected SRV RR is the next one to be contacted by the client.
    //            Remove this SRV RR from the set of the unordered SRV RRs and
    //    apply the described algorithm to the unordered SRV RRs to select
    //    the next target host.  Continue the ordering process until there
    //    are no unordered SRV RRs.  This process is repeated for each
    //    Priority.

    for (int i = 0; i < records.length - 1; i++) {
      SRVRecord sr0 = records[i];
      SRVRecord sr1 = records[i + 1];
      if (sr0.getPriority() == sr1.getPriority() && sr0.getPriority() > 0) {
        // Order by weight.
        // Find last applicable record
        int posLast = i + 1;
        while (posLast < records.length - 1
            && sr0.getPriority() == records[posLast + 1].getPriority()) {
          posLast++;
        }

        // Randomize
        Random rnd = new Random(System.currentTimeMillis());
        int[] weights = new int[posLast - i + 1];
        while (i < posLast) {
          // Sum of weights
          int sumOfWeights = 0;
          for (int j = i; j <= posLast; j++) {
            sumOfWeights += records[i].getWeight();
            weights[j] = sumOfWeights;
          }

          // Select entry
          int split = rnd.nextInt(sumOfWeights + 1);
          int posSplit = Arrays.binarySearch(weights, split);
          if (posSplit < 0) {
            posSplit = -posSplit - 1;
          }

          // swap - works also for i==posSplit
          SRVRecord tmp = records[i];
          records[i] = records[posSplit];
          records[posSplit] = tmp;

          i++;
        }
      }
    }

    return records;
  }

  /**
   * Use DNS SD [RFC 6763] to discover the discovery service. It uses PTR -> SRV -> A/AAAA record.
   *
   * @param domain Domain name
   * @param resolver Resolver instance. Can be "null".
   * @return Discovery service address or null.
   */
  private static InetSocketAddress getScionDiscoveryAddressSD(Name domain, Resolver resolver) {
    Name domainSD = newName(STR_SRV_TCP_PREFIX, domain);
    org.xbill.DNS.Record[] records = newLookup(domainSD, Type.PTR, resolver).run();
    if (records == null) {
      LOG.debug("Checking discovery service SD/PTR: no records found for {}", domain);
      return null;
    }

    for (int i = 0; i < records.length; i++) {
      PTRRecord pr = (PTRRecord) records[i];
      InetSocketAddress address = getScionDiscoveryAddressSRV(pr.getTarget(), resolver);
      if (address != null) {
        return address;
      }
    }
    return null;
  }

  private static int getScionDiscoveryPort(Name hostName, Resolver resolver) {
    final Integer INVALID = -1;
    Integer discoveryPort =
        DNSHelper.queryTXT(
            hostName,
            STR_TXT_X_SCION,
            txtEntry -> {
              try {
                int port = Integer.parseInt(txtEntry);
                if (port < 0 || port > 65536) {
                  LOG.info(ERR_PARSING_TXT_LOG, txtEntry);
                  return INVALID;
                }
                return port;
              } catch (NumberFormatException e) {
                LOG.info(ERR_PARSING_TXT_LOG2, txtEntry, e.getMessage());
                return INVALID;
              }
            },
            resolver);
    if (INVALID.equals(discoveryPort)) {
      throw new ScionRuntimeException(
          "Could not find valid TXT " + STR_TXT_X_SCION + " record for host: " + hostName);
    }
    if (discoveryPort == null) {
      LOG.debug("Could not find valid TXT " + STR_TXT_X_SCION + " record for host: {}", hostName);
      return Constants.DISCOVERY_DEFAULT_PORT;
    }
    return discoveryPort;
  }

  private static InetSocketAddress findDiscoveryServiceViaReverseLookup(Resolver resolver) {
    // Idea:
    // We call whoami to get our (external) IP, then reverse lookup the IP to get our
    // external domain. We then strip subdomains from the domain until we get one that
    // gives us a usable NAPTR record.
    // - dig +short A whoami.akamai.net @zh.akamaitech.net
    // - dig -x 129.132.0.0
    // - OR:   dig TXT whoami.ds.akahelp.net @dns.google.com

    // Reverse lookup public interface IPs
    for (InetAddress externalIp : IPHelper.getInterfaceIPs()) {
      if (!externalIp.isSiteLocalAddress()) {
        InetSocketAddress discovery = findDiscoveryServiceViaPTRLookup(externalIp, resolver);
        if (discovery != null) {
          return discovery;
        }
      }
    }

    Name reverseLookupHost = newName("whoami.akamai.net");
    InetSocketAddress discovery = reverseLookupIPv4(reverseLookupHost, resolver);
    if (discovery == null) {
      discovery = reverseLookupIPv6(reverseLookupHost, resolver);
    }
    if (discovery == null) {
      // We do this last because subnets may be large and have unrelated search domains.
      discovery = reverseLookupSubnet(resolver);
    }
    return discovery;
  }

  private static Resolver getWhoamiResolver(Resolver resolver) {
    if (resolver == null) {
      // We can use a custom resolver for Unit tests.
      try {
        return new SimpleResolver("zh.akamaitech.net");
      } catch (UnknownHostException e) {
        throw new ScionRuntimeException(e);
      }
    }
    return resolver;
  }

  private static InetSocketAddress reverseLookupIPv4(Name reverseLookupHost, Resolver resolver) {
    // IPv4 reverse lookup
    Lookup lookup4 = newLookup(reverseLookupHost, Type.A, getWhoamiResolver(resolver));
    org.xbill.DNS.Record[] records4 = lookup4.run();
    if (records4 != null) {
      for (org.xbill.DNS.Record record4 : records4) {
        ARecord aRecord = (ARecord) record4;
        InetAddress localAddress = aRecord.getAddress();
        if (!localAddress.isSiteLocalAddress()) {
          InetSocketAddress discovery = findDiscoveryServiceViaPTRLookup(localAddress, resolver);
          if (discovery != null) {
            return discovery;
          }
        }
      }
    }
    return null;
  }

  private static InetSocketAddress reverseLookupIPv6(Name reverseLookupHost, Resolver resolver) {
    // IPv6 reverse lookup
    Lookup lookup6 = newLookup(reverseLookupHost, Type.AAAA, getWhoamiResolver(resolver));
    org.xbill.DNS.Record[] records6 = lookup6.run();
    if (records6 != null) {
      for (org.xbill.DNS.Record record6 : records6) {
        AAAARecord aRecord = (AAAARecord) record6;
        InetAddress localAddress = aRecord.getAddress();
        if (!localAddress.isSiteLocalAddress()) {
          InetSocketAddress discovery = findDiscoveryServiceViaPTRLookup(localAddress, resolver);
          if (discovery != null) {
            return discovery;
          }
        }
      }
    }
    return null;
  }

  private static InetSocketAddress reverseLookupSubnet(Resolver resolver) {
    // Try reverse lookup on all subnets.
    for (InetAddress subnet : IPHelper.getSubnets()) {
      if (!subnet.isSiteLocalAddress()) {
        InetSocketAddress discovery = findDiscoveryServiceViaSOALookup(subnet, resolver);
        if (discovery != null) {
          return discovery;
        }
      }
    }
    return null;
  }

  private static InetSocketAddress findDiscoveryServiceViaPTRLookup(
      InetAddress address, Resolver resolver) {
    Name name = newName(reverseAddressForARPA(address));
    org.xbill.DNS.Record[] records = newLookup(name, Type.PTR, resolver).run();
    if (records == null) {
      return null;
    }
    for (org.xbill.DNS.Record record2 : records) {
      PTRRecord ptrRecord = (PTRRecord) record2;
      InetSocketAddress discovery = iterateSearchDomain(ptrRecord.getTarget(), resolver);
      if (discovery != null) {
        return discovery;
      }
    }
    return null;
  }

  private static InetSocketAddress findDiscoveryServiceViaSOALookup(
      InetAddress address, Resolver resolver) {
    Name name = newName(reverseAddressForARPA(address));
    // Strip leading zeros
    if (address instanceof Inet4Address) {
      while (name.toString().startsWith("0.")) {
        name = newName(name.toString().substring(2));
      }
    }
    org.xbill.DNS.Record[] records = newLookup(name, Type.SOA, resolver).run();
    if (records == null) {
      return null;
    }
    for (org.xbill.DNS.Record record2 : records) {
      SOARecord soaRecord = (SOARecord) record2;
      InetSocketAddress discovery = iterateSearchDomain(soaRecord.getHost(), resolver);
      if (discovery != null) {
        return discovery;
      }
    }
    return null;
  }

  private static InetSocketAddress iterateSearchDomain(Name domain, Resolver resolver) {
    // Iterate through all elements in the domain to find the actual search domain
    while (true) {
      InetSocketAddress discovery = getScionDiscoveryAddress(domain, resolver);
      if (discovery != null) {
        return discovery;
      }

      // Recursively strip subdomains
      String domStr = domain.toString(false);
      int pos = domStr.indexOf('.');
      if (pos <= 0 || pos == domStr.length() - 1) {
        break;
      }
      domain = newName(domStr.substring(pos + 1));
    }
    return null;
  }

  static String reverseAddressForARPA(InetAddress address) {
    StringBuilder sb = new StringBuilder();
    if (address instanceof Inet4Address) {
      byte[] ba = address.getAddress();
      for (int i = 0; i < ba.length; i++) {
        sb.append(ByteUtil.toUnsigned(ba[ba.length - i - 1])).append(".");
      }
      sb.append("in-addr.arpa.");
    } else {
      byte[] ba = address.getAddress();
      for (int i = 0; i < ba.length; i++) {
        int b = ByteUtil.toUnsigned(ba[ba.length - i - 1]);
        int b0 = b >> 4;
        int b1 = b & 0xf;
        sb.append(Integer.toHexString(b1)).append(".");
        sb.append(Integer.toHexString(b0)).append(".");
      }
      sb.append("ip6.arpa.");
    }
    return sb.toString();
  }

  private static Lookup newLookup(Name name, int type, Resolver resolver) {
    Lookup lookup = new Lookup(name, type);
    // Avoid parsing /etc/hosts because this would print a WARNING, see
    // https://github.com/dnsjava/dnsjava/issues/361
    lookup.setHostsFileParser(null);
    if (resolver != null) {
      lookup.setResolver(resolver);
    }
    return lookup;
  }

  private static Name newName(String str) {
    return newName(str, null);
  }

  private static Name newName(String str, Name domain) {
    try {
      return Name.fromString(str, domain);
    } catch (TextParseException e) {
      LOG.error("Error parsing domain string: {} + {}", str, domain, e);
      throw new ScionRuntimeException("Error parsing domain string: " + str + " + " + domain, e);
    }
  }
}
