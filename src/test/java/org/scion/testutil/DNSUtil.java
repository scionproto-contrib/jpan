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

package org.scion.testutil;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import org.xbill.DNS.*;

public class DNSUtil {

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
      Name name = Name.fromString(asHost + "."); // "inf.ethz.ch.";
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
}
