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

package org.scion.jpan.testutil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.xbill.DNS.*;

public class DNSUtil {

  public static InetAddress installAddress(String asHost, byte[] addrBytes) {
    try {
      Name name = Name.fromString(asHost + ".");
      Cache c = Lookup.getDefaultCache(DClass.IN);
      if (addrBytes.length == 4) {
        ARecord a = new ARecord(name, DClass.IN, 5000, InetAddress.getByAddress(addrBytes));
        c.addRecord(a, 10);
        // remove trailing top-level `.`
        return InetAddress.getByAddress(asHost, a.getAddress().getAddress());
      } else if (addrBytes.length == 16) {
        AAAARecord a = new AAAARecord(name, DClass.IN, 5000, InetAddress.getByAddress(addrBytes));
        c.addRecord(a, 10);
        // remove trailing top-level `.`
        return InetAddress.getByAddress(asHost, a.getAddress().getAddress());
      } else {
        throw new IllegalArgumentException();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void bootstrapNAPTR(String asHost, byte[] topoAddr, int topoPort) {
    bootstrapNAPTR(asHost, topoAddr, "x-sciondiscovery=" + topoPort, "x-sciondiscovery:tcp");
  }

  public static void bootstrapNAPTR(
      String asHost, byte[] topoAddr, String txtStr, String naptrKey) {
    installTXT(asHost, txtStr);
    installAddress("topohost.x.y", topoAddr);
    installNAPTR(asHost, "A", naptrKey, "topohost.x.y.", 1, 1);
  }

  public static void installNAPTR_S(String searchDomain, String replacement) {
    installNAPTR(searchDomain, "S", "x-sciondiscovery:tcp", replacement, 1, 1);
  }

  /**
   * Install NAPTR Record with "A" or "S" flag.
   *
   * @param asHost e.g. dns.mydomain.com
   * @param flag "A" or "S"
   * @param naptrKey e.g. "x-sciondiscovery:tcp"
   * @param replacementStr e.g. discovery.mydomain.com
   * @param order order
   * @param preference preference
   */
  public static void installNAPTR(
      String asHost,
      String flag,
      String naptrKey,
      String replacementStr,
      int order,
      int preference) {
    try {
      Name name = Name.fromString(asHost + ".");
      Name replacement = new Name(replacementStr);

      NAPTRRecord nr =
          new NAPTRRecord(
              name, DClass.IN, 5000, order, preference, flag, naptrKey, "", replacement);
      Cache c = Lookup.getDefaultCache(DClass.IN);
      c.addRecord(nr, 10);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void installTXT(InetSocketAddress address, String isdAs) {
    String name = address.getAddress().getHostName();
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException();
    }
    installScionTXT(name, isdAs, address.getAddress().getHostAddress());
  }

  public static void installScionTXT(String asHost, String isdAs, String ipAddress) {
    installTXT(asHost, "scion=" + isdAs + "," + ipAddress);
  }

  public static void installTXT(String asHost, String entry) {
    try {
      Name name = Name.fromString(asHost + ".");
      Cache c = Lookup.getDefaultCache(DClass.IN);
      TXTRecord txt = new TXTRecord(name, DClass.IN, 5000, entry);
      c.addRecord(txt, 10);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void clear() {
    Lookup.setDefaultCache(new Cache(), DClass.IN);
  }

  private static Name toName(String hostName) {
    try {
      if (hostName.endsWith(".")) {
        return Name.fromString(hostName);
      }
      return Name.fromString(hostName + ".");
    } catch (TextParseException e) {
      throw new RuntimeException(e);
    }
  }

  public static void installPTR(String key, String value) {
    // Example:
    // key = "4.3.2.1.in-addr.arpa.
    // value = "my-dhcp-192-168-0-42.my.domain.org"
    try {
      String[] results = value.split("\\.");
      ByteBuffer bb = ByteBuffer.allocate(1000);
      for (String r : results) {
        bb.put((byte) r.length());
        bb.put(r.getBytes());
      }
      bb.put((byte) 0);
      byte[] data = new byte[bb.position()];
      bb.flip();
      bb.get(data);
      Name name = Name.fromString(key);
      org.xbill.DNS.Record ptrRecord = PTRRecord.newRecord(name, Type.PTR, DClass.IN, 3600, data);
      Lookup.getDefaultCache(DClass.IN).addRecord(ptrRecord, 10);
    } catch (TextParseException e) {
      throw new RuntimeException(e);
    }
  }

  public static void installSOA(String key, String host) {
    // Example:
    // key = "42.42.in-addr.arpa.
    // value = "my-ns-192-168-0-42.my.domain.org"
    try {
      Name name = Name.fromString(key);
      org.xbill.DNS.Record soaRecord =
          SOARecord.fromString(
              name,
              Type.SOA,
              DClass.IN,
              3600,
              host + ". root." + host + ". 2915 10800 3600 60480 300",
              Name.fromString(host + "."));
      Lookup.getDefaultCache(DClass.IN).addRecord(soaRecord, 10);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Deprecated
  public static void installSRV(String key, String value) {
    installSRV(key, value, 8041);
  }

  public static void installSRV(String key, String value, int port) {
    installSRV(key, value, port, 10, 0);
  }

  public static void installSRV(String key, String value, int port, int priority, int weight) {
    // Example:
    // key = "42.42.in-addr.arpa.
    // value = "my-ns-192-168-0-42.my.domain.org"
    try {
      Name name = Name.fromString("_sciondiscovery._tcp." + key);
      org.xbill.DNS.Record srvRecord =
          SRVRecord.fromString(
              name,
              Type.SRV,
              DClass.IN,
              3600,
              priority + " " + weight + " " + port + " " + value + ".",
              Name.fromString(value + "."));
      Lookup.getDefaultCache(DClass.IN).addRecord(srvRecord, 10);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
