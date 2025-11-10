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

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.function.Function;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;

public class DNSHelper {

  private static final Logger LOG = LoggerFactory.getLogger(DNSHelper.class);
  private static final String STR_X_SCION = "x-sciondiscovery";
  private static final String STR_X_SCION_TCP = "x-sciondiscovery:tcp";
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
  public static <R> R queryTXT(String hostName, String key, Function<String, R> valueParser) {
    String nameStr = hostName.endsWith(".") ? hostName : hostName + ".";
    try {
      return queryTXT(Name.fromString(nameStr), key, valueParser);
    } catch (TextParseException e) {
      LOG.info(ERR_PARSING_TXT_LOG, e.getMessage());
    }
    return null;
  }

  public static <R> R queryTXT(Name name, String key, Function<String, R> valueParser) {
    org.xbill.DNS.Record[] records = newLookup(name, Type.TXT).run();
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

  public static InetAddress queryA(Name hostName) {
    org.xbill.DNS.Record[] recordsA = newLookup(hostName, Type.A).run();
    if (recordsA == null) {
      throw new ScionRuntimeException("No DNS A entry found for host: " + hostName);
    }
    // just return the first one for now
    return ((ARecord) recordsA[0]).getAddress();
  }

  public static InetAddress queryAAAA(Name hostName) {
    org.xbill.DNS.Record[] recordsA = newLookup(hostName, Type.AAAA).run();
    if (recordsA == null) {
      throw new ScionRuntimeException("No DNS AAAA entry found for host: " + hostName);
    }
    // just return the first one for now
    return ((AAAARecord) recordsA[0]).getAddress();
  }

  public static String searchForDiscoveryService() {
    String searchDomains =
        ScionUtil.getPropertyOrEnv(PROPERTY_DNS_SEARCH_DOMAINS, ENV_DNS_SEARCH_DOMAINS);
    if (searchDomains != null) {
      for (String domain : searchDomains.split(";")) {
        LOG.debug(
            "Checking discovery service domain from environment variable/property: {}", domain);
        try {
          String a = getScionDiscoveryAddress(Name.fromString(domain));
          if (a != null) {
            return a;
          }
        } catch (TextParseException e) {
          throw new ScionRuntimeException(e);
        }
      }
    }

    List<Name> domains = Lookup.getDefaultSearchPath();
    if (domains.isEmpty()) {
      Name domain = findSearchDomainViaReverseLookup();
      if (domain != null) {
        domains.add(domain);
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
      String address = getScionDiscoveryAddress(domain);
      if (address != null) {
        return address;
      }
    }
    return null;
  }

  public static String getScionDiscoveryAddress(String hostName) throws IOException {
    return getScionDiscoveryAddress(Name.fromString(hostName));
  }

  private static String getScionDiscoveryAddress(Name hostName) {
    org.xbill.DNS.Record[] records = newLookup(hostName, Type.NAPTR).run();
    if (records == null) {
      LOG.debug("Checking discovery service NAPTR: no records found");
      return null;
    }

    for (int i = 0; i < records.length; i++) {
      NAPTRRecord nr = (NAPTRRecord) records[i];
      String naptrService = nr.getService();
      if (STR_X_SCION_TCP.equals(naptrService)) {
        String naptrFlag = nr.getFlags();
        int port = getScionDiscoveryPort(hostName);
        if ("A".equals(naptrFlag)) {
          InetAddress addr = DNSHelper.queryA(nr.getReplacement());
          return addr.getHostAddress() + ":" + port;
        }
        if ("AAAA".equals(naptrFlag)) {
          InetAddress addr = DNSHelper.queryAAAA(nr.getReplacement());
          return "[" + addr.getHostAddress() + "]:" + port;
        } // keep going and collect more hints
      }
    }
    return null;
  }

  private static int getScionDiscoveryPort(Name hostName) {
    Integer discoveryPort =
        DNSHelper.queryTXT(
            hostName,
            STR_X_SCION,
            txtEntry -> {
              try {
                int port = Integer.parseInt(txtEntry);
                if (port < 0 || port > 65536) {
                  LOG.info(ERR_PARSING_TXT_LOG, txtEntry);
                  return null;
                }
                return port;
              } catch (NumberFormatException e) {
                LOG.info(ERR_PARSING_TXT_LOG2, txtEntry, e.getMessage());
                return null;
              }
            });
    if (discoveryPort == null) {
      throw new ScionRuntimeException(
          "Could not find valid TXT " + STR_X_SCION + " record for host: " + hostName);
    }
    return discoveryPort;
  }

  static Name findSearchDomainViaReverseLookup() {
    // Idea:
    // We call whoami to get our (external) IP, then reverse lookup the IP to get our
    // external domain. We then strip subdomains from the domain until we get one that
    // gives us a usable NAPTR record.
    // - dig +short A whoami.akamai.net @zh.akamaitech.net
    // - dig -x 129.132.0.0
    // - OR:   dig TXT whoami.ds.akahelp.net @dns.google.com

    try {
      // Reverse lookup public interface IPs
      for (InetAddress externalIp : IPHelper.getInterfaceIPs()) {
        if (!externalIp.isSiteLocalAddress()) {
          Name domain = findSearchDomainViaPTRLookup(externalIp);
          if (domain != null) {
            return domain;
          }
        }
      }

      Name reverseLookupHost = Name.fromString("whoami.akamai.net");
      Resolver resolver = new SimpleResolver("zh.akamaitech.net");

      // IPv4 reverse lookup
      Lookup lookup4 = newLookup(reverseLookupHost, Type.A);
      lookup4.setResolver(resolver);
      org.xbill.DNS.Record[] records4 = lookup4.run();
      for (org.xbill.DNS.Record record4 : records4) {
        ARecord aRecord = (ARecord) record4;
        InetAddress localAddress = aRecord.getAddress();
        if (!localAddress.isSiteLocalAddress()) {
          Name domain = findSearchDomainViaPTRLookup(localAddress);
          if (domain != null) {
            return domain;
          }
        }
      }

      // IPv6 reverse lookup
      Lookup lookup6 = newLookup(reverseLookupHost, Type.AAAA);
      lookup6.setResolver(resolver);
      org.xbill.DNS.Record[] records6 = lookup6.run();
      for (org.xbill.DNS.Record record6 : records6) {
        AAAARecord aRecord = (AAAARecord) record6;
        InetAddress localAddress = aRecord.getAddress();
        if (!localAddress.isSiteLocalAddress()) {
          Name domain = findSearchDomainViaPTRLookup(localAddress);
          if (domain != null) {
            return domain;
          }
        }
      }

      // Try reverse lookup on all subnets
      for (InetAddress subnet : IPHelper.getSubnets()) {
        if (!subnet.isSiteLocalAddress()) {
          Name domain = findSearchDomainViaSOALookup(subnet);
          if (domain != null) {
            return domain;
          }
        }
      }

    } catch (TextParseException | UnknownHostException e) {
      throw new ScionRuntimeException(e);
    }
    return null;
  }

  private static Name findSearchDomainViaPTRLookup(InetAddress address)
          throws TextParseException {
    Name name = Name.fromString(reverseAddressForARPA(address));
    org.xbill.DNS.Record[] records = newLookup(name, Type.PTR).run();
    if (records == null) {
      return null;
    }
    for (org.xbill.DNS.Record record2 : records) {
      PTRRecord ptrRecord = (PTRRecord) record2;
      Name domain = ptrRecord.getTarget();
      while (true) {
        if (newLookup(domain, Type.NAPTR).run() != null) {
          return domain;
        }

        // Recursively strip subdomains
        String domStr = domain.toString(false);
        int pos = domStr.indexOf('.');
        if (pos <= 0 || pos == domStr.length() - 1) {
          break;
        }
        domain = Name.fromString(domStr.substring(pos + 1));
      }
    }
    return null;
  }

  static Name findSearchDomainViaSOALookup(InetAddress address)
          throws TextParseException {
    Name name = Name.fromString(reverseAddressForARPA(address));
    // TODO ipv6
    while (name.toString().startsWith("0.")) {
      name = Name.fromString(name.toString().substring(2));
    }
    // System.out.println("SOA name: " + name);
    org.xbill.DNS.Record[] records = newLookup(name, Type.SOA).run();
    if (records == null) {
      return null;
    }
    for (org.xbill.DNS.Record record2 : records) {
      SOARecord soaRecord = (SOARecord) record2;
      System.out.println("SOA: host=" + soaRecord.getHost() + " name=" + soaRecord.getName() + "  admin=" + soaRecord.getAdmin());
      Name domain = soaRecord.getHost();
      while (true) {
        System.out.println("SOA: NAPTR lookup on: " + domain);
        if (newLookup(domain, Type.NAPTR).run() != null) {
          return domain;
        }

        // Recursively strip subdomains
        String domStr = domain.toString(false);
        int pos = domStr.indexOf('.');
        if (pos <= 0 || pos == domStr.length() - 1) {
          break;
        }
        domain = Name.fromString(domStr.substring(pos + 1));
      }
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

  private static Lookup newLookup(Name name, int type) {
    Lookup lookup = new Lookup(name, type);
    // Avoid parsing /etc/hosts because this would print a WARNING, see
    // https://github.com/dnsjava/dnsjava/issues/361
    lookup.setHostsFileParser(null);
    return lookup;
  }
}
