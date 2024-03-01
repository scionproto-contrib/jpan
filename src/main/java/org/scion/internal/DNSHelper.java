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

import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

public class DNSHelper {

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
}
