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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.scion.jpan.*;

/**
 * Scenarios to consider:<br>
 * - A policy may change ordering of paths (e.g. live latency measurements)<br>
 * - A policy may or may not be used concurrently by multiple channels/sockets. We can probably
 * assume that concurrent usage doesn't coincide with live updates.<br>
 * - live updates make only sense if the destination AS is known and fixed<br>
 * - concurrent usage is much less likely to go to the same AS<br>
 *
 * <p>Q: facility to blacklist paths? -> Less useful when combined with live measurements.
 *
 * <p>We should always return a list of paths, e.g. useful for<br>
 * - Multi-path <br>
 * - Dropping a path if it turns out to be erroneous<br>
 *
 * <p>Idea:<br>
 * - A path "filter" is static, i.e. has no state (or at least no state that can change). It can do
 * ordering by metadata or apply requirements based on metadata<br>
 * - A path "supplier" has a destination. It can refresh paths if they expire. It can do live
 * measurements to reorder paths or remove them.<br>
 *
 * <p>A supplier may just have a path container. We just call get() to get a path everytime we need
 * a path. SimpleSupplier:
 *
 * <p>Dynamic supplier:<br>
 * - refreshNow()? refreshAllExpired()?<br>
 * - hasNew()? This doesn't really work in a concurrent setting. Maybe hasNew(dateLastUsed)? Or
 * register a callback?<br>
 * - getPath()/getPaths()<br>
 * - Should be able to automatically use a given Service.<br>
 * - concurrent usage?<br>
 * - Should be able to process live metrics from actual traffic? E>g. traffic gets slower -> find
 * another paths with lower latency (assume it is a path problem, not a server problem)
 *
 * <p>Static supplier?<br>
 * - Has a service, refreshes when expired.<br>
 * - Possibility to blacklist paths (or directly handle SCMP errors)
 *
 * <p>Multi-AS thoughts supplier should use a service (where the service should in future be able to
 * hold multiple ASes) or a service factory (use daemon/topofile/...) where each new service has
 * exactly one AS...? (that doesn't work with topo files...). -> manually supplied topofiles are
 * unlikely in an AS-switching environment AS switching occurs for mobile devices, i.e. without
 * daemon or manual topo files).
 *
 * <p>send()/write()<br>
 * - send(x, SSA) should send to given path (potentially use map with refreshed paths, see
 * ScionDatagramChannel)<br>
 * - send(x, ISA) should use PP to get path <br>
 * - write(x) should use PP to get path <br>
 *
 * <p>Unfortunately, the PathPolicyHandler works only for a fixed destination, i.e. only for
 * connect()/write(). Using it for send() would require multiple instances, one for each
 * destination.
 *
 * <p>Expected behaviour of send()/write():<br>
 * - send(x, SSA): autorefresh path? -> could be cached in "refreshedMap". Attach PathPolicy to SSA?
 * Or we could try to refresh, looking for identical paths to what SSA contains....<br>
 * - send(x, Path): autorefresh path? MUST try to use identical path. could be cached in
 * "refreshedMap"<br>
 * - send(x, ISA): lookup path. Could be cached. Uses PathPolicy<br>
 * - connect(path): autorefresh path? MUST try to use identical path!<br>
 * - connect(ISA): lookup path. Uses PathPolicy<br>
 *
 * <p>autorefresh: How to deal with dynamic path policies?<br>
 * With autorefresh, we should just ignore paths that are close to expiry....?<br>
 * We need to document the behaviour:<br>
 * - send(x, Path) / connect(Path) is the strictest, it will automatically refresh the path, but
 * only with identical paths.<br>
 * - send(x, ISA) / connect(ISA) will use the path policy to get a path.<br>
 * - send(x, SSA) needs to be decided. We could attach a PathPolicy, but we could also try to find
 * identical paths (emulating the path policy). We could also decide to use this only for
 * ResponsePaths....?<br>
 *
 * <p>The dynamic path policy should handle expiry internally. If we get 100 path, some of them will
 * always be close to expiry. We should probably ignore them.<br>
 * - The DPP acts as a path cache. How often should we refresh? -> Good question. <br>
 * - try refreshing. How does it work? At what point can we expect a fresh path from the CS? How
 * close must it be to expiry?<br>
 *
 *
 *
 * -------------------------- September 2025 --------------------------
 * What does this class do:
 * - If connected: provides paths to the connected destination according to the path policy.
 * - ONLY for request paths / only on client
 * - Behavior:
 *   - send(path) -> just use path (maybe check expiry?)
 *            --> DEPRECATE!? Or keep for advanced use, e.g. use specific path or fail..? -> remove.
 *   - send(isa) -> resolve + get path according to policy.
 *                  Cache multiple providers? Oner per ISA?????
 *                  -> Configurable cache size. WARN if it runs out... WEAK?
 *   - send(SSA) -> just use path (maybe check expiry?)
 *   - write() -> get path according to policy
 *
 *   - connect(isa) -> resolve and get paths according to policy ????????????????
 *   - connect(SSA) -> Use path and replace according to policy??
 *                     Replace with identical if possible? Otherwise?
 *   - connect(path) -> do same as connect(SSA) or, set has default path (maybe check expiry?)  --> DEPRECATE!
 *   - disconnect() -> forget paths + provider
 *
 * - Different providers:
 *   - Single provider -> Single path, no refresh -> RefreshPolicy.OFF
 *   - SingleRenewing provider -> Single path, refresh on expiry -> RefreshPolicy.SAME_LINKS
 *   - Renewing provider -> Multiple paths, use path policy, refresh on expiry -> RefreshPolicy.POLICY
 *
 *
 * - PathPolicy
 *   - Should SSA have a path policy?
 *
 * @deprecated Needs work.
 */
@Deprecated
public class PathPolicyHandler {

  private final ScionService service;
  private ScionAddress dst;
  private int dstPort;
  private PathPolicy policy;
  private List<Path> paths;
  private Instant lastRefresh;
  private static final int REFRESH_MIN_INTERVAL_SECONDS = 1;
  private RefreshPolicy mode;

  public enum RefreshPolicy {
    /** No refresh. */
    OFF,
    /** Refresh with path along the same links. */
    SAME_LINKS,
    /** Refresh with path following path policy. */
    POLICY
  }

  private PathPolicyHandler(ScionService service, PathPolicy policy, RefreshPolicy mode) {
    this.service = service;
    this.policy = policy;
    this.mode = mode;
  }

  public static PathPolicyHandler create(ScionService service, PathPolicy policy, RefreshPolicy mode) {
    return new PathPolicyHandler(service, policy, mode);
  }

  public void connect(InetSocketAddress dst) throws ScionException {
    ScionAddress scionDst =
        AddressLookupService.lookupAddress(dst.getHostName(), service.getLocalIsdAs());
    connect(scionDst, dst.getPort());
  }

  public void connect(long isdAs, InetSocketAddress dst) throws ScionException {
    connect(ScionAddress.create(isdAs, dst.getAddress()), dst.getPort());
  }

  public void connect(ScionAddress dst, int port) {
    this.dst = dst;
    this.dstPort = port;
    refreshPaths();
  }

  public void connect(Path path) {
    this.dst = ScionAddress.create(path.getRemoteIsdAs(), path.getRemoteAddress());
    this.paths = new ArrayList<>();
    this.paths.add(path);
    // set 'path' as first choice...
    // TODO trigger refresh? Maybe check for expire and trigger if expired?
  }

  public void setMode(RefreshPolicy mode) {
    this.mode = mode;
  }

  public void disconnect() {
    dst = null;
    dstPort = -1;
    paths = null;
  }

  public void setPathPolicy(PathPolicy policy) {
    this.policy = policy;
    refreshPaths();
  }

  private void refreshPaths() {
    paths = service.getPaths(dst.getIsdAs(), dst.getInetAddress(), dstPort);
    lastRefresh = Instant.now();
  }

  public Path getCurrent(RefreshPolicy refreshPolicy, int expiryMargin) {
    if (dst == null) {
      // not connected
      return null;
    }
    // TODO remove? Or merge with ==null?
    if (paths.isEmpty()) {
      throw new ScionRuntimeException("No path available to " + dst);
    }

    RequestPath path = (RequestPath) paths.get(0);
    if (Instant.now().getEpochSecond() + expiryMargin <= path.getMetadata().getExpiration()) {
      return path;
    }

    // expired, get new path
    switch (refreshPolicy) {
      case OFF:
        // let this pass until it is ACTUALLY expired -> SCMP error
        return path;
      case POLICY:
        return applyFilter(service.getPaths(path), path.getRemoteSocketAddress()).get(0);
      case SAME_LINKS:
        List<Path> paths2 = service.getPaths(path);
        paths2 = applyFilter(paths2, path.getRemoteSocketAddress());
        return findPathSameLinks(paths2, path);
      default:
        throw new UnsupportedOperationException();
    }
  }

  private List<Path> applyFilter(List<Path> paths, Object address) throws ScionRuntimeException {
    List<Path> filtered = policy.filter(paths);
    if (filtered.isEmpty()) {
      String isdAs = ScionUtil.toStringIA(paths.get(0).getRemoteIsdAs());
      throw new ScionRuntimeException("No path found to destination: " + isdAs + " --- " + address);
    }
    return filtered;
  }

  private RequestPath findPathSameLinks(List<Path> paths, RequestPath path) {
    List<PathMetadata.PathInterface> reference = path.getMetadata().getInterfacesList();
    for (Path newPath : paths) {
      List<PathMetadata.PathInterface> ifs = newPath.getMetadata().getInterfacesList();
      if (ifs.size() != reference.size()) {
        continue;
      }
      boolean isSame = true;
      for (int i = 0; i < ifs.size(); i++) {
        // In theory we could compare only the first ISD/AS and then only Interface IDs....
        PathMetadata.PathInterface if1 = ifs.get(i);
        PathMetadata.PathInterface if2 = reference.get(i);
        if (if1.getIsdAs() != if2.getIsdAs() || if1.getId() != if2.getId()) {
          isSame = false;
          break;
        }
      }
      if (isSame) {
        return (RequestPath) newPath;
      }
    }
    return null;
  }

  public void skip() {}
}
