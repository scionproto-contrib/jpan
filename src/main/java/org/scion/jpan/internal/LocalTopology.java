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

package org.scion.jpan.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;

/** Parse a topology file into a local topology. */
public class LocalTopology {

  private final List<ServiceNode> controlServices = new ArrayList<>();
  private final List<ServiceNode> discoveryServices = new ArrayList<>();
  private final List<BorderRouter> borderRouters = new ArrayList<>();
  private String localIsdAs;
  private boolean isCoreAs;
  private int localMtu;
  private DispatcherPortRange portRange;

  public static synchronized LocalTopology create(String topologyFile) {
    LocalTopology topo = new LocalTopology();
    topo.parseTopologyFile(topologyFile);
    return topo;
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
    return ScionUtil.parseIA(localIsdAs);
  }

  public String getBorderRouterAddress(int interfaceId) {
    for (BorderRouter br : borderRouters) {
      for (BorderRouterInterface brif : br.interfaces) {
        if (brif.id == interfaceId) {
          return br.internalAddress;
        }
      }
    }
    throw new ScionRuntimeException("No router found with interface ID " + interfaceId);
  }

  public int getMtu() {
    return this.localMtu;
  }

  private void parseTopologyFile(String topologyFile) {
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(topologyFile);
    if (jsonTree.isJsonObject()) {
      JsonObject o = jsonTree.getAsJsonObject();
      localIsdAs = safeGet(o, "isd_as").getAsString();
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
          interfaces.add(
              new BorderRouterInterface(
                  ifEntry.getKey(), local.getAsString(), remote.getAsString(), isdAs, mtu, linkTo));
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
    private final String internalAddress;
    private final List<BorderRouterInterface> interfaces;

    BorderRouter(String name, String addr, List<BorderRouterInterface> interfaces) {
      this.name = name;
      this.internalAddress = addr;
      this.interfaces = interfaces;
    }

    public Iterable<BorderRouterInterface> getInterfaces() {
      return interfaces;
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
        String id, String publicU, String remoteU, long isdAs, int mtu, String linkTo) {
      this.id = Integer.parseInt(id);
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
