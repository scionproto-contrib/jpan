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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionService;
import org.scion.jpan.demo.DemoTopology;
import org.scion.jpan.demo.ShowpathsDemo;
import org.scion.jpan.testutil.MockDNS;

public class ShowpathDemoTest {

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
  void test() throws InterruptedException {
    ExecutorService exec = Executors.newSingleThreadExecutor();
    AtomicInteger failures = new AtomicInteger();
    ShowpathsDemo.init(false, ShowpathsDemo.Network.JUNIT_MOCK);

    // Yes, there is a race condition because client may send a packet before
    // the server is ready. Let's fix if it actually happens.
    exec.execute(
        () -> {
          try {
            ShowpathsDemo.main(null);
            System.out.println("Done");
          } catch (Throwable e) {
            failures.incrementAndGet();
            throw new RuntimeException(e);
          }
        });

    exec.shutdown();
    assertTrue(exec.awaitTermination(900, TimeUnit.MILLISECONDS));
    exec.shutdownNow();

    assertEquals(0, failures.get());
  }
}
