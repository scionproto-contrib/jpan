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
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.scion.jpan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SHIM acts similar to the SHIM component of a normal SCION installation:
 * https://docs.scion.org/en/latest/dev/design/router-port-dispatch.html
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

  private Shim(ScionService service, int port) {
    this.scmpResponder =
        Scmp.newResponderBuilder().setService(service).setLocalPort(port).setShim(this).build();
  }

  /** Start the SHIM. The SHIM also provides an SCMP echo responder. */
  public static void install(ScionService service) {
    synchronized (singleton) {
      if (singleton.get() == null) {
        singleton.set(Shim.newBuilder(service).build());
        singleton.get().start();
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

  private static Builder newBuilder(ScionService service) {
    return new Builder(service);
  }

  private void start() {
    this.forwarder = startHandler(this::forwardStarter, "Shim-forwarder");
  }

  private void forwardStarter(CountDownLatch barrier) {
    barrier.countDown();
    try {
      this.scmpResponder.start();
    } catch (BindException e) {
      // Ignore
      log.info("Could not start SHIM: {}", e.getMessage());
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  private Thread startHandler(Consumer<CountDownLatch> task, String name) {
    CountDownLatch barrier = new CountDownLatch(1);
    Thread thread = new Thread(() -> task.accept(barrier), name);
    thread.setDaemon(true);
    thread.start();
    try {
      if (!barrier.await(1, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Could not start receiver thread: " + name);
      }
      return thread;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScionRuntimeException(e);
    }
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
      InetSocketAddress dst = ScionHeaderParser.extractDestinationSocketAddress(buf);
      channel.send(buf, dst);
    } catch (IOException e) {
      log.info("ERROR while forwarding packet: {}", e.getMessage());
    }
  }

  public Consumer<Scmp.ErrorMessage> setScmpErrorListener(Consumer<Scmp.ErrorMessage> listener) {
    return scmpResponder.setScmpErrorListener(listener);
  }

  /**
   * Install a listener for echo messages. The listener is called for every incoming echo request
   * message. A response will be sent iff the listener returns 'true'. That means that any time
   * spent in the listener counts towards the RTT of the echo request.
   *
   * <p>The listener will only be called for messages received during `setUpScmpEchoResponder()`.
   *
   * @param listener THe listener function
   * @return Any previously installed listener or 'null' if none was installed.
   */
  public Predicate<Scmp.EchoMessage> setScmpEchoListener(Predicate<Scmp.EchoMessage> listener) {
    return scmpResponder.setScmpEchoListener(listener);
  }

  public <T> void setOption(SocketOption<T> option, T t) throws IOException {
    scmpResponder.setOption(option, t);
  }

  public InetSocketAddress getLocalAddress() throws IOException {
    return scmpResponder.getLocalAddress();
  }

  public static class Builder {
    private ScionService service;
    private int port = Constants.SCMP_PORT;

    Builder(ScionService service) {
      this.service = service;
    }

    public Builder setLocalPort(int localPort) {
      this.port = localPort;
      return this;
    }

    public Builder setService(ScionService service) {
      this.service = service;
      return this;
    }

    public Shim build() {
      ScionService service2 = service == null ? Scion.defaultService() : service;
      return new Shim(service2, port);
    }
  }
}
