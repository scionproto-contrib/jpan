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
import java.net.Inet4Address;
import java.net.InetAddress;
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

  public static void installNAPTR(String asHost, byte[] topoAddr, int topoPort) {
    installNAPTR(asHost, topoAddr, "x-sciondiscovery=" + topoPort, "x-sciondiscovery:tcp");
  }

  public static void installNAPTR(String asHost, byte[] topoAddr, String txtStr, String naptrKey) {
    try {
      Name name = Name.fromString(asHost + ".");
      Name replacement = new Name("topohost.x.y.");

      Cache c = Lookup.getDefaultCache(DClass.IN);
      TXTRecord txt = new TXTRecord(name, DClass.IN, 5000, txtStr);
      c.addRecord(txt, 10);

      InetAddress addr = InetAddress.getByAddress(topoAddr);
      String naptrFlag;
      if (addr instanceof Inet4Address) {
        ARecord a = new ARecord(replacement, DClass.IN, 5000, addr);
        c.addRecord(a, 10);
        naptrFlag = "A";
      } else {
        AAAARecord a = new AAAARecord(replacement, DClass.IN, 5000, addr);
        c.addRecord(a, 10);
        naptrFlag = "AAAA";
      }

      NAPTRRecord nr2 =
          new NAPTRRecord(name, DClass.IN, 5000, 1, 1, naptrFlag, naptrKey, "", replacement);
      c.addRecord(nr2, 10);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void installScionTXT(String asHost, String isdAs, String ipAddress) {
    installTXT(asHost, "\"scion=" + isdAs + "," + ipAddress + "\"");
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
}
