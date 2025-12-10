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
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The PathProviderWithRefresh will periodically poll the ScionService for new paths. It will poll
 * for new path either if: a path is "about" to expire, or if the polling interval elapses, or if
 * there is no path available for a new subscriber.<br>
 * A path is considered to "about" to expire if it is going to expire its expiration date is before
 * ( now + {@link Constants#DEFAULT_PATH_EXPIRY_MARGIN} - {@link
 * Constants#DEFAULT_PATH_POLLING_INTERVAL}).
 *
 * <p>The current path will be replaced if a "better" path (according to the PathPolicy) is
 * available, even if the current path is still valid.
 *
 * @see org.scion.jpan.internal.PathProvider
 */
public class PathProviderWithRefresh implements PathProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(PathProviderWithRefresh.class.getName());
  private static final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);

  static {
    timer.setRemoveOnCancelPolicy(true);
  }

  private final Runnable timerTask;
  private Future<?> timerFuture;
  private final ScionService service;
  private long dstIsdAs;
  private InetSocketAddress dstAddress;
  private PathPolicy pathPolicy;

  private PathUpdateCallback subscriber;
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

      // The hashcode should depend on interfaces and ASes, but not on expiration time.
      pathHashBase = new long[path.getMetadata().getInterfacesList().size() * 2];
      int n = 0;
      for (PathMetadata.PathInterface i : path.getMetadata().getInterfacesList()) {
        pathHashBase[n++] = i.getIsdAs();
        pathHashBase[n++] = i.getId();
      }
      hashCode = Arrays.hashCode(pathHashBase);
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
  }

  public static PathProviderWithRefresh create(
      ScionService service, PathPolicy policy, int expirationMarginMs, int pathPollIntervalMs) {
    return new PathProviderWithRefresh(service, policy, expirationMarginMs, pathPollIntervalMs);
  }

  public static PathProviderWithRefresh create(ScionService service, PathPolicy policy) {
    return new PathProviderWithRefresh(
        service,
        policy,
        Config.getPathExpiryMarginSeconds() * 1000,
        Config.getPathPollingIntervalSeconds() * 1000);
  }

  private PathProviderWithRefresh(
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
              if (isConnected()) {
                refreshPaths();
              }
            } catch (Exception e) {
              String time = configPathPollIntervalMs + "ms";
              LOG.error("Exception in PathProvider timer task, trying again in {}", time, e);
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
    Set<Entry> faultySet = faultyPaths.keySet();
    List<Entry> newFaulty = new ArrayList<>();
    Iterator<Entry> itUnused = unusedPaths.iterator();
    while (itUnused.hasNext()) {
      Entry newEntry = itUnused.next();
      if (faultySet.contains(newEntry)) {
        // In case we retry this path later.
        newFaulty.add(newEntry);
        // Remove from list of path that are free to use.
        itUnused.remove();
      }
    }
    faultyPaths.clear();
    newFaulty.forEach(e -> faultyPaths.put(e, e));

    if (unusedPaths.isEmpty()) {
      // try faulty paths again -> ordered by how long ago they were reported faulty
      faultyPaths.forEach((k, v) -> unusedPaths.add(v));
      unusedPaths.sort(Comparator.comparing(e -> e.rank));
      unusedPaths.forEach(e -> e.timestamp = null);
      faultyPaths.clear();
    }

    // Replace current path with the best available path.
    subscriber.updatePath(getFreePath());
  }

  private Path getFreePath() {
    Entry e = unusedPaths.remove(0);
    usedPath = e;
    return e.path;
  }

  private void updateSubscriber() {
    if (unusedPaths.isEmpty()) {
      refreshPaths();
      return;
    }
    subscriber.updatePath(getFreePath());
  }

  @Override
  public synchronized void reportFaultyPath(Path p) {
    Entry e = usedPath;
    if (e == null) {
      throw new IllegalArgumentException("Path not managed by this provider");
    }
    if (!Objects.equals(e.path, p)) {
      // This can happen due to races, e.g. when we receive an error for a path that we stopped
      // using.
      return;
    }
    e.setFaulty(Instant.now());
    faultyPaths.put(e, e);

    // Find new path
    updateSubscriber();
  }

  @Override
  public void subscribe(PathUpdateCallback cb) {
    if (subscriber != null) {
      throw new IllegalStateException();
    }
    this.subscriber = cb;
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
      assertPathExists();
    }
  }

  private void assertPathExists() {
    if ((usedPath == null || isExpired(usedPath.path)) && unusedPaths.isEmpty()) {
      String isdAs = ScionUtil.toStringIA(dstIsdAs);
      throw new ScionRuntimeException("No path found to destination: " + isdAs + "," + dstAddress);
    }
  }

  private boolean isExpiringInNextPeriod(Path path) {
    int expirationDeltaMs = configPathPollIntervalMs - configExpirationMarginMs;
    long epochSeconds = path.getMetadata().getExpiration();
    return epochSeconds < Instant.now().getEpochSecond() + expirationDeltaMs / 1000;
  }

  private boolean isExpired(Path path) {
    return path.getMetadata().getExpiration() < Instant.now().getEpochSecond();
  }

  @Override
  public void connect(Path path) {
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
      updateSubscriber();
    }

    timerFuture =
        timer.scheduleAtFixedRate(
            timerTask, configPathPollIntervalMs, configPathPollIntervalMs, TimeUnit.MILLISECONDS);

    assertPathExists();
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

  public boolean isConnected() {
    return this.dstAddress != null;
  }

  static int getQueueSize() {
    return timer.getQueue().size();
  }
}
