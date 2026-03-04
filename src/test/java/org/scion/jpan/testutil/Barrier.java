// Copyright 2026 ETH Zurich
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Barrier {
  private volatile CountDownLatch barrier = new CountDownLatch(0);

  public void await() {
    try {
      barrier.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Timeout while waiting for threads to start", e);
    }
  }

  public boolean await(long timeout, TimeUnit unit) {
    try {
      return barrier.await(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Timeout while waiting for threads to start", e);
    }
  }

  public void countDown() {
    barrier.countDown();
  }

  public void reset(int count) {
    barrier = new CountDownLatch(count);
  }
}
