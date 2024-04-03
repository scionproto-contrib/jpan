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
import org.scion.ScionRuntimeException;
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

  private static final String STR_X_SCION = "x-sciondiscovery";
  private static final String STR_X_SCION_TCP = "x-sciondiscovery:tcp";

  public static String queryTXT(String hostName, String key) {
    try {
      Record[] records = new Lookup(hostName, Type.TXT).run();
      if (records == null) {
        return null;
      }
      for (int i = 0; i < records.length; i++) {
        TXTRecord txt = (TXTRecord) records[i];
        String entry = txt.rdataToString();
        if (entry.startsWith("\"" + key)) {
          if (!entry.endsWith("\"") || entry.length() < key.length() + 3) {
            throw new ScionRuntimeException("Error parsing TXT entry: " + entry);
          }
          return entry.substring(key.length() + 2, entry.length() - 1);
        }
      }
    } catch (TextParseException e) {
      throw new ScionRuntimeException("Error parsing TXT entry: " + e.getMessage());
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
    String txtEntry = DNSHelper.queryTXT(hostName, STR_X_SCION);
    if (txtEntry == null) {
      throw new ScionRuntimeException(
          "Could not find bootstrap TXT port record for host: " + hostName);
    }
    int port = Integer.parseInt(txtEntry);
    if (port < 0 || port > 65536) {
      throw new ScionRuntimeException("Error parsing TXT entry: " + txtEntry);
    }
    return port;
  }
}
