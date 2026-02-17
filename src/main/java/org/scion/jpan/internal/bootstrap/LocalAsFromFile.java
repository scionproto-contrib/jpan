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
import java.util.*;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parse a topology file into a local topology. */
public class LocalAsFromFile {

  private LocalAsFromFile() {}

  public static LocalAS create(String topologyFile, TrcStore trcStore) {
    List<LocalAS.ServiceNode> controlServices = new ArrayList<>();
    List<LocalAS.ServiceNode> discoveryServices = new ArrayList<>();
    List<LocalAS.BorderRouter> borderRouters = new ArrayList<>();
    long localIsdAs = 0;
    boolean isCoreAs = false;
    int localMtu = 0;
    LocalAS.DispatcherPortRange portRange = null;

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
        List<LocalAS.BorderRouterInterface> interfaces = new ArrayList<>();
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
              new LocalAS.BorderRouterInterface(
                  ifId, local.getAsString(), remote.getAsString(), isdAs, mtu, linkTo));
        }
        borderRouters.add(new LocalAS.BorderRouter(addr, interfaces));
      }
      JsonObject css = safeGet(o, "control_service").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : css.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        controlServices.add(new LocalAS.ServiceNode(e.getKey(), cs.get("addr").getAsString()));
      }
      JsonElement dss = o.get("discovery_service");
      if (dss != null) {
        for (Map.Entry<String, JsonElement> e : dss.getAsJsonObject().entrySet()) {
          JsonObject ds = e.getValue().getAsJsonObject();
          discoveryServices.add(new LocalAS.ServiceNode(e.getKey(), ds.get("addr").getAsString()));
        }
      }
      JsonElement dispatchedPorts = o.get("dispatched_ports");
      if (dispatchedPorts == null) {
        portRange = LocalAS.DispatcherPortRange.createEmpty();
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

    return new LocalAS(
        localIsdAs,
        isCoreAs,
        localMtu,
        portRange,
        controlServices,
        discoveryServices,
        borderRouters,
        trcStore);
  }

  private static JsonElement safeGet(JsonObject o, String name) {
    JsonElement e = o.get(name);
    if (e == null) {
      throw new ScionRuntimeException("Entry not found in topology file: " + name);
    }
    return e;
  }

  private static LocalAS.DispatcherPortRange parsePortRange(String v) {
    if ("-".equals(v)) {
      return LocalAS.DispatcherPortRange.createEmpty();
    } else if ("all".equalsIgnoreCase(v)) {
      return LocalAS.DispatcherPortRange.createAll();
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
      return LocalAS.DispatcherPortRange.create(portMin, portMax);
    }
  }
}
