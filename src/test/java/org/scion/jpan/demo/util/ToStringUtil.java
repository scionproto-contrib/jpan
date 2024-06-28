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

package org.scion.jpan.demo.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import org.scion.jpan.demo.inspector.HopField;
import org.scion.jpan.demo.inspector.InfoField;
import org.scion.jpan.demo.inspector.PathHeaderScion;

public class ToStringUtil {

  public static String toStringIP(byte[] b) {
    try {
      return InetAddress.getByAddress(b).getHostAddress();
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
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
