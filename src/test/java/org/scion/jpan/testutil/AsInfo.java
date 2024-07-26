// Copyright 2024 ETH Zurich
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

import java.util.ArrayList;
import java.util.List;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;

public class AsInfo {
  private long isdAs;
  private String controlServer;
  private final List<BorderRouter> borderRouters = new ArrayList<>();

  public void setIsdAs(long isdAs) {
    this.isdAs = isdAs;
  }

  public void add(BorderRouter borderRouter) {
    borderRouters.add(borderRouter);
  }

  public void setControlServer(String addr) {
    controlServer = addr;
  }

  public int getControlServerPort() {
    return Integer.parseInt(controlServer.substring(controlServer.indexOf(':') + 1));
  }

  public String getBorderRouterAddressByIA(long remoteIsdAs) {
    for (BorderRouter br : borderRouters) {
      for (BorderRouterInterface brif : br.interfaces) {
        if (brif.isdAs == remoteIsdAs) {
          return br.internalAddress;
        }
      }
    }
    throw new ScionRuntimeException("No router found for IsdAs " + remoteIsdAs);
  }

  public long getIsdAs() {
    return isdAs;
  }

  public static class BorderRouter {
    private final String name;
    private final String internalAddress;
    private final List<BorderRouterInterface> interfaces;

    public BorderRouter(String name, String addr, List<BorderRouterInterface> interfaces) {
      this.name = name;
      this.internalAddress = addr;
      this.interfaces = interfaces;
    }
  }

  public static class BorderRouterInterface {
    final int id;
    final long isdAs;
    final String publicUnderlay;
    final String remoteUnderlay;

    public BorderRouterInterface(String id, String isdAs, String publicU, String remoteU) {
      this.id = Integer.parseInt(id);
      this.isdAs = ScionUtil.parseIA(isdAs);
      this.publicUnderlay = publicU;
      this.remoteUnderlay = remoteU;
    }
  }
}
