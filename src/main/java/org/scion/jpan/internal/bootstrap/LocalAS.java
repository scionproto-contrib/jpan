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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.InetSocketAddress;
import java.util.*;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.paths.DaemonServiceGrpc;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.proto.daemon.Daemon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parse a topology file into a local topology. */
public class LocalAS {

  private static final Logger LOG = LoggerFactory.getLogger(LocalAS.class.getName());

  private final List<ServiceNode> controlServices;
  private final List<ServiceNode> discoveryServices;
  private final List<BorderRouter> borderRouters;
  private final Map<Integer, BorderRouter> interfaceIDs;
  private long localIsdAs;
  private boolean isCoreAs;
  private int localMtu;
  private DispatcherPortRange portRange;
  private final TrcStore trcStore;

  //  public static synchronized LocalAS create(String topologyFile, TrcStore trcStore) {
  //    LocalAS localAS = new LocalAS(trcStore);
  //    localAS.parseTopologyFile(topologyFile);
  //    localAS.initInterfaceIDs();
  //    return localAS;
  //  }
  //
  //  public static synchronized LocalAS create(DaemonServiceGrpc daemonService, TrcStore trcStore)
  // {
  //    LocalAS localAS = new LocalAS(trcStore);
  //    localAS.initializeFromDaemon(daemonService);
  //    localAS.initInterfaceIDs();
  //    return localAS;
  //  }

  //  public static LocalAS createForPathService(String pathService, TrcStore trcStore) {
  //    LocalAS localAS = new LocalAS(trcStore);
  //    localAS.initializeFromPathService(pathService);
  //    if (true) {
  //      throw new UnsupportedOperationException(pathService);
  //    }
  //    localAS.initInterfaceIDs();
  //    return localAS;
  //  }
  //
  //  protected LocalAS(TrcStore trcStore) {
  //    this.trcStore = trcStore;
  //  }

  LocalAS(
      long localIsdAs,
      boolean isCoreAs,
      int localMtu,
      DispatcherPortRange portRange,
      List<ServiceNode> controlServices,
      List<ServiceNode> discoveryServices,
      List<BorderRouter> borderRouters,
      TrcStore trcStore) {
    this.localIsdAs = localIsdAs;
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

  private static JsonElement safeGet(JsonObject o, String name) {
    JsonElement e = o.get(name);
    if (e == null) {
      throw new ScionRuntimeException("Entry not found in topology file: " + name);
    }
    return e;
  }

  public String getControlServerAddress() {
    return controlServices.get(0).ipString;
  }

  public boolean isCoreAs() {
    return isCoreAs;
  }

  public long getIsdAs() {
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

  public int getMtu() {
    return this.localMtu;
  }

  private void parseTopologyFile(String topologyFile) {
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(topologyFile);
    if (jsonTree.isJsonObject()) {
      JsonObject o = jsonTree.getAsJsonObject();
      localIsdAs = ScionUtil.parseIA(safeGet(o, "isd_as").getAsString());
      localMtu = safeGet(o, "mtu").getAsInt();
      JsonObject brs = safeGet(o, "border_routers").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : brs.entrySet()) {
        JsonObject br = e.getValue().getAsJsonObject();
        String addr = safeGet(br, "internal_addr").getAsString();
        JsonObject ints = safeGet(br, "interfaces").getAsJsonObject();
        List<BorderRouterInterface> interfaces = new ArrayList<>();
        for (Map.Entry<String, JsonElement> ifEntry : ints.entrySet()) {
          JsonObject ife = ifEntry.getValue().getAsJsonObject();
          JsonObject underlay = ife.getAsJsonObject("underlay");
          // "public" was changed to "local" in scionproto 0.11
          JsonElement local =
              underlay.has(("local")) ? underlay.get(("local")) : underlay.get(("public"));
          JsonElement remote = underlay.get(("remote"));
          long isdAs = ScionUtil.parseIA(ife.get("isd_as").getAsString());
          int mtu = ife.get("mtu").getAsInt();
          String linkTo = ife.get("link_to").getAsString();
          int ifId = Integer.parseInt(ifEntry.getKey());
          interfaces.add(
              new BorderRouterInterface(
                  ifId, local.getAsString(), remote.getAsString(), isdAs, mtu, linkTo));
        }
        borderRouters.add(new BorderRouter(e.getKey(), addr, interfaces));
      }
      JsonObject css = safeGet(o, "control_service").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : css.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        controlServices.add(new ServiceNode(e.getKey(), cs.get("addr").getAsString()));
      }
      JsonElement dss = o.get("discovery_service");
      if (dss != null) {
        for (Map.Entry<String, JsonElement> e : dss.getAsJsonObject().entrySet()) {
          JsonObject ds = e.getValue().getAsJsonObject();
          discoveryServices.add(new ServiceNode(e.getKey(), ds.get("addr").getAsString()));
        }
      }
      JsonElement dispatchedPorts = o.get("dispatched_ports");
      if (dispatchedPorts == null) {
        portRange = DispatcherPortRange.createEmpty();
      } else {
        portRange = parsePortRange(dispatchedPorts.getAsString());
      }
      JsonArray attr = safeGet(o, "attributes").getAsJsonArray();
      for (int i = 0; i < attr.size(); i++) {
        if ("core".equals(attr.get(i).getAsString())) {
          isCoreAs = true;
        }
      }
    }
  }

  private static DispatcherPortRange parsePortRange(String v) {
    if ("-".equals(v)) {
      return DispatcherPortRange.createEmpty();
    } else if ("all".equalsIgnoreCase(v)) {
      return DispatcherPortRange.createAll();
    } else {
      String[] sa = v.split("-");
      if (sa.length != 2) {
        throw new ScionRuntimeException("Illegal expression in topo file dispatched_ports: " + v);
      }
      int portMin = Integer.parseInt(sa[0]);
      int portMax = Integer.parseInt(sa[1]);
      if (portMin < 1 || portMax < 1 || portMax > 65535 || portMin > portMax) {
        throw new ScionRuntimeException("Illegal port values in topo file dispatched_ports: " + v);
      }
      return DispatcherPortRange.create(portMin, portMax);
    }
  }

  private void initInterfaceIDs() {
    for (BorderRouter br : borderRouters) {
      for (BorderRouterInterface brif : br.interfaces) {
        interfaceIDs.put(brif.id, br);
      }
    }
  }

  private void initializeFromDaemon(DaemonServiceGrpc daemonService) {
    Daemon.ASResponse as = readASInfo(daemonService);
    this.localIsdAs = as.getIsdAs();
    this.localMtu = as.getMtu();
    this.isCoreAs = as.getCore();
    this.portRange = readLocalPortRange(daemonService);
    this.borderRouters.addAll(readBorderRouterAddresses(daemonService));
  }

  private static DispatcherPortRange readLocalPortRange(DaemonServiceGrpc daemonService) {
    Daemon.PortRangeResponse response;
    try {
      response = daemonService.portRange(Empty.getDefaultInstance());
      return DispatcherPortRange.create(
          response.getDispatchedPortStart(), response.getDispatchedPortEnd());
    } catch (StatusRuntimeException e) {
      LOG.warn("ERROR getting dispatched_ports range from daemon: {}", e.getMessage());
      // Daemon doesn't support port range.
      return DispatcherPortRange.createEmpty();
    }
  }

  private static Collection<BorderRouter> readBorderRouterAddresses(
      DaemonServiceGrpc daemonService) {
    Map<String, BorderRouter> borderRouters = new HashMap<>();
    for (Map.Entry<Long, Daemon.Interface> e : readInterfaces(daemonService).entrySet()) {
      String addr = e.getValue().getAddress().getAddress();
      int id = (int) (long) e.getKey();
      BorderRouter br =
          borderRouters.computeIfAbsent(
              addr,
              s ->
                  new BorderRouter("UnknownName-" + borderRouters.size(), addr, new ArrayList<>()));
      br.interfaces.add(new BorderRouterInterface(id, addr, null, 0, 0, null));
    }
    return borderRouters.values();
  }

  private static Daemon.ASResponse readASInfo(DaemonServiceGrpc daemonService) {
    Daemon.ASRequest request = Daemon.ASRequest.newBuilder().setIsdAs(0).build();
    Daemon.ASResponse response;
    try {
      response = daemonService.aS(request);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        throw new ScionRuntimeException("Could not connect to SCION daemon: " + e.getMessage(), e);
      }
      throw new ScionRuntimeException("Error while getting AS info: " + e.getMessage(), e);
    }
    return response;
  }

  private static Map<Long, Daemon.Interface> readInterfaces(DaemonServiceGrpc daemonService) {
    Daemon.InterfacesRequest request = Daemon.InterfacesRequest.newBuilder().build();
    Daemon.InterfacesResponse response;
    try {
      response = daemonService.interfaces(request);
    } catch (StatusRuntimeException e) {
      throw new ScionRuntimeException(e);
    }
    return response.getInterfacesMap();
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
    private final String name;
    private final String internalAddressString;
    private final InetSocketAddress internalAddress;
    private final List<BorderRouterInterface> interfaces;

    BorderRouter(String name, String addr, List<BorderRouterInterface> interfaces) {
      this.name = name;
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

    BorderRouterInterface(
        int id, String publicU, String remoteU, long isdAs, int mtu, String linkTo) {
      this.id = id;
      this.publicUnderlay = publicU;
      this.remoteUnderlay = remoteU;
      this.isdAs = isdAs;
      this.mtu = mtu;
      this.linkTo = linkTo;
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
