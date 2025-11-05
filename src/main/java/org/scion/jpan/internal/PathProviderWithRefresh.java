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
 * ( now + {@link Constants#DEFAULT_PATH_EXPIRY_MARGIN} -
 * {@link Constants#DEFAULT_PATH_POLLING_INTERVAL}).
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
  private final Queue<Entry> faultyPaths = new ArrayDeque<>();
  private final TreeMap<Double, Entry> unusedPaths = new TreeMap<>();
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

    public void set(Entry other) {
      this.path = other.path;
      this.rank = other.rank;
      if (this.timestamp != null
          || hashCode != other.hashCode
          || !Arrays.equals(pathHashBase, other.pathHashBase)) {
        // Preserve faulty timestamp
        throw new IllegalStateException();
      }
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
                refreshAllPaths();
              }
            } catch (Exception e) {
              String time = configPathPollIntervalMs + "ms";
              LOG.error("Exception in PathProvider timer task, trying again in {}", time, e);
            }
          }
        };
  }

  // Synchronized because it is called by timer
  private synchronized void refreshAllPaths() {
    // Purpose:
    // 1) Get new paths from the service
    // 2) Discard paths that are about to expire
    // 3) Consider retrying path that were broken

    // 1) Get new paths from the service
    List<Path> newPaths2 = pathPolicy.filter(service.getPaths(dstIsdAs, dstAddress));
    Map<Entry, Entry> newPaths = new HashMap<>();
    int n = 0;
    for (Path p : newPaths2) {
      // Avoid paths that are about to expire
      if (!isExpiringInNextPeriod(p)) {
        Entry newEntry = new Entry(p, n++);
        newPaths.put(newEntry, newEntry);
      }
    }

    if (newPaths.isEmpty()) {
      return;
    }

    // Clean up faulty list
    Iterator<Entry> faultyIter = faultyPaths.iterator();
    while (faultyIter.hasNext()) {
      Entry e = faultyIter.next();
      Entry newEntry = newPaths.get(e);
      if (newEntry == null) {
        // Path has no new version, remove it.
        faultyIter.remove();
      } else {
        // In case we retry this path later.
        e.set(newEntry);
      }
    }

    // update unused path.
    unusedPaths.clear();
    for (Entry e : newPaths.values()) {
      unusedPaths.put(e.rank, e);
    }

    // Replace current path with the best available path.
    subscriber.updatePath(getFreePath());
  }

  private Path getFreePath() {
    if (unusedPaths.isEmpty()) {
      // No new path available
      LOG.warn("No free path available.");
      return null;
    }
    Entry e = unusedPaths.pollFirstEntry().getValue();
    usedPath = e;
    return e.path;
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
    faultyPaths.add(e);

    // Find new path
    subscriber.updatePath(getFreePath());
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

      refreshAllPaths();
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

    // use this path
    unusedPaths.put(0.0, new Entry(path, 0.0));
    subscriber.updatePath(getFreePath());

    if (isExpiringInNextPeriod(path)) {
      // fetch new paths
      refreshAllPaths();
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
      unusedPaths.put(e.rank, e);
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
