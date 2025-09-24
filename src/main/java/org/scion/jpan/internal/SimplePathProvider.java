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

import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.ScionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SimplePathProvider simply provides the next best path.
 * Lifecycle:
 * 1) create PathProvidwr
 * 2) register callbacks
 * 3) connect to destination
 * 4) disconnectVielleicht
 * @param <K>
 */
public class SimplePathProvider<K> implements PathProvider2<K> {

  private static final Logger LOG = LoggerFactory.getLogger(SimplePathProvider.class.getName());
  private static final Timer timer = new Timer(true);
  private static final ScheduledThreadPoolExecutor timer2 = new ScheduledThreadPoolExecutor(1);

  private TimerTask timerTask;
  private final ScionService service;
  private long dstIsdAs;
  private InetSocketAddress dstAddress;
  private PathPolicy pathPolicy;

  private final Map<K, PathUpdateCallback> callbacks = new HashMap<>();
  private final Queue<Entry> faultyPaths = new ArrayDeque<>();
  private final TreeMap<Double, Entry> unusedPaths = new TreeMap<>();
  private final Map<K, Entry> usedPaths = new HashMap<>();

  private int configPathPollIntervalMs = 10_000;

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
      for (PathMetadata.PathInterface i: path.getMetadata().getInterfacesList()) {
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
      if (this == obj) return true;
      if (obj == null || getClass() != obj.getClass()) return false;
      Entry other = (Entry) obj;
      return hashCode == other.hashCode && Arrays.equals(pathHashBase, other.pathHashBase);
    }

    public void set(Entry other) {
      this.path = other.path;
      this.rank = other.rank;
      if (this.timestamp != null || hashCode != other.hashCode || !Arrays.equals(pathHashBase, other.pathHashBase)) {
        // Preserve faulty timestamp
        throw new IllegalStateException();
      }
    }
  }

  public static <K> SimplePathProvider<K> create(ScionService service, PathPolicy policy) {
    return new SimplePathProvider<>(service, policy);
  }

  private SimplePathProvider(ScionService service, PathPolicy policy) {
    this.service = service;
    this.dstIsdAs = 0;
    this.dstAddress = null;
    this.pathPolicy = policy;
  }

  // Synchronized because it is called by timer
  private synchronized void refreshAllPaths() {
    // Purpose:
    // 1) Get new paths from the service
    // 2) Discard paths that are about to expire
    // 3) Consider retry path that were broken
    
    // 1) Get new paths from the service

    Set<Path> newPaths2 = new HashSet<>(pathPolicy.filter(service.getPaths(dstIsdAs, dstAddress)));
    Map<Entry, Entry> newPaths = new HashMap<>();
    int n = 0;
    for (Path p : newPaths2) {
      Entry newEntry = new Entry(p, n++);
      newPaths.put(newEntry, newEntry);
    }

    // Unused path: Replace list of unused paths with new paths (after removing used paths and faulty paths)
    // Used paths: Replace list. Use callback to replace paths.
    //   Replace immediately if old path is not available anymore.
    //   Maybe: delay until the path is about to expire before the next path fetching?
    // Faulty paths: Remove from list if path is not available anymore
    //   Maybe: try to reuse faulty paths? Depending on ranking?

    // We could simply assign new paths to all used paths, but this would cause unnecessary path changes.
    // We try to maintain paths until they are expired or faulty.

    List<K> toRevoke = new ArrayList<>();
    List<Entry> toRemoveFromNewList = new ArrayList<>();

    // Check used paths
    for (Map.Entry<K, Entry> me : usedPaths.entrySet()) {
      Entry newEntry = newPaths.get(me.getValue());
      if (newEntry != null) {
        // Path is in use.
        me.getValue().set(newEntry);
        // TODO check expiration. If about to expire, assign updated path
//        if (aboutToExpire(me.getValue().path.getMetadata().getExpiration())) {
//          callbacks.get(me.getKey()).pathsUpdated(newEntry.path);
//        }
        toRemoveFromNewList.add(newEntry);
      } else {
        toRevoke.add(me.getKey()); // Path no longer available, revoke it
      }
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

    for (Entry e: toRemoveFromNewList) {
      newPaths.remove(e);
    }

    // update unused path.
    unusedPaths.clear();
    for (Entry e: newPaths.values()) {
      unusedPaths.put(e.rank, e);
    }

    // Revoke paths and assign a new one (must be done after updating unused paths list)
    for (K k: toRevoke) {
      usedPaths.remove(k);
      callbacks.get(k).pathsUpdated(getFreePath(k));
    }
  }

  private Path getFreePath(K key) {
    if (unusedPaths.isEmpty()) {
      // No new path available
      LOG.warn("No free path available.");
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
    callbacks.get(key).pathsUpdated(getFreePath(key));
  }

  @Override
  public synchronized void registerCallback(K key, PathUpdateCallback cb) {
    if (callbacks.containsKey(key)) {
      throw new IllegalArgumentException("Callback with the same key already registered");
    }
    callbacks.put(key, cb);
    if (isConnected()) {
      cb.pathsUpdated(getFreePath(key));
    }
  }

  @Override
  public synchronized void unregisterCallback(K key) {
    Entry e = usedPaths.remove(key);
    unusedPaths.put(e.rank, e);
    callbacks.remove(key);
  }

  @Override
  public synchronized void setPathPolicy(PathPolicy pathPolicy) {
    this.pathPolicy = pathPolicy;
  }

  @Override
  public void connect(long isdAs, InetSocketAddress destination) {
    if (isConnected()) {
      throw new IllegalStateException("Path provider is already connected");
    }
    this.dstIsdAs = isdAs;
    this.dstAddress = destination;
    int n = 0;
    for (Path p : pathPolicy.filter(service.getPaths(dstIsdAs, dstAddress))) {
      double rank = n++; // Very simple ranking: preserver ordering provided by PathPolicy
      unusedPaths.put(rank, new Entry(p, rank));
    }

    this.timerTask =
        new TimerTask() {
          @Override
          public void run() {
            try {
              refreshAllPaths();
            } catch (Exception e) {
              LOG.error("Exception in PathProvider timer task", e);
            }
          }
        };

    timer.scheduleAtFixedRate(timerTask, configPathPollIntervalMs, configPathPollIntervalMs);
  }

  @Override
  public void disconnect() {
//    if (!isConnected()) {
//      throw new IllegalStateException("Path provider is not connected");
//    }
    if (timerTask != null) {
      timerTask.cancel();
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
