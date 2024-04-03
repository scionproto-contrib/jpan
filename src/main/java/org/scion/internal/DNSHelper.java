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

package org.scion.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.util.function.Function;
import org.scion.ScionRuntimeException;
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

    try {
      Record[] records = new Lookup(hostName, Type.TXT).run();
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
    } catch (TextParseException e) {
      LOG.info(ERR_PARSING_TXT_LOG, e.getMessage());
    }
    return null;
  }

  public static InetAddress queryA(String hostName) throws IOException {
    Record[] recordsA = new Lookup(hostName, Type.A).run();
    if (recordsA == null) {
      throw new ScionRuntimeException("No DNS A entry found for host: " + hostName);
    }
    for (int i = 0; i < recordsA.length; i++) {
      ARecord ar = (ARecord) recordsA[i];
      // TODO just return the first one for now
      return ar.getAddress();
    }
    return null;
  }

  public static InetAddress queryAAAA(String hostName) throws IOException {
    Record[] recordsA = new Lookup(hostName, Type.AAAA).run();
    if (recordsA == null) {
      throw new ScionRuntimeException("No DNS AAAA entry found for host: " + hostName);
    }
    for (int i = 0; i < recordsA.length; i++) {
      AAAARecord ar = (AAAARecord) recordsA[i];
      // TODO just return the first one for now
      return ar.getAddress();
    }
    return null;
  }

  public static String searchForDiscoveryService() {
    try {
      for (Name n : Lookup.getDefaultSearchPath()) {
        String a = getScionDiscoveryAddress(n.toString());
        if (a != null) {
          return a;
        }
      }
      return null;
    } catch (IOException e) {
      throw new ScionRuntimeException("Error looking up NAPTR records from OS DNS search paths", e);
    }
  }

  public static String getScionDiscoveryAddress(String hostName) throws IOException {
    Record[] records = new Lookup(hostName, Type.NAPTR).run();
    if (records == null) {
      return null;
    }

    for (int i = 0; i < records.length; i++) {
      NAPTRRecord nr = (NAPTRRecord) records[i];
      String naptrService = nr.getService();
      if (STR_X_SCION_TCP.equals(naptrService)) {
        String host = nr.getReplacement().toString();
        String naptrFlag = nr.getFlags();
        int port = getScionDiscoveryPort(hostName);
        if ("A".equals(naptrFlag)) {
          InetAddress addr = DNSHelper.queryA(host);
          return addr.getHostAddress() + ":" + port;
        }
        if ("AAAA".equals(naptrFlag)) {
          InetAddress addr = DNSHelper.queryAAAA(host);
          return "[" + addr.getHostAddress() + "]:" + port;
        } // keep going and collect more hints
      }
    }
    return null;
  }

  private static int getScionDiscoveryPort(String hostName) {
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
