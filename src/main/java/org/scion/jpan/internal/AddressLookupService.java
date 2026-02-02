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

import org.scion.jpan.*;
import org.scion.jpan.internal.bootstrap.DNSHelper;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.internal.util.SimpleCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The AddressService looks up IP addresses provides information such as: <br>
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
public class AddressLookupService {

  private static final Logger LOG = LoggerFactory.getLogger(AddressLookupService.class.getName());

  private static final String DNS_TXT_KEY = "scion";
  private static final String ERR_INVALID_TXT = "Invalid TXT entry: ";
  private static final String ERR_INVALID_TXT_LOG = ERR_INVALID_TXT + "{}";
  private static final String ERR_INVALID_TXT_LOG2 = ERR_INVALID_TXT + "{} {}";

  private static final HostsFileParser hostsFile = new HostsFileParser();
  private static final SimpleCache<String, ScionAddress> scionAddressCache = new SimpleCache<>(100);

  private AddressLookupService() {}

  /**
   * @param hostName hostName of the host to resolve
   * @return The ISD/AS code for a hostname
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public static long getIsdAs(String hostName, long localIsdAs) throws ScionException {
    ScionAddress scionAddress = checkCache(hostName);
    if (scionAddress != null) {
      return scionAddress.getIsdAs();
    }

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
    HostsFileParser.HostEntry entry = findHostname(hostName);
    if (entry != null) {
      return entry.getIsdAs();
    }

    // Use local ISD/AS for localhost addresses
    if (IPHelper.isLocalhost(hostName)) {
      return localIsdAs;
    }

    // DNS lookup
    Long fromDNS =
        DNSHelper.queryTXT(hostName, DNS_TXT_KEY, AddressLookupService::parseTxtRecordToIA, null);
    if (fromDNS != null) {
      return fromDNS;
    }

    throw new ScionException("No DNS TXT entry \"scion\" found for host: " + hostName);
  }

  /**
   * Uses DNS and hostfiles to look up a SCION enabled IP address for a give host string.
   *
   * @param hostName hostName of the host to resolve
   * @return A ScionAddress
   * @throws ScionException if the DNS/TXT lookup did not return a (valid) SCION address.
   */
  public static ScionAddress lookupAddress(String hostName, long localIsdAs) throws ScionException {
    ScionAddress scionAddress = checkCache(hostName);
    if (scionAddress != null) {
      return scionAddress;
    }

    // Look for TXT in application properties
    String txtFromProperties = findTxtRecordInProperties(hostName);
    if (txtFromProperties != null) {
      ScionAddress address = parseTxtRecord(txtFromProperties, hostName);
      if (address == null) {
        throw new ScionException(ERR_INVALID_TXT + txtFromProperties);
      }
      return address;
    }

    // Check /etc/scion/hosts
    HostsFileParser.HostEntry entry = findHostname(hostName);
    if (entry != null) {
      return ScionAddress.create(entry.getIsdAs(), entry.getAddress());
    }

    // Use local ISD/AS for localhost addresses
    byte[] localBytes = IPHelper.lookupLocalhost(hostName);
    if (localBytes != null) {
      return ScionAddress.create(localIsdAs, hostName, localBytes);
    }

    // DNS lookup
    ScionAddress fromDNS =
        DNSHelper.queryTXT(hostName, DNS_TXT_KEY, x -> parseTxtRecord(x, hostName), null);
    if (fromDNS != null) {
      return addToCache(fromDNS);
    }

    throw new ScionException("No DNS TXT entry \"scion\" found for host: " + hostName);
  }

  private static ScionAddress addToCache(ScionAddress address) {
    synchronized (scionAddressCache) {
      scionAddressCache.put(address.getHostName(), address);
      scionAddressCache.put(address.getInetAddress().getHostAddress(), address);
      return address;
    }
  }

  private static ScionAddress checkCache(String hostName) {
    synchronized (scionAddressCache) {
      return scionAddressCache.get(hostName);
    }
  }

  private static HostsFileParser.HostEntry findHostname(String hostName) {
    synchronized (hostsFile) {
      return hostsFile.find(hostName);
    }
  }

  public static void refresh() {
    synchronized (hostsFile) {
      hostsFile.refresh();
    }
    synchronized (scionAddressCache) {
      scionAddressCache.clear();
    }
  }

  private static String findTxtRecordInProperties(String hostName) throws ScionException {
    String props = System.getProperty(HeaderConstants.DEBUG_PROPERTY_MOCK_DNS_TXT);
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

  private static ScionAddress parseTxtRecord(String txtEntry, String hostName) {
    // dnsEntry example: "scion=64-2:0:9,129.x.x.x"
    int posComma = txtEntry.indexOf(',');
    if (posComma < 0) {
      LOG.info(ERR_INVALID_TXT_LOG, txtEntry);
      return null;
    }
    try {
      long isdAs = ScionUtil.parseIA(txtEntry.substring(0, posComma));
      byte[] bytes = IPHelper.toByteArray(txtEntry.substring(posComma + 1));
      return ScionAddress.create(isdAs, hostName, bytes);
    } catch (IllegalArgumentException e) {
      LOG.info(ERR_INVALID_TXT_LOG2, txtEntry, e.getMessage());
      return null;
    }
  }

  private static Long parseTxtRecordToIA(String txtEntry) {
    // dnsEntry example: "scion=64-2:0:9,129.x.x.x"
    int posComma = txtEntry.indexOf(',');
    if (posComma < 0) {
      LOG.info(ERR_INVALID_TXT_LOG, txtEntry);
      return null;
    }
    return ScionUtil.parseIA(txtEntry.substring(0, posComma));
  }
}
