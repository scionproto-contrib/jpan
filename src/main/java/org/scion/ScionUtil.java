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

package org.scion;

import java.net.InetSocketAddress;

public class ScionUtil {

  //  const (
  //  IABytes       = 8
  private static final int ISDBits = 16;

  // ISD is the ISolation Domain identifier. See formatting and allocations here:
  // https://github.com/scionproto/scion/wiki/ISD-and-AS-numbering#isd-numbers
  // type ISD uint16

  // AS is the Autonomous System identifier. See formatting and allocations here:
  // https://github.com/scionproto/scion/wiki/ISD-and-AS-numbering#as-numbers
  //  type AS uint64

  // IA represents the ISD (ISolation Domain) and AS (Autonomous System) Id of a given SCION AS.
  // The highest 16 bit form the ISD number and the lower 48 bits form the AS number.
  //  type IA uint64
  private static final int ASBits = 48;
  private static final int BGPASBits = 32;
  //  MaxISD    ISD = (1 << ISDBits) - 1
  private static final long MaxAS = (1L << ASBits) - 1L;
  //  MaxBGPAS  AS  = (1 << BGPASBits) - 1
  //
  private static final int asPartBits = 16;
  private static final int asPartBase = 16;
  private static final int asPartMask = (1 << asPartBits) - 1; // TODO int????
  private static final int asParts = ASBits / asPartBits;

  // ParseIA parses an IA from a string of the format 'isd-as'.
  public static long ParseIA(String ia) { // (IA, error) {
    String[] parts = ia.split("-"); // TODO regex
    if (parts.length != 2) {
      // return 0, serrors.New("invalid ISD-AS", "value", ia)
      throw new ScionException("invalid ISD-AS: value=" + ia);
    }
    int isd = ParseISD(parts[0]);
    //    if err != nil {
    //      return 0, err
    //    }
    long as = ParseAS(parts[1]);
    //    if err != nil {
    //      return 0, err
    //    }
    return MustIAFrom(isd, as);
  }
  // )

  // ParseISD parses an ISD from a decimal string. Note that ISD 0 is parsed
  // without any errors.
  private static int ParseISD(String s) { // (ISD, error) {
    // int isd = strconv.ParseUint(s, 10, ISDBits);
    int isd = Integer.parseUnsignedInt(s, 10); // , ISDBits);
    //    if err != nil {
    //      return 0, serrors.WrapStr("parsing ISD", err) // TODO
    //    }
    return isd;
  }

  // MustIAFrom creates an IA from the ISD and AS number. It panics if any error
  // is encountered. Callers must ensure that the values passed to this function
  // are valid.
  private static long MustIAFrom(int isd, long as) { // IA {
    long ia = IAFrom(isd, as);
    //    if err != nil {
    //      panic(fmt.Sprintf("parsing ISD-AS: %s", err)) // TODO
    //    }
    return ia;
  }

  private static boolean inRange(long as) {
    return as <= MaxAS;
  }

  // IAFrom creates an IA from the ISD and AS number.
  private static long IAFrom(int isd, long as) {
    if (!inRange(as)) {
      // return 0, serrors.New("AS out of range", "max", MaxAS, "value", as)
      throw new IllegalArgumentException("AS out of range: max=" + MaxAS + "; value=" + as);
    }
    // return IA(isd)<<ASBits | IA(as&MaxAS), nil
    return Integer.toUnsignedLong(isd) << ASBits | (as & MaxAS);
  }

  // ParseAS parses an AS from a decimal (in the case of the 32bit BGP AS number
  // space) or ipv6-style hex (in the case of SCION-only AS numbers) string.
  private static long ParseAS(String as) { // (AS, error) {
    return parseAS(as, ":");
  }

  private static long parseAS(String as, String sep) { // (AS, error) {
    String[] parts = as.split(sep);
    if (parts.length == 1) {
      // Must be a BGP AS, parse as 32-bit decimal number
      return asParseBGP(as);
    }

    if (parts.length != asParts) {
      // return 0, serrors.New("wrong number of separators", "sep", sep, "value", as)
      throw new ScionException("wrong number of separators: sep=" + sep + "; value=" + as);
    }
    long parsed = 0; // AS
    for (int i = 0; i < asParts; i++) {
      parsed <<= asPartBits;
      // long v = strconv.ParseUint(parts[i], asPartBase, asPartBits); // TODO long??
      int v32 = Integer.parseUnsignedInt(parts[i], asPartBase) & 0xFFFF; // TODO long??
      long v = Integer.toUnsignedLong(v32);
      //      if err != nil {
      //        return 0, serrors.WrapStr("parsing AS part", err, "index", i, "value", as) // TODO
      //      }
      parsed |= v; // AS(v)
    }
    // This should not be reachable. However, we leave it here to protect
    // against future refactor mistakes.
    if (!inRange(parsed)) {
      // return 0, serrors.New("AS out of range", "max", MaxAS, "value", as)
      throw new ScionException(
          "AS out of range: max=" + MaxAS + "; value=" + as + "; parsed=" + parsed);
    }
    return parsed;
  }

  private static long asParseBGP(String s) { // (AS, error) {
    // long as = strconv.ParseUint(s, 10, BGPASBits)
    long as = Integer.parseUnsignedInt(s, 10);
    //    if err != nil {
    //      return 0, serrors.WrapStr("parsing BGP AS", err) // TODO ?
    //    }
    return as;
  }


  public static String toStringIA(long ia) {
    long mask = 0xFFFFL << 48;
    String s = "";
    s +=  Long.toString((ia & mask) >>> 48, 16) + ":";
    mask >>>= 16;
    s +=  Long.toString((ia & mask) >>> 32, 16) + ":";
    mask >>>= 16;
    s +=  Long.toString((ia & mask) >>> 16, 16) + ":";
    mask >>>= 16;
    s +=  Long.toString(ia & mask, 16);
    return s;
  }

  public static String toStringIA(long isd, long as) {
    long ia = (isd << 48) | as;
    long mask = 0xFFFFL << 48;
    String s = "";
    s +=  Long.toString((ia & mask) >>> 48, 16) + ":";
    mask >>>= 16;
    s +=  Long.toString((ia & mask) >>> 32, 16) + ":";
    mask >>>= 16;
    s +=  Long.toString((ia & mask) >>> 16, 16) + ":";
    mask >>>= 16;
    s +=  Long.toString(ia & mask, 16);
    return s;
  }

  public static String toStringIPv4(int ip) {
    int mask = 0xFF000000;
    String s = "";
    s += ((ip & mask) >>> 24) + ".";
    s += ((ip & (mask >>> 8)) >>> 16) + ".";
    s += ((ip & (mask >>> 16)) >>> 8) + ".";
    s += (ip & (mask >>> 24));
    return s;
  }

  public static String toStringIPv6(int len, int ... ips) {
    String s = "";
    for (int i = 0; i < len; i++) {
      String s2 = Integer.toHexString(ips[i] >>> 16);
      String s3 = Integer.toHexString(ips[i] & 0xFFFF);
      s += s2 + ":" + s3;
      if (i < len -1) {
        s += ":";
      }
    }
    // TODO not quite correct, we should replace the LONGEST sequence of 0:0 instead of the FIRST one.
    int pos = s.indexOf("0:0:");
    if (pos >= 0) {
      int pos2 = pos + 4;
      for (; pos2 + 1 < s.length(); pos2 += 2) {
        if (s.charAt(pos2) != '0' || s.charAt(pos2 + 1) != ':') {
          break;
        }
      }
      s = s.substring(0, pos) + s.substring(pos2 - 1);
      if (pos == 0) {
        s = ":" + s;
      }
    }
    return s;
  }

  // TODO Isn´t this supported by the InetSocketClass????
  public static String toHostAddrPort(InetSocketAddress address) {
    return address.getAddress().getHostAddress() + ":" + address.getPort();
  }


  private static String getPropertyOrEnv(String propertyName, String envName) {
    String value = System.getProperty(propertyName);
    return value != null ? value : System.getenv(envName);
  }

  public static String getPropertyOrEnv(String propertyName, String envName, String defaultValue) {
    String value = getPropertyOrEnv( propertyName, envName);
    return value != null ? value : defaultValue;
  }

  public static boolean getPropertyOrEnv(String propertyName, String envName, boolean defaultValue) {
    String value = getPropertyOrEnv( propertyName, envName);
    return value != null ? Boolean.parseBoolean(value) : defaultValue;
  }

  // TODO Return ScionException????
  // TODO
}