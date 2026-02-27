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

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.*;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.internal.paths.DaemonServiceGrpc;
import org.scion.jpan.proto.daemon.Daemon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Get topology info from daemon. */
public class LocalAsFromDaemon {

  private static final Logger LOG = LoggerFactory.getLogger(LocalAsFromDaemon.class.getName());

  private LocalAsFromDaemon() {}

  public static LocalAS create(DaemonServiceGrpc daemonService, TrcStore trcStore) {
    Daemon.ASResponse as = readASInfo(daemonService);
    return new LocalAS(
        Collections.singleton(as.getIsdAs()),
        as.getCore(),
        as.getMtu(),
        readLocalPortRange(daemonService),
        null,
        null,
        readBorderRouterAddresses(daemonService),
        trcStore);
  }

  private static LocalAS.DispatcherPortRange readLocalPortRange(DaemonServiceGrpc daemonService) {
    Daemon.PortRangeResponse response;
    try {
      response = daemonService.portRange(Empty.getDefaultInstance());
      return LocalAS.DispatcherPortRange.create(
          response.getDispatchedPortStart(), response.getDispatchedPortEnd());
    } catch (StatusRuntimeException e) {
      LOG.warn("ERROR getting dispatched_ports range from daemon: {}", e.getMessage());
      // Daemon doesn't support port range.
      return LocalAS.DispatcherPortRange.createEmpty();
    }
  }

  private static List<LocalAS.BorderRouter> readBorderRouterAddresses(
      DaemonServiceGrpc daemonService) {
    Set<String> borderRouterNames = new HashSet<>();
    List<LocalAS.BorderRouter> borderRouters = new ArrayList<>();
    for (Map.Entry<Long, Daemon.Interface> e : readInterfaces(daemonService).entrySet()) {
      String addr = e.getValue().getAddress().getAddress();
      int id = (int) (long) e.getKey();
      if (!borderRouterNames.contains(addr)) {
        borderRouterNames.add(addr);
        LocalAS.BorderRouter br = new LocalAS.BorderRouter(addr, new ArrayList<>());
        br.addInterface(new LocalAS.BorderRouterInterface(id, addr, null, 0, 0, null));
        borderRouters.add(br);
      }
    }
    return borderRouters;
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
}
