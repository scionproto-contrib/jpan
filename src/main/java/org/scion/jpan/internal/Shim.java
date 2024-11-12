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

package org.scion.jpan.internal;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.scion.jpan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SHIM acts similar to the SHIM component of a normal SCION installation: <a
 * href="https://docs.scion.org/en/latest/dev/design/router-port-dispatch.html">link</a>
 *
 * <p>The SHIM will try to install itself on port 30041. If that is not possible, it will
 * optimistically assume that another SHIM is already running there.
 *
 * <p>The SHIM responds to SCMP echo request. All other packets will be forwarded to the destination
 * address encoded in the SCION header. If parsing of the SCION header fails, the packet is dropped.
 */
public class Shim implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(Shim.class);
  private static final AtomicReference<Shim> singleton = new AtomicReference<>();
  private final ScmpResponder scmpResponder;
  private Thread forwarder;
  private Predicate<ByteBuffer> forwardCallback = null;
  private final CountDownLatch scmpResponderBarrier = new CountDownLatch(1);

  private Shim(ScionService service, int port) {
    this.scmpResponder =
        Scmp.newResponderBuilder().setService(service).setLocalPort(port).setShim(this).build();
  }

  /** Start the SHIM. The SHIM also provides an SCMP echo responder. */
  public static void install(ScionService service) {
    synchronized (singleton) {
      if (singleton.get() == null) {
        boolean flag =
            ScionUtil.getPropertyOrEnv(
                Constants.PROPERTY_SHIM, Constants.ENV_SHIM, Constants.DEFAULT_SHIM);
        if (flag) {
          singleton.set(Shim.newBuilder(service).build());
          singleton.get().start();
        }
      }
    }
  }

  public static void uninstall() {
    synchronized (singleton) {
      if (singleton.get() != null) {
        try {
          singleton.getAndSet(null).close();
        } catch (IOException e) {
          throw new ScionRuntimeException(e);
        }
      }
    }
  }

  static void setCallback(Predicate<ByteBuffer> cb) {
    synchronized (singleton) {
      if (singleton.get() != null) {
        singleton.get().forwardCallback = cb;
      }
    }
  }

  private static Builder newBuilder(ScionService service) {
    return new Builder(service);
  }

  public static boolean isInstalled() {
    return singleton.get() != null;
  }

  private void start() {
    forwarder = new Thread(this::forwardStarter, "Shim-forwarder");
    forwarder.setDaemon(true);
    forwarder.start();
    try {
      if (!scmpResponderBarrier.await(100, TimeUnit.MILLISECONDS)) {
        // ignore
        log.info("Could not start SHIM: {}", forwarder.getName());
      } else {
        log.info("SHIM started.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScionRuntimeException(e);
    }
  }

  private void forwardStarter() {
    try {
      this.scmpResponder.start();
    } catch (BindException e) {
      // Ignore
      log.info("Error while starting SHIM: {}", e.getMessage());
      // This is not thread safe but best effort to indicate a failed start.
      singleton.set(null);
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  public void signalReadiness() {
    scmpResponderBarrier.countDown();
  }

  private void stopHandler(Thread thread) {
    thread.interrupt();
    try {
      thread.join(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() throws IOException {
    stopHandler(forwarder);
    scmpResponder.close();
  }

  public void forward(ByteBuffer buf, DatagramChannel channel) {
    buf.rewind();
    try {
      if (forwardCallback == null || forwardCallback.test(buf)) {
        InetSocketAddress dst = ScionHeaderParser.extractDestinationSocketAddress(buf);
        channel.send(buf, dst);
      }
    } catch (IOException e) {
      log.info("ERROR while forwarding packet: {}", e.getMessage());
    }
  }

  public static class Builder {
    private final ScionService service;

    Builder(ScionService service) {
      this.service = service;
    }

    public Shim build() {
      ScionService service2 = service == null ? Scion.defaultService() : service;
      int port = Constants.SCMP_PORT;
      return new Shim(service2, port);
    }
  }
}
