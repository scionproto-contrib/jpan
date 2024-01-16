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

package org.scion.demo.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.scion.demo.inspector.HopField;
import org.scion.demo.inspector.InfoField;
import org.scion.demo.inspector.PathHeaderScion;

public class ToStringUtil {

  public static String toStringIPv4(int ip) {
    int mask = 0xFF000000;
    String s = "";
    s += ((ip & mask) >>> 24) + ".";
    s += ((ip & (mask >>> 8)) >>> 16) + ".";
    s += ((ip & (mask >>> 16)) >>> 8) + ".";
    s += (ip & (mask >>> 24));
    return s;
  }

  public static String toStringIPv6(int len, int... ips) {
    String s = "";
    for (int i = 0; i < len; i++) {
      String s2 = Integer.toHexString(ips[i] >>> 16);
      String s3 = Integer.toHexString(ips[i] & 0xFFFF);
      s += s2 + ":" + s3;
      if (i < len - 1) {
        s += ":";
      }
    }
    // TODO not quite correct, we should replace the LONGEST sequence of 0:0 instead of the FIRST
    // one.
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

  public static String toStringIPv4(byte[] bytes) {
    return bytes[0] + "." + bytes[1] + "." + bytes[2] + "." + bytes[3];
  }

  public static String toStringIPv6(byte[] b) {
    // TODO use custom fix-length builder with loop
    Integer[] ints = new Integer[8];
    for (int i = 0; i < ints.length; i++) {
      ints[i] = b[i * 2] * 256 + b[i * 2 + 1];
    }
    String s = String.format("%x:%x:%x:%x:%x:%x:%x:%x", (Object[]) ints);
    //    String s = String.format("%x%02x:%x%02x:%x%02x:%x%02x:%x%02x:%x%02x:%x%02x:%x%02x",
    //            b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
    //            b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
    //    String s = Integer.toHexString(bytes[0]) + Integer.toHexString(bytes[1]) + ":" +
    //                    Integer.toHexString(bytes[2]) + Integer.toHexString(bytes[3]) + ":" +
    //                    Integer.toHexString(bytes[4]) + Integer.toHexString(bytes[5]) + ":" +
    //                    Integer.toHexString(bytes[6]) + Integer.toHexString(bytes[7]) + ":" +
    //                    Integer.toHexString(bytes[8]) + Integer.toHexString(bytes[9]) + ":" +
    //                    Integer.toHexString(bytes[10]) + Integer.toHexString(bytes[11]) + ":" +
    //                    Integer.toHexString(bytes[12]) + Integer.toHexString(bytes[13]) + ":" +
    //                    Integer.toHexString(bytes[14]) + Integer.toHexString(bytes[15]);
    // TODO not quite correct, we should replace the LONGEST sequence of 0:0 instead of the FIRST
    // one.
    //    int pos = s.indexOf(":0:");
    //    if (pos >= 0) {
    //      int pos2 = pos + 2;
    //      while (pos2 + 2 < s.length() && s.charAt(pos2 + 1) == '0' && s.charAt(pos2 + 2) == ':')
    // {
    //        pos2 += 2;
    //      }
    //      s = s.substring(0, pos + 1) + s.substring(pos2);
    //      if (s.startsWith("0:")) {
    //        // TODO
    //      }
    //    }
    return s;
  }

  /**
   * Turns an InetSocketAddress into a String. Specifically, it surrounds the numeric code of IPv6
   * addresses with [], e.g. [0:0:0:0:0:0:0:0]:12345.
   *
   * @param addr socket address
   * @return address:port
   */
  public static String toAddressPort(InetSocketAddress addr) {
    InetAddress inetAddress = addr.getAddress();
    if (inetAddress instanceof Inet6Address) {
      String host = inetAddress.getHostAddress();
      if (host.contains(":") && !host.endsWith("]")) {
        return "[" + host + "]:" + addr.getPort();
      }
    }
    return inetAddress.getHostAddress() + ":" + addr.getPort();
  }

  public static String toStringHex(byte[] ba) {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < ba.length - 1; i++) {
      int ub = Byte.toUnsignedInt(ba[i]);
      sb.append("0x").append(Integer.toHexString(ub)).append(", ");
    }
    if (ba.length > 0) {
      int ub = Byte.toUnsignedInt(ba[ba.length - 1]);
      sb.append("0x").append(Integer.toHexString(ub));
    }
    sb.append("]");
    return sb.toString();
  }

  public static String toStringByte(byte[] ba) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    for (int i = 0; i < ba.length - 1; i++) {
      sb.append(ba[i]).append(", ");
    }
    if (ba.length > 0) {
      sb.append(ba[ba.length - 1]);
    }
    sb.append("}");
    return sb.toString();
  }

  public static String path(byte[] raw) {
    PathHeaderScion ph = new PathHeaderScion();
    ph.read(ByteBuffer.wrap(raw));
    StringBuilder sb = new StringBuilder();
    sb.append("Hops: [");
    InfoField info0 = ph.getInfoField(0);
    InfoField info1 = ph.getInfoField(1);
    InfoField info2 = ph.getInfoField(2);

    int[] segLen = {ph.getSegLen(0), ph.getSegLen(1), ph.getSegLen(2)};
    sb.append("c0:").append(info0.getFlagC()).append(" ");
    sb.append("c1:").append(info1.getFlagC()).append(" ");
    sb.append("c2:").append(info2.getFlagC()).append(" ");

    int offset = 0;
    for (int j = 0; j < segLen.length; j++) {
      boolean flagC = ph.getInfoField(j).getFlagC();
      for (int i = offset; i < offset + segLen[j] - 1; i++) {
        HopField hfE = ph.getHopField(i);
        HopField hfI = ph.getHopField(i + 1);
        if (flagC) {
          sb.append(hfE.getEgress()).append(">").append(hfI.getIngress());
        } else {
          sb.append(hfE.getIngress()).append(">").append(hfI.getEgress());
        }
        if (i < ph.getHopCount() - 1) {
          sb.append(" ");
        }
      }
      offset += segLen[j];
    }
    sb.append("]");
    return sb.toString();
  }

  public static String pathLong(byte[] raw) {
    PathHeaderScion ph = new PathHeaderScion();
    ph.read(ByteBuffer.wrap(raw));
    return ph.toString();
  }
}
