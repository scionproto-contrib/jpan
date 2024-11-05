// Copyright 2023 ETH Zurich
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

package org.scion.jpan.demo.test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionService;
import org.scion.jpan.demo.DemoTopology;
import org.scion.jpan.demo.PingPongChannelClient;
import org.scion.jpan.demo.PingPongChannelServer;
import org.scion.jpan.testutil.MockDNS;

public class PingPongChannelDemoTest {

  @BeforeAll
  public static void beforeAll() {
    ScionService.closeDefault();
  }

  @AfterAll
  public static void afterAll() {
    DemoTopology.shutDown();
    MockDNS.clear();
    ScionService.closeDefault();
  }

  @Test
  void test() throws InterruptedException, ExecutionException {
    AtomicInteger failures = new AtomicInteger();
    ExecutorService exec = Executors.newFixedThreadPool(2);
    PingPongChannelServer.PRINT = false;
    PingPongChannelClient.PRINT = false;
    CountDownLatch barrier = new CountDownLatch(1);

    exec.execute(
        () -> {
          try {
            barrier.countDown();
            PingPongChannelServer.main(null);
          } catch (Throwable e) {
            failures.incrementAndGet();
            e.printStackTrace();
            throw new RuntimeException(e);
          }
        });
    // Yes this is bad, the barrier is counted down before the server socket starts listening.
    // But it is the best we can easily do here.
    Thread.sleep(100);
    if (!barrier.await(100, TimeUnit.MILLISECONDS)) {
      fail();
    }
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);

    // Yes, there is a race condition because client may send a packet before
    // the server is ready. Let's fix if it actually happens.
    Future<Boolean> f =
        exec.submit(
            () -> {
              try {
                PingPongChannelClient.main(null);
                return true;
              } catch (Throwable e) {
                failures.incrementAndGet();
                throw new RuntimeException(e);
              }
            });

    assertTrue(f.get());
    exec.shutdown();
    assertTrue(exec.awaitTermination(1000, TimeUnit.MILLISECONDS));
    exec.shutdownNow();
    assertEquals(0, failures.get());
  }
}
