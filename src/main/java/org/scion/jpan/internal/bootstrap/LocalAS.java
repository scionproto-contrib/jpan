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

package org.scion.jpan.internal.bootstrap;

import java.net.InetSocketAddress;
import java.util.*;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.internal.util.IPHelper;

/** Information about local AS. */
public class LocalAS {

  private final List<ServiceNode> controlServices;
  private final List<ServiceNode> discoveryServices;
  private final List<BorderRouter> borderRouters;
  private final Map<Integer, BorderRouter> interfaceIDs;
  private final List<Long> localIsdAs;
  private final boolean isCoreAs;
  private final int localMtu;
  private final DispatcherPortRange portRange;
  private final TrcStore trcStore;

  LocalAS(
      List<Long> localIsdAs,
      boolean isCoreAs,
      int localMtu,
      DispatcherPortRange portRange,
      List<ServiceNode> controlServices,
      List<ServiceNode> discoveryServices,
      List<BorderRouter> borderRouters,
      TrcStore trcStore) {
    this.localIsdAs = Collections.unmodifiableList(localIsdAs);
    this.isCoreAs = isCoreAs;
    this.localMtu = localMtu;
    this.portRange = portRange;
    this.controlServices = controlServices;
    this.discoveryServices = discoveryServices;
    this.borderRouters = borderRouters;
    this.interfaceIDs = initInterfaceIDs(borderRouters);
    this.trcStore = trcStore;
  }

  private static Map<Integer, LocalAS.BorderRouter> initInterfaceIDs(
      List<LocalAS.BorderRouter> borderRouters) {
    Map<Integer, LocalAS.BorderRouter> interfaceIDs = new HashMap<>();
    for (LocalAS.BorderRouter br : borderRouters) {
      for (LocalAS.BorderRouterInterface brIf : br.getInterfaces()) {
        interfaceIDs.put(brIf.id, br);
      }
    }
    return interfaceIDs;
  }

  public String getControlServerAddress() {
    return controlServices.get(0).ipString;
  }

  /**
   * @return 'true' if the local AS is a core AS
   * @deprecated This is not available in the new endhost API
   */
  @Deprecated
  public boolean isCoreAs() {
    return isCoreAs;
  }

  /**
   * @return the ISD/AS number of the local AS
   * @deprecated This is not available in the new endhost API
   */
  public long getIsdAs() {
    return localIsdAs.get(0);
  }

  /**
   * @return the ISD/AS numbers of the local AS
   */
  public List<Long> getIsdAses() {
    return localIsdAs;
  }

  public String getBorderRouterAddressString(int interfaceId) {
    BorderRouter br = interfaceIDs.get(interfaceId);
    if (br == null) {
      throw new ScionRuntimeException("No router found with interface ID " + interfaceId);
    }
    return br.internalAddressString;
  }

  public InetSocketAddress getBorderRouterAddress(int interfaceId) {
    BorderRouter br = interfaceIDs.get(interfaceId);
    if (br == null) {
      throw new ScionRuntimeException("No router found with interface ID " + interfaceId);
    }
    return br.internalAddress;
  }

  /**
   * @return mtu
   * @deprecated This is not available in the new endhost API
   */
  @Deprecated
  public int getMtu() {
    return this.localMtu;
  }

  public DispatcherPortRange getPortRange() {
    return portRange;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("ISD/AS: ").append(localIsdAs).append('\n');
    sb.append("Core: ").append(isCoreAs).append('\n');
    sb.append("MTU: ").append(localMtu).append('\n');
    for (ServiceNode sn : controlServices) {
      sb.append("Control server:   ").append(sn).append('\n');
    }
    for (ServiceNode sn : discoveryServices) {
      sb.append("Discovery server: ").append(sn).append('\n');
    }
    return sb.toString();
  }

  public List<ServiceNode> getControlServices() {
    return Collections.unmodifiableList(controlServices);
  }

  public List<BorderRouter> getBorderRouters() {
    return Collections.unmodifiableList(borderRouters);
  }

  public static class BorderRouter {
    private final String internalAddressString;
    private final InetSocketAddress internalAddress;
    private final List<BorderRouterInterface> interfaces;

    BorderRouter(String addr, List<BorderRouterInterface> interfaces) {
      this.internalAddressString = addr;
      this.internalAddress = IPHelper.toInetSocketAddress(addr);
      this.interfaces = interfaces;
    }

    public InetSocketAddress getInternalAddress() {
      return internalAddress;
    }

    public Iterable<BorderRouterInterface> getInterfaces() {
      return interfaces;
    }

    void addInterface(BorderRouterInterface borderRouterInterface) {
      interfaces.add(borderRouterInterface);
    }
  }

  public static class BorderRouterInterface {
    public static final String PARENT = "parent";
    public static final String CHILD = "child";
    public static final String CORE = "core";
    final int id;
    final String publicUnderlay;
    final String remoteUnderlay;
    final long isdAs;
    final int mtu;
    final String linkTo;

    @Deprecated // Should only be used in Unit tests
    BorderRouterInterface(
        int id, String publicU, String remoteU, long isdAs, int mtu, String linkTo) {
      this.id = id;
      this.publicUnderlay = publicU;
      this.remoteUnderlay = remoteU;
      this.isdAs = isdAs;
      this.mtu = mtu;
      this.linkTo = linkTo;
    }

    BorderRouterInterface(int id) {
      this(id, "unknown", "unknown", 0, 1200, "");
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

    public String getPublicUnderlay() {
      return publicUnderlay;
    }
  }

  public static class ServiceNode {
    final String name;
    final String ipString;

    ServiceNode(String name, String ipString) {
      this.name = name;
      this.ipString = ipString;
    }

    public String getIpString() {
      return ipString;
    }

    @Override
    public String toString() {
      return "{" + "name='" + name + '\'' + ", ipString='" + ipString + '\'' + '}';
    }
  }

  public static class DispatcherPortRange {
    private final int portMin;
    private final int portMax;

    private DispatcherPortRange(int min, int max) {
      portMin = min;
      portMax = max;
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

    public boolean hasPortRange() {
      return portMin >= 1 && portMax <= 65535 && portMax >= portMin;
    }

    public boolean hasPortRangeALL() {
      return portMin == 1 && portMax == 65535;
    }

    public InetSocketAddress mapToLocalPort(InetSocketAddress address) {
      if (address.getPort() == Constants.SCMP_PORT
          || (address.getPort() >= portMin && address.getPort() <= portMax)) {
        return address;
      }
      return new InetSocketAddress(address.getAddress(), Constants.DISPATCHER_PORT);
    }

    public int getPortMin() {
      return portMin;
    }

    public int getPortMax() {
      return portMax;
    }
  }
}
