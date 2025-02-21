// Copyright 2025 ETH Zurich
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

package org.scion.jpan.ppl;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.scion.jpan.ScionUtil;

public class PplUtil {

  private PplUtil() {}

  public static String toMinimal(int isd, long as) {
    if (as == 0) {
      return "" + isd;
    }
    return ScionUtil.toStringIA(isd, as);
  }

  public static String toMinimal(int isd, long as, byte[] ip, int port) {
    String key = "" + isd;
    if (as > 0) {
      key += "-" + ScionUtil.toStringIA(0, as).substring(2);
      if (ip != null) {
        try {
          key += "," + InetAddress.getByAddress(ip).toString().substring(1);
        } catch (UnknownHostException e) {
          throw new IllegalArgumentException("Error converting IP to string", e);
        }
        if (port > 0) {
          key += ":" + port;
        }
      }
    }
    return key;
  }
}
