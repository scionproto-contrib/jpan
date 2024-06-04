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

import java.io.IOException;
import java.net.InetAddress;
import java.util.function.Function;
import org.scion.jpan.ScionRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.NAPTRRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

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
    Record[] records = new Lookup(name, Type.TXT).run();
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
    Record[] recordsA = new Lookup(hostName, Type.A).run();
    if (recordsA == null) {
      throw new ScionRuntimeException("No DNS A entry found for host: " + hostName);
    }
    // just return the first one for now
    return ((ARecord) recordsA[0]).getAddress();
  }

  public static InetAddress queryAAAA(Name hostName) {
    Record[] recordsA = new Lookup(hostName, Type.AAAA).run();
    if (recordsA == null) {
      throw new ScionRuntimeException("No DNS AAAA entry found for host: " + hostName);
    }
    // just return the first one for now
    return ((AAAARecord) recordsA[0]).getAddress();
  }

  public static String searchForDiscoveryService() {
    for (Name domain : Lookup.getDefaultSearchPath()) {
      LOG.debug("Checking discovery service domain: {}", domain);
      String a = getScionDiscoveryAddress(domain);
      if (a != null) {
        return a;
      }
    }
    return null;
  }

  public static String getScionDiscoveryAddress(String hostName) throws IOException {
    return getScionDiscoveryAddress(Name.fromString(hostName));
  }

  private static String getScionDiscoveryAddress(Name hostName) {
    Record[] records = new Lookup(hostName, Type.NAPTR).run();
    if (records == null) {
      LOG.debug("Checking discovery service NAPTR: no records found");
      return null;
    }

    for (int i = 0; i < records.length; i++) {
      NAPTRRecord nr = (NAPTRRecord) records[i];
      String naptrService = nr.getService();
      LOG.debug("Checking discovery service NAPTR: {}", naptrService);
      if (STR_X_SCION_TCP.equals(naptrService)) {
        String naptrFlag = nr.getFlags();
        LOG.debug("Checking discovery service NAPTR flag: {}", naptrFlag);
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
}
