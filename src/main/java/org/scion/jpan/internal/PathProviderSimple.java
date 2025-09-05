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
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.ReentrantLock;
import org.scion.jpan.Path;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionService;

/** A simple PathProvider that fetches paths and refreshed them periodically. */
public class PathProviderSimple implements PathProvider {
  private final List<Path> paths = new ArrayList<>(10);
  private final long dstIsdAs;
  private final InetSocketAddress dstAddr;
  private final ScionService service;
  private Filter filter = null;
  private Comparator comparator = null;
  private final Timer timer = new Timer();
  private final ReentrantLock lock = new ReentrantLock();
  private final int minLifetime = Config.getPathExpiryMarginSeconds();

  public PathProviderSimple(ScionService service, long dstIsdAs, InetSocketAddress dstAddr) {
    this.dstIsdAs = dstIsdAs;
    this.dstAddr = dstAddr;
    this.service = service;
    internalRefresh();
    // TODO find out when first path needs refreshing.
    this.timer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            internalRefresh();
          }
        },
        1000,
        60000);
  }

  private void lock() {
    lock.lock(); // TODO just use "synchronized" ?
  }

  private void unlock() {
    lock.unlock();
  }

  private void internalRefresh() {
    lock();
    try {
      paths.clear();
      if (filter == null) {
        paths.addAll(service.getPaths(dstIsdAs, dstAddr));
      } else {
        for (Path p : service.getPaths(dstIsdAs, dstAddr)) {
          if (filter.accept(p)) {
            paths.add(p);
          }
        }
      }
      if (comparator != null) {
        paths.sort(comparator);
      }
    } finally {
      unlock();
    }
  }

  public void setComparator(Comparator comparator) {
    lock();
    try {
      this.comparator = comparator;
    } finally {
      unlock();
    }
  }

  public void setFilter(Filter filter) {
    lock();
    try {
      this.filter = filter;
    } finally {
      unlock();
    }
  }

  @Override
  public Path getPath() {
    lock();
    try {
      if (paths.isEmpty()) {
        throw new ScionRuntimeException("No path available");
      }
      return paths.get(0);
    } finally {
      unlock();
    }
  }

  @Override
  public ScionService getService() {
    return service;
  }

  @Override
  public void close() {
    timer.cancel();
    timer.purge();
  }

  @Override
  public void refresh() {
    internalRefresh();
  }
}
