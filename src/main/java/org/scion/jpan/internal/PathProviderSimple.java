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
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SimplePathProvider simply provides the next best path. Lifecycle:<br>
 * 1) create PathProvider <br>
 * 2) subscribe()<br>
 * 3) connect()<br>
 * 4) disconnect()<br>
 * 5) unsubscribe()<br>
 *
 * <p>The PathProvider will periodically poll the ScionService for new paths. It will poll for new
 * path either if: a path is about to expire, or if the polling interval elapses, or if there is no
 * path available for a new subscriber.
 */
public class PathProviderSimple implements PathProvider {

  private static final Logger LOG = LoggerFactory.getLogger(PathProviderSimple.class.getName());
  private static final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);

  private final TimerTask timerTask;
  private final ScionService service;
  private long dstIsdAs;
  private InetSocketAddress dstAddress;
  private PathPolicy pathPolicy;
  private final ReplaceStrategy replaceStrategy;

  private PathUpdateCallback subscriber;
  private final Queue<Entry> faultyPaths = new ArrayDeque<>();
  private final TreeMap<Double, Entry> unusedPaths = new TreeMap<>();
  private Entry usedPath = null;

  private int configPathPollIntervalMs = 60_000;
  private int configExpirationMarginSec = 10;

  public enum ReplaceStrategy {
    /** Try to replace with new version of the same path, otherwise let expire. */
    EXACT_MATCH,
    /** Replace with the best ranked path available. */
    BEST_RANK,
    /**
     * Replace with the best ranked path available, but only if the current path is about to expire.
     */
    BEST_RANK_IF_EXPIRED,
  }

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

  public static PathProviderSimple create(
      ScionService service,
      PathPolicy policy,
      int pollIntervalMs,
      int expirationMarginSec,
      ReplaceStrategy strategy) {
    return new PathProviderSimple(service, policy, pollIntervalMs, expirationMarginSec, strategy);
  }

  private PathProviderSimple(
      ScionService service,
      PathPolicy policy,
      int pollIntervalMs,
      int expirationMarginSec,
      ReplaceStrategy strategy) {
    this.service = service;
    this.dstIsdAs = 0;
    this.dstAddress = null;
    this.pathPolicy = policy;
    this.configPathPollIntervalMs = pollIntervalMs;
    this.configExpirationMarginSec = expirationMarginSec;
    this.replaceStrategy = strategy;

    this.timerTask =
        new TimerTask() {
          @Override
          public void run() {
            boolean restart = true;
            try {
              restart = refreshAllPaths();
              // Path polling:
              // - We poll at a fixed interval.
              // - TODO generally, we should think about doing the polling centrally so that we
              //     reuse path requests to the same remote AS. Central polling could be done with
              //     subscriptions or with a central polling timer that consolidates polling to
              //     identical remote ASes.
              if (usedPath == null) {
                LOG.warn("No enough valid path available.");
              }
            } catch (Exception e) {
              String time = configPathPollIntervalMs + "ms";
              LOG.error("Exception in PathProvider timer task, trying again in " + time, e);
              e.printStackTrace();
            } finally {
              if (restart) {
                timer.schedule(timerTask, configPathPollIntervalMs, TimeUnit.MILLISECONDS);
              }
            }
          }
        };
  }

  // Synchronized because it is called by timer
  private synchronized boolean refreshAllPaths() {
    if (!isConnected()) {
      // We probably have been disconnected concurrently.
      return false; // TODO -1?
    }

    // Purpose:
    // 1) Get new paths from the service
    // 2) Discard paths that are about to expire
    // 3) Consider retry path that were broken

    // 1) Get new paths from the service
    Set<Path> newPaths2 = new HashSet<>(pathPolicy.filter(service.getPaths(dstIsdAs, dstAddress)));
    Map<Entry, Entry> newPaths = new HashMap<>();
    int n = 0;
    for (Path p : newPaths2) {
      // Avoid paths that are about to expire
      if (!aboutToExpireMs(p, configPathPollIntervalMs)) {
        Entry newEntry = new Entry(p, n++);
        newPaths.put(newEntry, newEntry);
      }
    }

    if (newPaths.isEmpty()) {
      return true;
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

    // Update path that are currently in use.
    if (replaceStrategy == ReplaceStrategy.EXACT_MATCH) {
      // Check used paths
      if (usedPath != null) {
        Entry newEntry = newPaths.get(usedPath);
        if (newEntry != null) {
          usedPath.set(newEntry);
          newPaths.remove(newEntry);
        } else {
          subscriber.pathsUpdated(getFreePath());
        }
      }
    } else if (replaceStrategy == ReplaceStrategy.BEST_RANK_IF_EXPIRED) {
      // Check used paths
      if (usedPath != null) {
        if (aboutToExpire(usedPath.path)) {
          // Path is about to expire, replace with best ranked path
          subscriber.pathsUpdated(getFreePath());
        } else {
          // Try to find the same path in the new list
          Entry newEntry = newPaths.get(usedPath);
          if (newEntry != null) {
            usedPath.set(newEntry);
            newPaths.remove(newEntry);
          }
        }
      }
    } else if (replaceStrategy == ReplaceStrategy.BEST_RANK) {
      // Replace path with the best available path
      if (usedPath != null) {
        subscriber.pathsUpdated(getFreePath());
      }
    } else {
      throw new IllegalStateException("Unknown replace strategy");
    }
    return true;
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
    if (e.path != p) {
      LOG.error("Path not managed by this provider");
      return;
    }
    e.setFaulty(Instant.now());
    faultyPaths.add(e);

    // Find new path
    subscriber.pathsUpdated(getFreePath());
  }

  @Override
  public void subscribe(PathUpdateCallback cb) {
    if (subscriber != null) {
      throw new IllegalStateException();
    }
    this.subscriber = cb;
  }

  @Override
  public synchronized void setPathPolicy(PathPolicy pathPolicy) {
    this.pathPolicy = pathPolicy;
    if (isConnected()) {
      if (usedPath != null) {
        // Remove used path if it doesn't fit the policy
        if (pathPolicy.filter(Collections.singletonList(usedPath.path)).isEmpty()) {
          usedPath = null;
        }
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

  private boolean aboutToExpire(Path path) {
    // TODO why is expiration part of the metadata? Server paths can also expire!
    long epochSeconds = path.getMetadata().getExpiration();
    return epochSeconds < Instant.now().getEpochSecond() + configExpirationMarginSec;
  }

  private boolean aboutToExpireMs(Path path, int expirationDeltaMs) {
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
    subscriber.pathsUpdated(getFreePath());

    if (aboutToExpireMs(path, configPathPollIntervalMs)) {
      // fetch new paths
      refreshAllPaths();
    }

    timer.schedule(timerTask, configPathPollIntervalMs, TimeUnit.MILLISECONDS);

    assertPathExists();
  }

  @Override
  public synchronized void disconnect() {
    Entry e = usedPath;
    if (e != null) {
      unusedPaths.put(e.rank, e);
    }

    timer.remove(timerTask);
    this.timerTask.cancel();
    this.dstAddress = null;
    this.dstIsdAs = 0;
    this.unusedPaths.clear();
    this.usedPath = null;
    this.faultyPaths.clear();
  }

  public boolean isConnected() {
    return this.dstAddress != null;
  }
}
