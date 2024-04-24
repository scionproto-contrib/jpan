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

package org.scion.jpan.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionService;

public class PingPongDemoTest {

  @BeforeAll
  public static void beforeAll() {
    ScionService.closeDefault();
  }

  @Test
  void test() throws InterruptedException {
    AtomicInteger failures = new AtomicInteger();
    PingPongChannelServer.PRINT = false;
    PingPongChannelServer.NETWORK = DemoConstants.Network.MOCK_TOPOLOGY_IPV4;
    PingPongChannelClient.PRINT = false;
    PingPongChannelClient.NETWORK = DemoConstants.Network.MOCK_TOPOLOGY_IPV4;
    CountDownLatch barrier = new CountDownLatch(1);
    Thread server =
        new Thread(
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
    server.start();
    Thread.sleep(1000);
    // Yes this is bad, not least because the barrier is counted down before the server starts.
    // But it is the best we can do here.
    if (!barrier.await(100, TimeUnit.MILLISECONDS)) {
      fail();
    }

    // Yes, there is a race condition because client may send a packet before
    // the server is ready. Let's fix if it actually happens.
    Thread client =
        new Thread(
            () -> {
              try {
                PingPongChannelClient.main(null);
              } catch (Throwable e) {
                failures.incrementAndGet();
                e.printStackTrace();
                throw new RuntimeException(e);
              }
            });
    client.start();

    server.join(1000);
    client.join(1000);
    // just in case
    server.interrupt();
    client.interrupt();
    // join again to make sure the interrupt was handled
    server.join(100);
    client.join(100);

    assertEquals(0, failures.get());
  }
}
