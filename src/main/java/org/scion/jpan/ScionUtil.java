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

package org.scion.jpan;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.scion.jpan.internal.PathRawParser;

/** Scion utility functions. */
public class ScionUtil {

  // ISD is the ISolation Domain identifier. See formatting and allocations here:
  // https://github.com/scionproto/scion/wiki/ISD-and-AS-numbering#isd-numbers

  // AS is the Autonomous System identifier. See formatting and allocations here:
  // https://github.com/scionproto/scion/wiki/ISD-and-AS-numbering#as-numbers

  // IA represents the ISD (ISolation Domain) and AS (Autonomous System) Id of a given SCION AS.
  // The highest 16 bit form the ISD number and the lower 48 bits form the AS number.
  private static final int ISD_BITS = 16;
  private static final int MAX_ISD = (1 << ISD_BITS) - 1;
  private static final int AS_BITS = 48;
  private static final long MAX_AS = (1L << AS_BITS) - 1L;
  private static final int AS_PART_BITS = 16;
  private static final int AS_PART_BASE = 16;
  private static final int AS_PARTS = AS_BITS / AS_PART_BITS;

  /** ParseIA parses an IA from a string of the format 'isd-as'. */
  public static long parseIA(String ia) {
    String[] parts = ia.split("-");
    if (parts.length != 2) {
      throw new IllegalArgumentException("invalid ISD-AS: value=" + ia);
    }
    int isd = parseISD(parts[0]);
    long as = parseAS(parts[1]);
    checkLimits(isd, as);
    return Integer.toUnsignedLong(isd) << AS_BITS | (as & MAX_AS);
  }

  public static int parseISD(String isd) {
    int parsed = Integer.parseUnsignedInt(isd, 10);
    checkLimits(parsed, 0);
    return parsed;
  }

  /**
   * ParseAS parses an AS from a decimal (in the case of the 32bit BGP AS number space) or
   * ipv6-style hex (in the case of SCION-only AS numbers) string.
   */
  public static long parseAS(String as) {
    String[] parts = as.split(":");
    if (parts.length == 1) {
      // Must be a BGP AS, parse as 32-bit decimal number
      return Integer.parseUnsignedInt(as, 10);
    }

    if (parts.length != AS_PARTS) {
      throw new IllegalArgumentException("Wrong number of ':' separators in value=" + as);
    }
    long parsed = 0;
    for (int i = 0; i < AS_PARTS; i++) {
      parsed <<= AS_PART_BITS;
      parsed |= Long.parseUnsignedLong(parts[i], AS_PART_BASE) & 0xFFFF;
    }
    checkLimits(0, parsed);
    return parsed;
  }

  public static String toStringIA(long ia) {
    return toStringIA((int) ((ia & 0xFFFF000000000000L) >>> 48), ia & 0xFFFFFFFFFFFFL);
  }

  public static String toStringIA(int isd, long as) {
    checkLimits(isd, as);

    // ISD
    String s = "";
    s += (isd & 0xFFFFL) + "-";

    if (as <= 0xFFFFFFFFL) {
      // Classic AS - 32bit - decimal
      s += as;
    } else {
      // SCION AS - 48bit - hexadecimal
      s += Long.toString((as & 0xFFFF00000000L) >>> 32, 16) + ":";
      s += Long.toString((as & 0xFFFF0000L) >>> 16, 16) + ":";
      s += Long.toString(as & 0xFFFFL, 16);
    }

    return s;
  }

  /**
   * @param raw A raw path
   * @return The sequence or border router interface IDs.
   */
  public static String toStringPath(byte[] raw) {
    PathRawParser ph = PathRawParser.create(raw);
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    int[] segLen = {ph.getSegLen(0), ph.getSegLen(1), ph.getSegLen(2)};
    int offset = 0;
    for (int j = 0; j < segLen.length; j++) {
      boolean flagC = ph.getInfoField(j).getFlagC();
      for (int i = offset; i < offset + segLen[j] - 1; i++) {
        PathRawParser.HopField hfE = ph.getHopField(i);
        PathRawParser.HopField hfI = ph.getHopField(i + 1);
        if (flagC) {
          sb.append(hfE.getEgress()).append(">").append(hfI.getIngress());
        } else {
          sb.append(hfE.getIngress()).append(">").append(hfI.getEgress());
        }
        if (i < ph.getHopFieldCount() - 2) {
          sb.append(" ");
        }
      }
      offset += segLen[j];
    }
    sb.append("]");
    return sb.toString();
  }

  /**
   * @param meta PathMetadata
   * @return ISD/AS codes and border outer interface IDs along the path.
   */
  public static String toStringPath(PathMetadata meta) {
    if (meta.getInterfacesList().isEmpty()) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    int nInterfaces = meta.getInterfacesList().size();
    for (int i = 0; i < nInterfaces; i++) {
      PathMetadata.PathInterface pIf = meta.getInterfacesList().get(i);
      if (i % 2 == 0) {
        sb.append(ScionUtil.toStringIA(pIf.getIsdAs())).append(" ");
        sb.append(pIf.getId()).append(">");
      } else {
        sb.append(pIf.getId()).append(" ");
      }
    }
    sb.append(ScionUtil.toStringIA(meta.getInterfacesList().get(nInterfaces - 1).getIsdAs()));
    sb.append("]");
    return sb.toString();
  }

  private static void checkLimits(int isd, long as) {
    if (isd < 0 || isd > MAX_ISD) {
      throw new IllegalArgumentException("ISD out of range: " + isd);
    }
    if (as < 0 || as > MAX_AS) {
      throw new IllegalArgumentException("AS out of range: " + as);
    }
  }

  public static String getPropertyOrEnv(String propertyName, String envName) {
    String value = System.getProperty(propertyName);
    return value != null || Constants.debugIgnoreEnvironment ? value : System.getenv(envName);
  }

  public static String getPropertyOrEnv(String propertyName, String envName, String defaultValue) {
    String value = getPropertyOrEnv(propertyName, envName);
    return value != null ? value : defaultValue;
  }

  public static boolean getPropertyOrEnv(
      String propertyName, String envName, boolean defaultValue) {
    String value = getPropertyOrEnv(propertyName, envName);
    return value != null ? Boolean.parseBoolean(value) : defaultValue;
  }

  public static int getPropertyOrEnv(String propertyName, String envName, int defaultValue) {
    String value = getPropertyOrEnv(propertyName, envName);
    return value != null ? Integer.parseInt(value) : defaultValue;
  }

  public static double getPropertyOrEnv(String propertyName, String envName, double defaultValue) {
    String value = getPropertyOrEnv(propertyName, envName);
    return value != null ? Double.parseDouble(value) : defaultValue;
  }

  public static int extractIsd(long isdAs) {
    return (int) (isdAs >>> AS_BITS);
  }

  public static long extractAs(long isdAs) {
    return isdAs & MAX_AS;
  }

  static InetSocketAddress parseInetSocketAddress(String addrStr) {
    try {
      int posColon = addrStr.indexOf(':');
      InetAddress inetAddress = InetAddress.getByName(addrStr.substring(0, posColon));
      return new InetSocketAddress(inetAddress, Integer.parseInt(addrStr.substring(posColon + 1)));
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static boolean isWildcard(long isdAs) {
    return isdAs == toWildcard(isdAs);
  }

  public static long toWildcard(long isdAs) {
    return (isdAs >>> 48) << 48;
  }

  public static boolean isPathUsingInterface(PathMetadata meta, long isdAs, long ifId) {
    int nInterfaces = meta.getInterfacesList().size();
    for (int i = 0; i < nInterfaces; i++) {
      PathMetadata.PathInterface pIf = meta.getInterfacesList().get(i);
      if (pIf.getIsdAs() == isdAs && pIf.getId() == ifId) {
        return true;
      }
    }
    return false;
  }

  private ScionUtil() {}
}
