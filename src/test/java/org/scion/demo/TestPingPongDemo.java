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

package org.scion.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class TestPingPongDemo {

  @Test
  public void test() throws InterruptedException {
    AtomicInteger failures = new AtomicInteger();
    ScionPingPongChannelServer.PRINT = false;
    ScionPingPongChannelClient.PRINT = false;
    ScionPingPongChannelClient.USE_MOCK_TOPOLOGY = true;
    Thread server =
        new Thread(
            () -> {
              try {
                ScionPingPongChannelServer.main(null);
              } catch (IOException e) {
                failures.incrementAndGet();
                throw new RuntimeException(e);
              }
            });
    server.start();

    // Yes, there is a race condition because client may send a packet before
    // the server is ready. Let's fix if it actually happens.
    Thread client =
        new Thread(
            () -> {
              try {
                ScionPingPongChannelClient.main(null);
              } catch (IOException | InterruptedException e) {
                failures.incrementAndGet();
                throw new RuntimeException(e);
              }
            });
    client.start();

    server.join();
    client.join();

    assertEquals(0, failures.get());
  }
}
