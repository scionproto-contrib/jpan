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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.LocalTopology;

public class AsInfo {
  private long isdAs;
  private String controlServer;
  private LocalTopology.DispatcherPortRange portRange;
  private final List<BorderRouter> borderRouters = new ArrayList<>();

  void setIsdAs(long isdAs) {
    this.isdAs = isdAs;
  }

  void setPortRange(LocalTopology.DispatcherPortRange portRange) {
    this.portRange = portRange;
  }

  LocalTopology.DispatcherPortRange getPortRange() {
    return this.portRange;
  }

  public InetSocketAddress mapDispatcherPorts(InetSocketAddress address) {
    return portRange.mapToLocalPort(address);
  }

  void add(BorderRouter borderRouter) {
    borderRouters.add(borderRouter);
  }

  void setControlServer(String addr) {
    controlServer = addr;
  }

  public void connectWith(AsInfo asInfoRemote) {
    // Associate BorderRouters
    HashMap<String, BorderRouterInterface> map = new HashMap<>();
    for (BorderRouter br : asInfoRemote.borderRouters) {
      for (BorderRouterInterface brIf : br.interfaces) {
        if (map.put(brIf.localUnderlay, brIf) != null) {
          throw new IllegalStateException();
        }
      }
    }
    for (BorderRouter br : borderRouters) {
      for (BorderRouterInterface brIf : br.interfaces) {
        BorderRouterInterface remoteIf = map.get(brIf.remoteUnderlay);
        brIf.setRemoteInterface(remoteIf);
      }
    }
  }

  public String getControlServerIP() {
    return controlServer.substring(0, controlServer.lastIndexOf(':'));
  }

  public int getControlServerPort() {
    return Integer.parseInt(controlServer.substring(controlServer.lastIndexOf(':') + 1));
  }

  public String getBorderRouterAddressByIA(long remoteIsdAs) {
    for (BorderRouter br : borderRouters) {
      for (BorderRouterInterface brIf : br.interfaces) {
        if (brIf.isdAs == remoteIsdAs) {
          return br.internalAddress;
        }
      }
    }
    throw new ScionRuntimeException("No router found for IsdAs " + remoteIsdAs);
  }

  public long getIsdAs() {
    return isdAs;
  }

  public List<BorderRouter> getBorderRouters() {
    return Collections.unmodifiableList(borderRouters);
  }

  public static class BorderRouter {
    private final String name;
    private final String internalAddress;
    private final List<BorderRouterInterface> interfaces = new ArrayList<>();

    public BorderRouter(String name, String addr) {
      this.name = name;
      this.internalAddress = addr;
    }

    public void addInterface(BorderRouterInterface borderRouterInterface) {
      this.interfaces.add(borderRouterInterface);
    }

    public String getInternalAddress() {
      return internalAddress;
    }

    public List<BorderRouterInterface> getInterfaces() {
      return Collections.unmodifiableList(interfaces);
    }
  }

  public static class BorderRouterInterface {
    final int id;
    final long isdAs;
    final String localUnderlay;
    final String remoteUnderlay;
    final BorderRouter borderRouter;
    BorderRouterInterface remoteInterface;

    public BorderRouterInterface(
        String id,
        String isdAs,
        String localUnderlay,
        String remoteUnderlay,
        BorderRouter borderRouter) {
      this.id = Integer.parseInt(id);
      this.isdAs = ScionUtil.parseIA(isdAs);
      this.localUnderlay = localUnderlay;
      this.remoteUnderlay = remoteUnderlay;
      this.borderRouter = borderRouter;
    }

    void setRemoteInterface(BorderRouterInterface remoteInterface) {
      this.remoteInterface = remoteInterface;
    }

    public BorderRouterInterface getRemoteInterface() {
      return remoteInterface;
    }

    public BorderRouter getBorderRouter() {
      return borderRouter;
    }
  }
}
