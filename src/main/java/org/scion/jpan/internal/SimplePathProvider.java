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
 *
 * @param <K>
 */
public class SimplePathProvider<K> implements PathProvider2<K> {

  private static final Logger LOG = LoggerFactory.getLogger(SimplePathProvider.class.getName());
  private static final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);

  private TimerTask timerTask;
  private final ScionService service;
  private long dstIsdAs;
  private InetSocketAddress dstAddress;
  private PathPolicy pathPolicy;
  private final ReplaceStrategy replaceStrategy;

  private final Map<K, PathUpdateCallback> subscribers = new HashMap<>();
  private final Queue<Entry> faultyPaths = new ArrayDeque<>();
  private final TreeMap<Double, Entry> unusedPaths = new TreeMap<>();
  private final Map<K, Entry> usedPaths = new HashMap<>();

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

  public static <K> SimplePathProvider<K> create(
      ScionService service,
      PathPolicy policy,
      int pollIntervalMs,
      int expirationMarginSec,
      ReplaceStrategy strategy) {
    return new SimplePathProvider<>(service, policy, pollIntervalMs, expirationMarginSec, strategy);
  }

  private SimplePathProvider(
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

    if (newPaths.isEmpty() || newPaths.size() < usedPaths.size()) {
      LOG.warn(
          "No enough valid path available. Found: {}, used: {}", newPaths.size(), usedPaths.size());
      // TODO replace the ones closest to expiration, try again later.
    }

    // Unused path: Replace list of unused paths with new paths (after removing used paths and
    // faulty paths)
    // Used paths: Replace list. Use callback to replace paths.
    //   Replace immediately if old path is not available anymore.
    //   Maybe: delay until the path is about to expire before the next path fetching?
    // Faulty paths: Remove from list if path is not available anymore
    //   Maybe: try to reuse faulty paths? Depending on ranking?

    // We could simply assign new paths to all used paths, but this would cause unnecessary path
    // changes.
    // We try to maintain paths until they are expired or faulty.

    List<K> toRevoke = new ArrayList<>();
    List<Entry> toRemoveFromNewList = new ArrayList<>();

    // TODO generally, what about giving the same path to multiple subscribers?
    //      Could be useful for certain usecases, or if too few paths available.
    //      Make Provider configurable or have different implementations?
    //      --> Later.

    if (replaceStrategy == ReplaceStrategy.EXACT_MATCH) {
      // Check used paths
      for (Map.Entry<K, Entry> me : usedPaths.entrySet()) {
        Entry newEntry = newPaths.get(me.getValue());
        if (newEntry != null) {
          me.getValue().set(newEntry);
          toRemoveFromNewList.add(newEntry);
        } else {
          toRevoke.add(me.getKey()); // Path no longer available, revoke it
        }
      }
    } else if (replaceStrategy == ReplaceStrategy.BEST_RANK_IF_EXPIRED) {
      // Check used paths
      for (Map.Entry<K, Entry> me : usedPaths.entrySet()) {
        if (aboutToExpire(me.getValue().path)) {
          // Path is about to expire, replace with best ranked path
          toRevoke.add(me.getKey());
        } else {
          // Try to find the same path in the new list
          Entry newEntry = newPaths.get(me.getValue());
          if (newEntry != null) {
            me.getValue().set(newEntry);
            toRemoveFromNewList.add(newEntry);
          }
        }
      }
    } else if (replaceStrategy == ReplaceStrategy.BEST_RANK) {
      // Revoke all path (and later replace with best available path)
      for (Map.Entry<K, Entry> me : usedPaths.entrySet()) {
        toRevoke.add(me.getKey());
      }
    } else {
      throw new IllegalStateException("Unknown replace strategy");
    }

    // Clean up faulty list
    Iterator<Entry> faultyIter = faultyPaths.iterator();
    while (faultyIter.hasNext()) {
      Entry e = faultyIter.next();
      Entry newEntry = newPaths.get(e);
      if (newEntry == null) {
        faultyIter.remove();
      }
    }

    for (Entry e : toRemoveFromNewList) {
      newPaths.remove(e);
    }

    // update unused path.
    unusedPaths.clear();
    for (Entry e : newPaths.values()) {
      unusedPaths.put(e.rank, e);
    }

    // Revoke paths and assign a new one (must be done after updating unused paths list)
    for (K k : toRevoke) {
      usedPaths.remove(k);

      subscribers.get(k).pathsUpdated(getFreePath(k));
    }

    return nextExpiration;
  }

  private Path getFreePath(K key) {
    if (unusedPaths.isEmpty()) {
      // No new path available
      LOG.warn("No free path available.");
      refreshAllPaths();
      // TODO fetch new paths, especially if we only have a single path
      // TODO try using faulty paths that might have recovered
      return null;
    }
    Entry e = unusedPaths.pollFirstEntry().getValue();
    usedPaths.put(key, e);
    return e.path;
  }

  @Override
  public synchronized void reportFaultyPath(K key, Path p) {
    Entry e = usedPaths.get(key);
    if (e == null) {
      throw new IllegalArgumentException("Path not managed by this provider");
    }
    e.setFaulty(Instant.now());
    faultyPaths.add(e);

    // Find new path
    subscribers.get(key).pathsUpdated(getFreePath(key));
  }

  @Override
  public synchronized void subscribe(K key, PathUpdateCallback cb) {
    if (subscribers.containsKey(key)) {
      throw new IllegalArgumentException("Callback with the same key already registered");
    }
    subscribers.put(key, cb);
    if (isConnected()) {
      cb.pathsUpdated(getFreePath(key));
    }
  }

  @Override
  public synchronized void unsubscribe(K key) {
    Entry e = usedPaths.remove(key);
    if (e != null) {
      unusedPaths.put(e.rank, e);
    }
    subscribers.remove(key);
  }

  @Override
  public synchronized void setPathPolicy(PathPolicy pathPolicy) {
    this.pathPolicy = pathPolicy;
  }

  @Override
  // TODO remove method?!?!?!
  public void connect(long isdAs, InetSocketAddress destination) {
    //    if (isConnected()) {
    //      throw new IllegalStateException("Path provider is already connected");
    //    }
    //    this.dstIsdAs = isdAs;
    //    this.dstAddress = destination;
    //    int n = 0;
    //    for (Path p : pathPolicy.filter(service.getPaths(dstIsdAs, dstAddress))) {
    //      double rank = n++; // Very simple ranking: preserver ordering provided by PathPolicy
    //      unusedPaths.put(rank, new Entry(p, rank));
    //    }
    //
    //    this.timerTask =
    //        new TimerTask() {
    //          @Override
    //          public void run() {
    //            try {
    //              refreshAllPaths();
    //            } catch (Exception e) {
    //              LOG.error("Exception in PathProvider timer task", e);
    //            }
    //          }
    //        };
    //
    //    timer.scheduleAtFixedRate(timerTask, configPathPollIntervalMs, configPathPollIntervalMs);
  }

  private boolean aboutToExpire(Path path) {
    // TODO why is expiration part of the metadata? Server paths can also expire!
    long epochSeconds = path.getMetadata().getExpiration();
    return epochSeconds - configExpirationMarginSec < Instant.now().getEpochSecond();
  }

  @Override
  public void connect(Path path) {
    if (isConnected()) {
      throw new IllegalStateException("Path provider is already connected");
    }
    this.dstIsdAs = path.getRemoteIsdAs();
    this.dstAddress = path.getRemoteSocketAddress();

    long nextExpiration = Long.MAX_VALUE;

    if (aboutToExpire(path)) {
      // fetch new paths
      int n = 1;
      for (Path p : pathPolicy.filter(service.getPaths(dstIsdAs, dstAddress))) {
        double rank = n++; // Very simple ranking: preserver ordering provided by PathPolicy
        unusedPaths.put(rank, new Entry(p, rank));
        nextExpiration = Math.min(nextExpiration, p.getMetadata().getExpiration());
      }
    } else {
      // use this path
      unusedPaths.put(0.0, new Entry(path, 0.0));
      nextExpiration = Math.min(nextExpiration, path.getMetadata().getExpiration());
    }

    // TODO Remove this. This is nonsense.
    for (PathUpdateCallback sub : subscribers.values()) {
      if (unusedPaths.isEmpty()) {
        throw new IllegalStateException("No path available");
      }
      // TODO this is bad!
      // What do we need:
      // If the connect path is expired, we should replace it with a new path.
      // But if it is expired,

      sub.pathsUpdated(unusedPaths.pollFirstEntry().getValue().path);
    }

    // Design: How should this work?
    // Multiple sibscribers + connect...??!?!
    // - ConnectPath is very specific, so we should do it (unless it is expired)

    // TODO
    // WHat does connect do?
    // SHould we have connect() and connect(Path)

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

    //    refreshAllPaths(); // TODO ??            -> Use return value to schedule timer!

    long delaySec = nextExpiration - Instant.now().getEpochSecond() - configExpirationMarginSec;
    // TODO is 100 milliseconds good?
    ScheduledFuture f =
        timer.schedule(timerTask, delaySec > 0 ? delaySec * 1000L : 100L, TimeUnit.MILLISECONDS);
    // TODO consider cancelling via f.cancel()
  }

  @Override
  public void disconnect() {
    //    if (!isConnected()) {
    //      throw new IllegalStateException("Path provider is not connected");
    //    }
    if (timerTask != null) {
      timer.remove(timerTask);
    }
    this.timerTask = null;
    this.dstAddress = null;
    this.dstIsdAs = 0;
    this.unusedPaths.clear();
    this.usedPaths.clear();
    this.faultyPaths.clear();
  }

  public boolean isConnected() {
    return this.dstAddress != null;
  }
}
