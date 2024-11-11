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

/**
 * This is similar to a Java ExecutorService, but it also has a startup barrier and reports
 * exceptions. It also encapsulates most of the exception boilerplate.
 */
public class ManagedThread implements ManagedThreadNews {

  private final ExecutorService executor;
  private final List<Exception> exceptions = new CopyOnWriteArrayList<>();
  private final CountDownLatch barrier = new CountDownLatch(1);
  private final String name;
  private final int startUpWaitMillis;
  private final boolean expectException;

  public interface MTRunnable {
    void run(ManagedThreadNews news) throws Exception;
  }

  public static ManagedThread.Builder newBuilder() {
    return new ManagedThread.Builder();
  }

  private ManagedThread(int nThreads, int startUpWaitMillis, String name, boolean expectException) {
    this.name = name;
    this.startUpWaitMillis = startUpWaitMillis;
    this.expectException = expectException;
    if (nThreads == 1) {
      executor = Executors.newSingleThreadExecutor();
    } else {
      executor = Executors.newFixedThreadPool(nThreads);
    }
  }

  public void submit(MTRunnable runnable) {
    executor.submit(
        () -> {
          try {
            runnable.run(this);
          } catch (Exception t) {
            exceptions.add(t);
            throw new RuntimeException(t);
          }
        });

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

  public void join() {
    join(1000);
  }

  public void join(int millis) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(millis, TimeUnit.MILLISECONDS)) {
        stopNow();
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    checkExceptions();
  }

  public Throwable getException() {
    if (!expectException) {
      throw new IllegalStateException();
    }
    if (exceptions.size() != 1) {
      throw new IllegalStateException();
    }
    return exceptions.get(0);
  }

  @Override
  public void reportStarted() {
    barrier.countDown();
  }

  @Override
  public void reportException(Exception t) {
    exceptions.add(t);
  }

  private void checkExceptions() {
    if (expectException) {
      return; // ignore any exceptions
    }
    for (Exception t : exceptions) {
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
    private boolean expectException = false;

    public ManagedThread build() {
      return new ManagedThread(nThreads, startUpWaitMillis, name, expectException);
    }

    public Builder expectException(boolean flag) {
      this.expectException = flag;
      return this;
    }
  }
}
