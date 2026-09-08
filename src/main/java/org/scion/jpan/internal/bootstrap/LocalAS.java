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
  private final List<SnapControlNode> snapControlNodes;
  private final Map<Integer, BorderRouter> interfaceIDs;
  private final Set<Long> localIsdAs;
  private final boolean isCoreAs;
  private final int localMtu;
  private final DispatcherPortRange portRange;
  private final TrcStore trcStore;
  private InetSocketAddress snapFirstHopAddress;

  LocalAS(
      Set<Long> localIsdAs,
      boolean isCoreAs,
      int localMtu,
      DispatcherPortRange portRange,
      List<ServiceNode> controlServices,
      List<ServiceNode> discoveryServices,
      List<BorderRouter> borderRouters,
      List<SnapControlNode> snapControlNodes,
      TrcStore trcStore) {
    this.localIsdAs = Collections.unmodifiableSet(localIsdAs);
    this.isCoreAs = isCoreAs;
    this.localMtu = localMtu;
    this.portRange = portRange;
    this.controlServices = controlServices;
    this.discoveryServices = discoveryServices;
    this.borderRouters = borderRouters;
    this.snapControlNodes = snapControlNodes;
    this.interfaceIDs = initInterfaceIDs(borderRouters);
    this.trcStore = trcStore;
  }

  private static Map<Integer, LocalAS.BorderRouter> initInterfaceIDs(
      List<LocalAS.BorderRouter> borderRouters) {
    Map<Integer, LocalAS.BorderRouter> interfaceIDs = new HashMap<>();
    for (LocalAS.BorderRouter br : borderRouters) {
      for (Integer brIfId : br.getInterfaces()) {
        interfaceIDs.put(brIfId, br);
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
  @Deprecated
  public long getIsdAs() {
    // Just pick a random ISD/AS
    return localIsdAs.iterator().next();
  }

  /**
   * @return the ISD/AS numbers of the local AS
   */
  public Set<Long> getIsdAses() {
    return localIsdAs;
  }

  /**
   * Address of the first hop border router for a given interface ID.
   *
   * @param interfaceId border router interface ID
   * @return The address of the first hop.
   */
  public InetSocketAddress getFirstHopAddress(int interfaceId) {
    BorderRouter br = interfaceIDs.get(interfaceId);
    if (br == null) {
      throw new ScionRuntimeException("No router found with interface ID " + interfaceId);
    }
    return br.internalAddress;
  }

  /**
   * The SNAP dataplane address to use as the first hop for an AS with no local border routers at
   * all (a SNAP-only tenant AS). Deliberately separate from {@link #getFirstHopAddress(int)}: that
   * method's contract is "throw if the interface ID isn't a real border router", which must stay
   * strict, while this one is an optional, narrowly-scoped fallback for the one case where there is
   * genuinely no border-router data to look up in the first place. Set once, after construction, by
   * {@code ScionService} once it resolves the SNAP dataplane -- {@link LocalAS} is otherwise
   * immutable, but this can't be a constructor argument: {@link LocalAS} is built first and the
   * SNAP dataplane is resolved afterwards, from {@link #getSnapControlNodes()} on this very
   * instance.
   *
   * @return the SNAP dataplane address, or {@code null} if SNAP is not enabled or not yet resolved.
   * @deprecated
   */
  @Deprecated
  public InetSocketAddress getSnapFirstHopAddress() {
    // TODO remove this!!
    return snapFirstHopAddress;
  }

  public void setSnapFirstHopAddress(InetSocketAddress snapFirstHopAddress) {
    this.snapFirstHopAddress = snapFirstHopAddress;
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

  public List<SnapControlNode> getSnapControlNodes() {
    return Collections.unmodifiableList(snapControlNodes);
  }

  public static class BorderRouter {
    private final InetSocketAddress internalAddress;
    private final List<Integer> interfaces = new ArrayList<>();

    BorderRouter(String addr) {
      this.internalAddress = IPHelper.toInetSocketAddress(addr);
    }

    public InetSocketAddress getInternalAddress() {
      return internalAddress;
    }

    public Iterable<Integer> getInterfaces() {
      return interfaces;
    }

    void addInterface(Integer borderRouterInterface) {
      interfaces.add(borderRouterInterface);
    }
  }

  public static class ServiceNode {
    final String name;
    final String ipString;

    protected ServiceNode(String name, String ipString) {
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

  public static class SnapControlNode {
    private final String address;
    private final List<Long> isdAses;

    SnapControlNode(String address, List<Long> isdAses) {
      this.address = address;
      this.isdAses = isdAses == null ? Collections.emptyList() : isdAses;
    }

    public String getAddress() {
      return address;
    }

    public List<Long> getIsdAses() {
      return Collections.unmodifiableList(isdAses);
    }
  }

  public static class DispatcherPortRange {
    private final int portMin;
    private final int portMax;

    protected DispatcherPortRange(int min, int max) {
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
