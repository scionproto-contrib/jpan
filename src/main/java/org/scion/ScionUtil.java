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

/** Scion utility functions. */
public class ScionUtil {

  // ISD is the ISolation Domain identifier. See formatting and allocations here:
  // https://github.com/scionproto/scion/wiki/ISD-and-AS-numbering#isd-numbers

  // AS is the Autonomous System identifier. See formatting and allocations here:
  // https://github.com/scionproto/scion/wiki/ISD-and-AS-numbering#as-numbers

  // IA represents the ISD (ISolation Domain) and AS (Autonomous System) Id of a given SCION AS.
  // The highest 16 bit form the ISD number and the lower 48 bits form the AS number.
  private static final int ISDBits = 16;
  private static final int MaxISD = (1 << ISDBits) - 1;
  private static final int ASBits = 48;
  private static final long MaxAS = (1L << ASBits) - 1L;
  private static final int asPartBits = 16;
  private static final int asPartBase = 16;
  private static final int asParts = ASBits / asPartBits;

  /** ParseIA parses an IA from a string of the format 'isd-as'. */
  public static long parseIA(String ia) {
    String[] parts = ia.split("-");
    if (parts.length != 2) {
      throw new IllegalArgumentException("invalid ISD-AS: value=" + ia);
    }
    int isd = Integer.parseUnsignedInt(parts[0], 10);
    long as = parseAS(parts[1]);
    checkLimits(isd, as);
    return Integer.toUnsignedLong(isd) << ASBits | (as & MaxAS);
  }

  /**
   * ParseAS parses an AS from a decimal (in the case of the 32bit BGP AS number space) or
   * ipv6-style hex (in the case of SCION-only AS numbers) string.
   */
  private static long parseAS(String as) {
    String[] parts = as.split(":");
    if (parts.length == 1) {
      // Must be a BGP AS, parse as 32-bit decimal number
      return Integer.parseUnsignedInt(as, 10);
    }

    if (parts.length != asParts) {
      throw new IllegalArgumentException("Wrong number of ':' separators in value=" + as);
    }
    long parsed = 0;
    for (int i = 0; i < asParts; i++) {
      parsed <<= asPartBits;
      parsed |= Long.parseUnsignedLong(parts[i], asPartBase) & 0xFFFF;
    }
    return parsed;
  }

  public static String toStringIA(long ia) {
    long mask = 0xFFFFL << 48;
    String s = "";
    s += Long.toString((ia & mask) >>> 48, 10) + "-";
    mask >>>= 16;
    s += Long.toString((ia & mask) >>> 32, 16) + ":";
    mask >>>= 16;
    s += Long.toString((ia & mask) >>> 16, 16) + ":";
    mask >>>= 16;
    s += Long.toString(ia & mask, 16);
    return s;
  }

  public static String toStringIA(int isd, long as) {
    checkLimits(isd, as);
    long ia = ((long) (isd) << 48) | as;
    long mask = 0xFFFFL << 48;
    String s = "";
    s += Long.toString((ia & mask) >>> 48, 10) + "-";
    mask >>>= 16;
    s += Long.toString((ia & mask) >>> 32, 16) + ":";
    mask >>>= 16;
    s += Long.toString((ia & mask) >>> 16, 16) + ":";
    mask >>>= 16;
    s += Long.toString(ia & mask, 16);
    return s;
  }

  private static void checkLimits(int isd, long as) {
    if (isd < 0 || isd > MaxISD) {
      throw new IllegalArgumentException("ISD out of range: " + isd);
    }
    if (as < 0 || as > MaxAS) {
      throw new IllegalArgumentException("AS out of range: " + as);
    }
  }

  public static String getPropertyOrEnv(String propertyName, String envName) {
    String value = System.getProperty(propertyName);
    return value != null ? value : System.getenv(envName);
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

  public static int extractIsd(long isdAs) {
    return (int) (isdAs >>> ASBits);
  }

  public static long extractAs(long isdAs) {
    return isdAs & MaxAS;
  }
}
