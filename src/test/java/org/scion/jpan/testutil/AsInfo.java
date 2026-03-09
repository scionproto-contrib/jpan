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
import java.util.stream.Collectors;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.util.IPHelper;

public class AsInfo {
  private long isdAs;
  private final List<ControlService> controlServers = new ArrayList<>();
  private DispatcherPortRange portRange;
  private boolean isCoreAs;
  private int mtu;
  private final List<BorderRouter> borderRouters = new ArrayList<>();

  void setIsdAs(long isdAs) {
    this.isdAs = isdAs;
  }

  void setIsCoreAs(boolean isCoreAs) {
    this.isCoreAs = isCoreAs;
  }

  boolean isCoreAs() {
    return isCoreAs;
  }

  void setMtu(int mtu) {
    this.mtu = mtu;
  }

  int getMtu() {
    return mtu;
  }

  void setPortRange(DispatcherPortRange portRange) {
    this.portRange = portRange;
  }

  DispatcherPortRange getPortRange() {
    return this.portRange;
  }

  public InetSocketAddress mapDispatcherPorts(InetSocketAddress address) {
    return portRange.mapToLocalPort(address);
  }

  void add(BorderRouter borderRouter) {
    borderRouters.add(borderRouter);
  }

  void addControlServer(String addr) {
    controlServers.add(new ControlService("unnamed", addr));
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

  public List<InetSocketAddress> getControlServerAddresses() {
    return controlServers.stream().map(ControlService::getAddress).collect(Collectors.toList());
  }

  public String getBorderRouterAddressByIA(long remoteIsdAs) {
    for (BorderRouter br : borderRouters) {
      for (BorderRouterInterface brIf : br.interfaces) {
        if (brIf.isdAs == remoteIsdAs) {
          return br.internalAddress;
        }
      }
    }
    throw new ScionRuntimeException(
        "No router found for IsdAs: " + ScionUtil.toStringIA(remoteIsdAs));
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
    public static final String PARENT = "parent";
    public static final String CHILD = "child";
    public static final String CORE = "core";
    final int id;
    final long isdAs;
    final String localUnderlay;
    final String remoteUnderlay;
    final BorderRouter borderRouter;
    final int mtu;
    final String linkTo;
    BorderRouterInterface remoteInterface;

    public BorderRouterInterface(
        String id,
        String localUnderlay,
        String remoteUnderlay,
        String isdAs,
        int mtu,
        String linkTo,
        BorderRouter borderRouter) {
      this.id = Integer.parseInt(id);
      this.isdAs = ScionUtil.parseIA(isdAs);
      this.mtu = mtu;
      this.linkTo = linkTo;
      this.localUnderlay = localUnderlay;
      this.remoteUnderlay = remoteUnderlay;
      this.borderRouter = borderRouter;
    }

    public long getIsdAs() {
      return isdAs;
    }

    public int getMtu() {
      return mtu;
    }

    public int getId() {
      return id;
    }

    public String getLinkTo() {
      return linkTo;
    }

    public String getRemoteUnderlay() {
      return remoteUnderlay;
    }

    public String getLocalUnderlay() {
      return localUnderlay;
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

  public static class ControlService {
    private final String name;
    private final InetSocketAddress address;

    public ControlService(String name, String addr) {
      this.name = name;
      this.address = IPHelper.toInetSocketAddress(addr);
    }

    public InetSocketAddress getAddress() {
      return address;
    }
  }

  public static class ServiceNode extends LocalAS.ServiceNode {
    ServiceNode(String name, String ipString) {
      super(name, ipString);
    }

    static ServiceNode create(String name, String ipString) {
      return new ServiceNode(name, ipString);
    }
  }

  public static class DispatcherPortRange extends LocalAS.DispatcherPortRange {

    private DispatcherPortRange(int min, int max) {
      super(min, max);
    }

    public static DispatcherPortRange create(int min, int max) {
      return new DispatcherPortRange(min, max);
    }

    public static DispatcherPortRange createAll() {
      return new DispatcherPortRange(1, 65535);
    }

    public static DispatcherPortRange createEmpty() {
      return new DispatcherPortRange(-1, -2);
    }
  }
}
