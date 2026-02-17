// Copyright 2026 ETH Zurich
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.proto.endhost.Underlays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Get topology info from a path service. */
public class LocalAsFromPathService {

  private static final Logger LOG = LoggerFactory.getLogger(LocalAsFromPathService.class.getName());

  private LocalAsFromPathService() {}

  public static LocalAS create(String pathService, TrcStore trcStore) {
    List<LocalAS.ServiceNode> snList = getServiceNodeList(pathService);
    Underlays.ListUnderlaysResponse u = query(snList, pathService);
    if (!u.hasUdp() || u.getUdp().getRoutersList().isEmpty()) {
      LOG.warn("No underlay available");
      return new LocalAS(0, false, 1200, null, null, null, null, trcStore);
    }
    long isdAs = u.getUdp().getRoutersList().get(0).getIsdAs();
    List<LocalAS.BorderRouter> brList = getBorderRouterList(u);
    return new LocalAS(
        isdAs,
        false, // TODO?
        1200, // TODO
        LocalAS.DispatcherPortRange.createAll(), // TODO
        snList,
        null,
        brList,
        trcStore);
  }

  private static List<LocalAS.ServiceNode> getServiceNodeList(String pathServiceAddresses) {
    List<LocalAS.ServiceNode> list = new ArrayList<>();
    String[] addresses = pathServiceAddresses.split(";");
    for (String address : addresses) {
      list.add(new LocalAS.ServiceNode("path service", address));
    }
    return list;
  }

  private static List<LocalAS.BorderRouter> getBorderRouterList(Underlays.ListUnderlaysResponse u) {
    List<LocalAS.BorderRouter> list = new ArrayList<>();
    for (Underlays.Router r : u.getUdp().getRoutersList()) {
      LocalAS.BorderRouter br = new LocalAS.BorderRouter(r.getAddress(), new ArrayList<>());
      for (Integer i : r.getInterfacesList()) {
        br.addInterface(new LocalAS.BorderRouterInterface(i));
      }
      list.add(br);
    }
    return list;
  }

  private static Underlays.ListUnderlaysResponse query(List<LocalAS.ServiceNode> nodes, String in) {
    for (LocalAS.ServiceNode node : nodes) {
      try {
        return query(node.getIpString());
      } catch (IOException e) {
        LOG.warn("ERROR contacting path service: {}", node.getIpString());
      }
    }
    LOG.error("Path services unreachable: {}", in);
    throw new ScionRuntimeException("Path services unreachable: " + in);
  }

  private static Underlays.ListUnderlaysResponse query(String apiAddress) throws IOException {
    OkHttpClient httpClient = new OkHttpClient();
    Underlays.ListUnderlaysRequest protoRequest =
        Underlays.ListUnderlaysRequest.newBuilder().build();
    RequestBody requestBody = RequestBody.create(protoRequest.toByteArray());

    Request request =
        new Request.Builder()
            .url("http://" + apiAddress + "/scion.endhost.v1.UnderlayService/ListUnderlays")
            .addHeader("Content-type", "application/proto")
            //            .addHeader("User-Agent", "OkHttp Bot")
            .post(requestBody)
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      ResponseBody body = response.body();
      if (!response.isSuccessful() || body == null) {
        throw new IOException("Unexpected code " + response.code() + ": " + response.message());
      }
      return Underlays.ListUnderlaysResponse.newBuilder().mergeFrom(body.bytes()).build();
    }
  }
}
