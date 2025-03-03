// Copyright 2025 ETH Zurich
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

import java.net.InetSocketAddress;
import java.util.List;
import org.scion.jpan.*;

/**
 * Scenarios to consider: - A policy may change ordering of paths (e.g. live latency measurements) -
 * A policy may or may not be used concurrently by multiple channels/sockets. We can probably assume
 * that concurrent usage doesn't coincide with live updates. - live updates make only sense if the
 * destination AS is known and fixed - concurrent usage is much less likely to go to the same AS
 *
 * <p>Q: facility to blacklist paths? -> Less useful when combined with live measurements.
 *
 * <p>We should always return a list of paths, e.g. useful for - Multi-path - Dropping a path if it
 * turns out to be erroneous
 *
 * <p>Idea: - A path "filter" is static, i.e. has no state (or at least no state that can change).
 * It can do ordering by metadata or apply requirements based on metadata - A path "supplier" has a
 * destination. It can refresh paths if they expire. It can do live measurements to reorder paths or
 * remove them.
 *
 * <p>A supplier may just have a path container. We just call get() to get a path everytime we need
 * a path. SimpleSupplier:
 *
 * <p>Dynamic supplier: - refreshNow()? refreshAllExpired()? - hasNew()? This doesn't really work in
 * a concurrent setting. Maybe hasNew(dateLastUsed)? Or register a callback? - getPath()/getPaths()
 * - Should be able to automatically use a given Service. - concurrent usage? - Should be able to
 * process live metrics from actual traffic? E>g. traffic gets slower -> find another paths with
 * lower latency (assume it is a path problem, not a server problem)
 *
 * <p>Static supplier? - Has a service, refreshes when expired. - Possibility to blacklist paths (or
 * directly handle SCMP errors)
 *
 * <p>Multi-AS thoughts supplier should use a service (where the service should in future be able to
 * hold multiple ASes) or a service factory (use daemon/topofile/...) where each new service has
 * exactly one AS...? (that doesn't work with topo files...). -> manually supplied topofiles are
 * unlikely in an AS-switching environment AS switching occurs for mobile devices, .i.e without
 * daemon or manual topo files).
 *
 * @deprecated Needs work.
 */
@Deprecated
public class PathPolicyHandler {

  private final ScionService service;
  private final ScionAddress dst;
  private final int dstPort;
  private final PathPolicy policy;
  private List<Path> paths;

  private PathPolicyHandler(ScionService service, ScionAddress dst, int port, PathPolicy policy) {
    this.service = service;
    this.dst = dst;
    this.dstPort = port;
    this.policy = policy;
    init();
  }

  public static PathPolicyHandler with(
      ScionService service, InetSocketAddress dst, PathPolicy policy) throws ScionException {
    ScionAddress scionDst =
        AddressLookupService.lookupAddress(dst.getHostName(), service.getLocalIsdAs());
    return new PathPolicyHandler(service, scionDst, dst.getPort(), policy);
  }

  public static PathPolicyHandler with(
      ScionService service, long isdAs, InetSocketAddress dst, PathPolicy policy) {
    return new PathPolicyHandler(
        service, ScionAddress.create(isdAs, dst.getAddress()), dst.getPort(), policy);
  }

  private void init() {
    paths = service.getPaths(dst.getIsdAs(), dst.getInetAddress(), dstPort);
  }

  public Path getCurrent() {
    // Return first path.
    // - check if it is about to expire -> schedule refresh
    // - check if it is expired -> refresh and try to look at next path
    // - no next path? -> refresh and return first (may be null? / exception if empty?).

    for (Path path : paths) {
      // check expiration
      if (path.getMetadata().getExpiration() < System.currentTimeMillis()) {
        // refresh
        paths = service.getPaths(dst.getIsdAs(), dst.getInetAddress(), dstPort);
        return getCurrent();
      }
    }

    // TODO refresh and try again or simply throw?
    throw new ScionRuntimeException("No path available to " + dst);
  }

  public void skip() {}
}
