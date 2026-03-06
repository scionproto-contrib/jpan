// Copyright 2024 ETH Zurich
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

package org.scion.jpan.testutil;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.scion.jpan.Scmp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockBorderRouterRunner {

  private static final Logger logger =
      LoggerFactory.getLogger(MockBorderRouterRunner.class.getName());

  private final Barrier barrier = new Barrier();
  final AtomicInteger nForwardTotal = new AtomicInteger();
  final AtomicInteger dropNextPackets = new AtomicInteger();
  final AtomicReference<Scmp.TypeCode> scmpErrorOnNextPacket = new AtomicReference<>();
  final AtomicInteger nStunRequests = new AtomicInteger();
  final AtomicBoolean enableStun = new AtomicBoolean(true);
  final AtomicReference<Predicate<ByteBuffer>> stunCallback = new AtomicReference<>();

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final List<MockBorderRouter> routers = new ArrayList<>();

  public static MockBorderRouterRunner create() {
    return new MockBorderRouterRunner();
  }

  private MockBorderRouterRunner() {}

  public synchronized void add(MockBorderRouter br) {
    routers.add(br);
  }

  public synchronized void add(
      InetSocketAddress bind1, InetSocketAddress bind2, int ifId1, int ifId2) {
    int id = routers.size();
    routers.add(new MockBorderRouter(id, bind1, bind2, ifId1, ifId2, barrier, this));
  }

  public synchronized void start() {
    barrier.reset(routers.size());
    for (MockBorderRouter br : routers) {
      executor.execute(br);
    }
    if (!barrier.await(1, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Failed to start border routers.");
    }
  }

  public synchronized void stop() {
    try {
      executor.shutdownNow();
      // Wait a while for tasks to respond to being canceled
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.error("Router did not terminate");
      }
      logger.info("Router shut down");
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  @Deprecated
  public synchronized void reset() {
    // reset static fields
    nForwardTotal.set(0);
    dropNextPackets.set(0);
    scmpErrorOnNextPacket.set(null);
    nStunRequests.set(0);
    enableStun.set(true);
    stunCallback.set(null);
  }

  public int getForwardCount(int id) {
    return routers.get(id).getForwardCount();
  }

  public int getAndResetTotalForwardCount() {
    return nForwardTotal.getAndSet(0);
  }

  public int getTotalForwardCount() {
    return nForwardTotal.get();
  }

  /**
   * Set the routers to drop the next n packets.
   *
   * @param n packets to drop
   */
  public void dropNextPackets(int n) {
    dropNextPackets.set(n);
  }

  public void returnScmpErrorOnNextPacket(Scmp.TypeCode scmpTypeCode) {
    scmpErrorOnNextPacket.set(scmpTypeCode);
  }

  public int getAndResetStunCount() {
    return nStunRequests.getAndSet(0);
  }

  public void disableStun() {
    enableStun.set(false);
  }

  public void setStunCallback(Predicate<ByteBuffer> stunCallback) {
    this.stunCallback.set(stunCallback);
  }

  public List<MockBorderRouter> getBorderRouters() {
    return routers;
  }
}
