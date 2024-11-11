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

package org.scion.jpan.testutil;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * This is similar to a Java ExecutorService, but it also has a startup barrier and reports
 * exceptions. It also encapsulates most of the exception boilerplate.
 */
public class ManagedThread {

  private final ExecutorService executor;
  private final List<Throwable> exceptions = new CopyOnWriteArrayList<>();
  private final CountDownLatch barrier = new CountDownLatch(1);
  private final String name;
  private final int startUpWaitMillis;

  public interface MTRunnable {
    void run(Runnable reportStarted, Consumer<Throwable> reportException);
  }

  public static ManagedThread.Builder newBuilder() {
    return new ManagedThread.Builder();
  }

  private ManagedThread(int nThreads, int startUpWaitMillis, String name) {
    this.name = name;
    this.startUpWaitMillis = startUpWaitMillis;
    if (nThreads == 1) {
      executor = Executors.newSingleThreadExecutor();
    } else {
      executor = Executors.newFixedThreadPool(nThreads);
    }
  }

  public void submit(MTRunnable runnable) {
    executor.submit(() -> runnable.run(this::reportStarted, this::reportException));
    try {
      if (!barrier.await(1, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        throw new IllegalStateException("Could not start managed thread: " + name);
      }
      Thread.sleep(startUpWaitMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  public void stopNow() {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
        checkExceptions();
        throw new IllegalStateException("Managed thread won't stop: " + name);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
    checkExceptions();
  }

  private void reportStarted() {
    barrier.countDown();
  }

  private void reportException(Throwable t) {
    exceptions.add(t);
  }

  private void checkExceptions() {
    for (Throwable t : exceptions) {
      t.printStackTrace();
    }
    if (!exceptions.isEmpty()) {
      throw new IllegalStateException("Exception in managed thread: " + name, exceptions.get(0));
    }
  }

  public static class Builder {
    private final int startUpWaitMillis = 50;
    private final String name = "ManagedThread-" + System.identityHashCode(this);
    private final int nThreads = 1;

    public ManagedThread build() {
      return new ManagedThread(nThreads, startUpWaitMillis, name);
    }
  }
}
