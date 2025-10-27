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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SimplePathProvider simply provides the next best path. Lifecycle:<br>
 * 1) create PathProvider <br>
 * 2) subscribe<br>
 * 3) connect<br>
 * 4) disconnect<br>
 * 5) unsubscribe<br>
 *
 * <p>The PathProvider will periodically poll the ScionService for new paths. It will poll for new
 * path either if: a path is about to expire, or if the polling interval elapses, or if there is no
 * path available for a new subscriber.
 */
public class SimplePathProvider3 implements PathProvider3 {

  private static final Logger LOG = LoggerFactory.getLogger(SimplePathProvider3.class.getName());
  private static final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);

  private TimerTask timerTask;
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
    @Deprecated
    IGNORE_EXPIRED
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

  public static SimplePathProvider3 create(
      ScionService service,
      PathPolicy policy,
      int pollIntervalMs,
      int expirationMarginSec,
      ReplaceStrategy strategy) {
    return new SimplePathProvider3(service, policy, pollIntervalMs, expirationMarginSec, strategy);
  }

  private SimplePathProvider3(
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
  }

  // Synchronized because it is called by timer
  private synchronized long refreshAllPaths() {
    if (!isConnected()) {
      // We probably have been disconnected concurrently.
      return 0; // TODO -1?
    }
    if (replaceStrategy == ReplaceStrategy.IGNORE_EXPIRED) {
      return Instant.now().plus(60, ChronoUnit.SECONDS).getEpochSecond();
    }
    long nextExpiration = Long.MAX_VALUE;
    // Purpose:
    // 1) Get new paths from the service
    // 2) Discard paths that are about to expire
    // 3) Consider retry path that were broken

    // 1) Get new paths from the service
    Set<Path> newPaths2 = new HashSet<>(pathPolicy.filter(service.getPaths(dstIsdAs, dstAddress)));
    Map<Entry, Entry> newPaths = new HashMap<>();
    int n = 0;
    for (Path p : newPaths2) {
      // Avoid paths that are about o expire
      if (!aboutToExpire(p)) {
        Entry newEntry = new Entry(p, n++);
        newPaths.put(newEntry, newEntry);
        // TODO measure only expiration of paths that are actually in use?!??!!
        nextExpiration = Math.min(nextExpiration, p.getMetadata().getExpiration());
      }
    }

    if (newPaths.isEmpty()) {
      LOG.warn("No enough valid path available.");
      //      if (usedPath != null && isExpired(usedPath.path)) {
      //        subscriber.pathsUpdated(null);
      //        usedPath = null;
      //      }
      return 1000; // TODO!!!
      // TODO replace the ones closest to expiration, try again later.
    }

    // Unused path: Replace list of unused paths with new paths (after removing used paths and
    // faulty paths)
    // Used paths: Replace list. Use callback to replace paths.
    //   Replace immediately if old path is not available anymore.
    //   Maybe: delay until the path is about to expire before the next path fetching?
    // Faulty paths: Remove from list if path is not available anymore
    //   Maybe: try to reuse faulty paths? Depending on ranking?

    // We could simply assign new paths to used paths, but this would cause unnecessary path
    // changes.
    // We try to maintain paths until they are expired or faulty.

    // Clean up faulty list
    Iterator<Entry> faultyIter = faultyPaths.iterator();
    while (faultyIter.hasNext()) {
      Entry e = faultyIter.next();
      Entry newEntry = newPaths.get(e);
      if (newEntry == null) {
        // TODO why not completely clear the list? Except maybe replace faulty once tih identical
        // refreshed ones?
        faultyIter.remove();
      }
    }

    // update unused path.
    unusedPaths.clear();
    for (Entry e : newPaths.values()) {
      unusedPaths.put(e.rank, e);
    }

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

    return nextExpiration;
  }

  private Path getFreePath() {
    if (unusedPaths.isEmpty()) {
      // No new path available
      LOG.warn("No free path available.");
      // TODO can we do this without causing recursive calls?
      //    -> refreshAllPaths();
      // TODO fetch new paths, especially if we only have a single path
      // TODO try using faulty paths that might have recovered
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
      // TODO ignore?
      throw new IllegalArgumentException("Path not managed by this provider");
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
      refreshAllPaths();
      if ((usedPath == null || isExpired(usedPath.path)) && unusedPaths.isEmpty()) {
        String isdAs = ScionUtil.toStringIA(dstIsdAs);
        throw new ScionRuntimeException(
            "No path found to destination: " + isdAs + "," + dstAddress);
      }
    }
  }

  private boolean aboutToExpire(Path path) {
    // TODO why is expiration part of the metadata? Server paths can also expire!
    long epochSeconds = path.getMetadata().getExpiration();
    return epochSeconds - configExpirationMarginSec < Instant.now().getEpochSecond();
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
    long nextExpiration = path.getMetadata().getExpiration();

    if (aboutToExpire(path)) {
      // fetch new paths
      nextExpiration = refreshAllPaths();
    }

    // Scenarios:
    // 1) Multipath: We connect to a destination ISD/AS/IP. Multiple subscribers automatically get
    // paths
    //    connect(...) seems unnecessary, maybe without argument?
    // 2) SinglePath with connect(IP): We connect to a specific ISD/AS/IP.
    //    This overrides anything done during subscribe().
    // 3) SinglePath with connect(Path): We connect with a specific path to ISD/AS/IP.
    //    Similar to 2) this overrides subscribe(). In addition it sets filters for a specific path.
    // 4) SinglePath with send(). Should we differ between RequestPath vs ResponsePath?
    //    No -> This would violate polymorphism and makes it hard to understand...?
    //    We could just allow automatic renewal....

    // Summary:
    // - Subscribe: sets ScionService and other settings. Mostly immutable(?)
    // - Connect: sets ISD/AS/IP

    // Multipath: we should use multiple subscribe(?) and a single connect().
    //            If we do multipath with a single path, we can do with a single subscribe()
    //            -> Do we need multiple sockets for multi path? A single socket used concurrently?
    // - Latency-MP: Can definitely be done with a single socket. SHOULD be done with single socket.
    // - Redundancy-MP: Can be done with single socket.
    // - Bandwidth-MP: ....?

    //

    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // TODO
    // Conclusion 2025-10-10
    // Most cases will require only a single subscriber.
    // For sake of simplicity:
    // We support only one socket/channel per PathProvider. This supports most use-cases and
    // keeps the API/implementation simple.
    // Concurrent transfer (bandwidth or redundancy) should be done in separate implementations
    // anyway. These would probably require a packet scheduler and not support send(path/IP).

    this.timerTask =
        new TimerTask() {
          @Override
          public void run() {
            try {
              try {
                long nextExpiration = refreshAllPaths();
                long delaySec =
                    nextExpiration - Instant.now().getEpochSecond() - configExpirationMarginSec;
                if (timerTask != null) {
                  timer.schedule(
                      timerTask, delaySec > 0 ? delaySec * 1000L : 100L, TimeUnit.MILLISECONDS);
                }
              } catch (Exception e) {
                LOG.error("Exception in PathProvider timer task, trying again in 10sec", e);
                e.printStackTrace();
                // Trying again in 10sec
                timer.schedule(timerTask, 10, TimeUnit.SECONDS);
              }
            } catch (Throwable t) {
              LOG.error("Throwable in PathProvider timer task, stopping", t);
              t.printStackTrace();
            }
          }
        };

    long delaySec = nextExpiration - Instant.now().getEpochSecond() - configExpirationMarginSec;
    // TODO is 100 milliseconds good?
    ScheduledFuture f =
        timer.schedule(timerTask, delaySec > 0 ? delaySec * 1000L : 100L, TimeUnit.MILLISECONDS);
    // TODO consider cancelling via f.cancel()
  }

  @Override
  public synchronized void disconnect() {
    //    if (!isConnected()) {
    //      throw new IllegalStateException("Path provider is not connected");
    //    }
    Entry e = usedPath;
    if (e != null) {
      unusedPaths.put(e.rank, e);
    }

    if (timerTask != null) {
      timer.remove(timerTask);
      timerTask.cancel();
    }
    //    this.timerTask = null;
    this.dstAddress = null;
    this.dstIsdAs = 0;
    this.unusedPaths.clear();
    this.usedPath = null;
    this.faultyPaths.clear();
  }

  @Override
  public long getIsdAs() {
    return dstIsdAs;
  }

  @Override
  public InetSocketAddress getAddress() {
    return dstAddress;
  }

  public boolean isConnected() {
    return this.dstAddress != null;
  }
}
