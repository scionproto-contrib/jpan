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

package org.scion.jpan.selectors;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.*;
import org.scion.jpan.internal.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The PathSelectorWithRefresh will periodically poll the ScionService for new paths. It will poll
 * for new path either if: a path is "about" to expire, or if the polling interval elapses, or if
 * there is no path available for a new subscriber.<br>
 * A path is considered to "about" to expire if it is going to expire its expiration date is before
 * ( now + {@link Constants#DEFAULT_PATH_EXPIRY_MARGIN} - {@link
 * Constants#DEFAULT_PATH_POLLING_INTERVAL}).
 *
 * <p>The current path will be replaced if a "better" path (according to the PathPolicy) is
 * available, even if the current path is still valid.
 *
 * @see PathSelector
 */
public class PathSelectorWithRefresh implements PathSelector {

  private static final Logger LOG =
      LoggerFactory.getLogger(PathSelectorWithRefresh.class.getName());
  private static final ScheduledThreadPoolExecutor timer;

  static {
    timer =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setDaemon(true);
              return thread;
            });
    timer.setRemoveOnCancelPolicy(true);
  }

  private final Runnable timerTask;
  private Future<?> timerFuture;
  private final ScionService service;
  private long dstIsdAs;
  private InetSocketAddress dstAddress = null;
  private PathPolicy pathPolicy;

  private final Map<Entry, Entry> faultyPaths = new HashMap<>();
  private final List<Entry> unusedPaths = new ArrayList<>();
  private Entry usedPath = null;

  private final int configPathPollIntervalMs;
  private int configExpirationMarginMs;

  private static class Entry {
    Path path;
    double rank;
    Instant timestamp;
    final long[] pathHashBase;
    final int hashCode;

    Entry(Path path, double rank) {
      this.path = path;
      this.rank = rank;

      pathHashBase = calcHashBase(path);
      hashCode = Arrays.hashCode(pathHashBase);
    }

    private long[] calcHashBase(Path path) {
      // The hashcode should depend on interfaces and ASes, but not on expiration time.
      long[] ret = new long[path.getMetadata().getInterfaces().size() * 2];
      int n = 0;
      for (PathMetadata.PathInterface i : path.getMetadata().getInterfaces()) {
        ret[n++] = i.getIsdAs();
        ret[n++] = i.getId();
      }
      return ret;
    }

    void setFaulty(Instant timestamp) {
      this.timestamp = timestamp;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      Entry other = (Entry) obj;
      return hashCode == other.hashCode && Arrays.equals(pathHashBase, other.pathHashBase);
    }

    /**
     * @param p Path
     * @return 'true' iff both paths have the same ISD/AS sequence and interface IDs. MAC codes,
     *     expiration dates and IP/port are ignored.
     */
    public boolean pathEquals(Path p) {
      if (Objects.deepEquals(path.getRawPath(), p.getRawPath())) {
        // The usual case, this is the same object or at least identical raw path (including
        // expiration dates).
        return true;
      }
      long[] hashBase2 = calcHashBase(p);
      return Objects.deepEquals(pathHashBase, hashBase2);
    }
  }

  public static PathSelectorWithRefresh create(
      ScionService service, PathPolicy policy, int expirationMarginMs, int pathPollIntervalMs) {
    return new PathSelectorWithRefresh(service, policy, expirationMarginMs, pathPollIntervalMs);
  }

  public static PathSelectorWithRefresh create(ScionService service, PathPolicy policy) {
    return new PathSelectorWithRefresh(
        service,
        policy,
        Config.getPathExpiryMarginSeconds() * 1000,
        Config.getPathPollingIntervalSeconds() * 1000);
  }

  private PathSelectorWithRefresh(
      ScionService service, PathPolicy policy, int expirationMarginMs, int pathPollIntervalMs) {
    if (service == null) {
      throw new IllegalArgumentException();
    }
    this.service = service;
    this.dstIsdAs = 0;
    this.dstAddress = null;
    this.pathPolicy = policy;
    this.configPathPollIntervalMs = pathPollIntervalMs;
    this.configExpirationMarginMs = expirationMarginMs;

    this.timerTask =
        new TimerTask() {
          @Override
          public void run() {
            try {
              synchronized (PathSelectorWithRefresh.this) {
                if (isConnected()) {
                  refreshPaths();
                }
              }
            } catch (Exception e) {
              String time = configPathPollIntervalMs + "ms";
              LOG.error("Exception in PathSelector timer task, trying again in {}", time, e);
            }
          }
        };
  }

  /** Refresh paths from path server. */
  // Synchronized because it is called by timer
  synchronized void refreshPaths() {
    // Purpose:
    // 1) Get new paths from the service
    // 2) Discard paths that are about to expire
    // 3) Consider retrying path that were broken TODO

    // 1) Get new paths from the service
    List<Path> newPaths2 = pathPolicy.filter(service.getPaths(dstIsdAs, dstAddress));
    unusedPaths.clear();
    int n = 0;
    for (Path p : newPaths2) {
      // Avoid paths that are about to expire
      if (!isExpiringInNextPeriod(p)) {
        Entry newEntry = new Entry(p, n++);
        unusedPaths.add(newEntry);
      }
    }

    if (unusedPaths.isEmpty()) {
      LOG.warn("No free path available.");
      return;
    }

    // We check all new path for whether they were reported faulty.
    // We also clean up the faulty list so it doesn't remove any paths that were not also
    // offered in the last request.
    List<Entry> newFaulty = new ArrayList<>();
    Iterator<Entry> itUnused = unusedPaths.iterator();
    while (itUnused.hasNext()) {
      Entry newEntry = itUnused.next();
      faultyPaths.computeIfPresent(
          newEntry,
          (k, v) -> {
            // In case we retry this path later.
            newEntry.setFaulty(v.timestamp);
            newFaulty.add(newEntry);
            // Remove from list of path that are free to use.
            itUnused.remove();
            return v;
          });
    }
    faultyPaths.clear();
    newFaulty.forEach(e -> faultyPaths.put(e, e));

    if (unusedPaths.isEmpty()) {
      // try faulty paths again -> ordered by how long ago they were reported faulty
      faultyPaths.forEach((k, v) -> unusedPaths.add(v));
      unusedPaths.sort(Comparator.comparing(e -> e.timestamp));
      unusedPaths.forEach(e -> e.timestamp = null);
      faultyPaths.clear();
    }

    // Replace current path with the best available path.
    findFreePath();
  }

  private void findFreePath() {
    usedPath = unusedPaths.remove(0);
  }

  /**
   * Report paths as faulty. The algorithm is pretty simple: This method tags all paths as faulty
   * that use the ISD/AS and at least one of the interfaces that are reported in the error.
   *
   * <p>A more advanced algorithm could also de-rank any path through an affected AS, even if other
   * interfaces are used (especially if internal connectivity is affected) or when the AS is
   * addressed through a different ISD.
   *
   * @param error The SCMP error.
   */
  @Override
  public synchronized void reportError(Scmp.ErrorMessage error) {
    long faultyIsdAs;
    long ifId1;
    Long ifId2 = null;
    // Only errors 5 and 6 give us useful information
    if (error instanceof Scmp.Error5Message) {
      Scmp.Error5Message error5 = (Scmp.Error5Message) error;
      faultyIsdAs = error5.getIsdAs();
      ifId1 = error5.getInterfaceId();
    } else if (error instanceof Scmp.Error6Message) {
      Scmp.Error6Message error6 = (Scmp.Error6Message) error;
      faultyIsdAs = error6.getIsdAs();
      ifId1 = error6.getIngressId();
      ifId2 = error6.getEgressId();
    } else {
      return;
    }

    // Mark unused paths with faulty interfaces as faulty
    Iterator<Entry> unusedIter = unusedPaths.iterator();
    while (unusedIter.hasNext()) {
      Entry e = unusedIter.next();
      PathMetadata meta = e.path.getMetadata();
      if (ScionUtil.isPathUsingInterface(meta, faultyIsdAs, ifId1)
          || (ifId2 != null && ScionUtil.isPathUsingInterface(meta, faultyIsdAs, ifId2))) {
        unusedIter.remove();
        e.setFaulty(Instant.now());
        faultyPaths.put(e, e);
      }
    }

    // Mark used paths with faulty interfaces as faulty
    PathMetadata usedMeta = usedPath.path.getMetadata();
    if (ScionUtil.isPathUsingInterface(usedMeta, faultyIsdAs, ifId1)
        || (ifId2 != null && ScionUtil.isPathUsingInterface(usedMeta, faultyIsdAs, ifId2))) {
      Entry e = usedPath;
      usedPath = null;
      e.setFaulty(Instant.now());
      faultyPaths.put(e, e);
      // Find new path
      if (unusedPaths.isEmpty()) {
        refreshPaths();
        return;
      }
      findFreePath();
    }
  }

  @Override
  public synchronized PathPolicy getPathPolicy() {
    return pathPolicy;
  }

  @Override
  public synchronized void setPathPolicy(PathPolicy pathPolicy) {
    this.pathPolicy = pathPolicy;
    if (isConnected()) {
      // Remove used path if it doesn't fit the policy
      if (usedPath != null
          && pathPolicy.filter(Collections.singletonList(usedPath.path)).isEmpty()) {
        usedPath = null;
      }

      refreshPaths();
    }
  }

  private boolean isExpiringInNextPeriod(Path path) {
    int expirationDeltaMs = configPathPollIntervalMs - configExpirationMarginMs;
    long epochSeconds = path.getMetadata().getExpiration();
    return epochSeconds < Instant.now().getEpochSecond() + expirationDeltaMs / 1000;
  }

  @Override
  public synchronized Path getPath() {
    return usedPath == null ? null : usedPath.path;
  }

  @Override
  public InetSocketAddress getRemoteSocketAddress() {
    return dstAddress;
  }

  @Override
  public long getRemoteIsdAs() {
    return dstIsdAs;
  }

  @Override
  public synchronized void connect(ScionSocketAddress remote) {
    if (isConnected()) {
      throw new IllegalStateException("Path provider is already connected");
    }
    this.dstIsdAs = remote.getIsdAs();
    this.dstAddress = remote;

    // fetch new paths
    refreshPaths();

    timerFuture =
        timer.scheduleAtFixedRate(
            timerTask, configPathPollIntervalMs, configPathPollIntervalMs, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void connect(Path path) {
    if (isConnected()) {
      throw new IllegalStateException("Path provider is already connected");
    }
    this.dstIsdAs = path.getRemoteIsdAs();
    this.dstAddress = path.getRemoteSocketAddress();

    if (isExpiringInNextPeriod(path)) {
      // fetch new paths
      refreshPaths();
    } else {
      // use this path
      unusedPaths.add(new Entry(path, 0.0));
      findFreePath();
    }

    timerFuture =
        timer.scheduleAtFixedRate(
            timerTask, configPathPollIntervalMs, configPathPollIntervalMs, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void disconnect() {
    Entry e = usedPath;
    if (e != null) {
      unusedPaths.add(e);
      usedPath = null;
    }

    if (timerFuture != null) {
      timerFuture.cancel(true);
      timerFuture = null;
    }
    this.dstAddress = null;
    this.dstIsdAs = 0;
    this.unusedPaths.clear();
    this.usedPath = null;
    this.faultyPaths.clear();
  }

  @Override
  public void setExpirationSafetyMargin(int cfgExpirationSafetyMargin) {
    configExpirationMarginMs = cfgExpirationSafetyMargin * 1000;
  }

  public synchronized boolean isConnected() {
    return this.dstAddress != null;
  }

  static int getQueueSize() {
    return timer.getQueue().size();
  }
}
