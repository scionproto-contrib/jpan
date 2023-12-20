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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.scion.ScionRuntimeException;
import org.xbill.DNS.*;

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

  public static InetAddress queryAOrAaaa(String flag, String hostName) throws IOException {
    if ("A".equals(flag)) {
      Record[] recordsA = new Lookup(hostName, Type.A).run();
      if (recordsA == null) {
        throw new ScionRuntimeException("No DNS A entry found for host: " + hostName);
      }
      for (int i = 0; i < recordsA.length; i++) {
        ARecord ar = (ARecord) recordsA[i];
        // TODO just return the first one for now
        return ar.getAddress();
      }
    } else if ("AAAA".equals(flag)) {
      Record[] recordsA = new Lookup(hostName, Type.AAAA).run();
      if (recordsA == null) {
        throw new ScionRuntimeException("No DNS AAAA entry found for host: " + hostName);
      }
      for (int i = 0; i < recordsA.length; i++) {
        AAAARecord ar = (AAAARecord) recordsA[i];
        // TODO just return the first one for now
        return ar.getAddress();
      }
    } else {
      throw new ScionRuntimeException("Illegal flag, should be A or AAAA: " + flag);
    }
    return null;
  }

  public static void installNAPTR(String asHost, byte[] topoAddr, int topoPort)
      throws TextParseException, UnknownHostException {
    {
      // NAPTR:
      // flags=A
      // service=x-sciondiscovery:tcp
      // regExp=
      // order=1
      // pref=1
      // repl=netsec-w37w3w.inf.ethz.ch.
      // addName=netsec-w37w3w.inf.ethz.ch.
      // dClass=1
      // name=inf.ethz.ch.
      // ttl=2533

      String AS_HOST = asHost; // "iinf.ethz.ch.";
      String REPL_HOST = "topohost.x.y."; // "netsec-w37w3w.inf.ethz.ch."
      Cache c = new Cache(DClass.IN);

      Name name = Name.fromString(AS_HOST + ".");
      Name replacement = new Name(REPL_HOST);

      TXTRecord txt = new TXTRecord(name, DClass.IN, 5000, "x-sciondiscovery=" + topoPort);
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
          new NAPTRRecord(
              name, DClass.IN, 5000, 1, 1, naptrFlag, "x-sciondiscovery:tcp", "", replacement);
      c.addRecord(nr2, 10);

      Lookup.setDefaultCache(c, DClass.IN);

      // TODO clean up
      //      System.out.println("NAme: " + name);
      //    }
      //
      //    {
      // String hostName = "iinf.ethz.ch";
      //      Record[] nrRecords = new Lookup(AS_HOST, Type.NAPTR).run();
      //      NAPTRRecord nr = (NAPTRRecord) nrRecords[0];
      //      System.out.println(
      //          "NAPTR2: "
      //              + nr.getFlags()
      //              + " "
      //              + nr.getService()
      //              + " reg="
      //              + nr.getRegexp()
      //              + " o="
      //              + nr.getOrder()
      //              + "  pref="
      //              + nr.getPreference()
      //              + "  repl="
      //              + nr.getReplacement()
      //              + "  addN="
      //              + nr.getAdditionalName()
      //              + "  "
      //              + nr.getDClass()
      //              + "  "
      //              + nr.getName()
      //              + "  ttl="
      //              + nr.getTTL());
      //
      //      Record[] txtRecords = new Lookup(asHost, Type.TXT).run();
      //      TXTRecord txtR = (TXTRecord) txtRecords[0];
      //      System.out.println("TXT: " + txtR);
    }
  }
}
