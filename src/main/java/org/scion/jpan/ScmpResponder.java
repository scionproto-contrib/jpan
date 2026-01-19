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

package org.scion.jpan;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.scion.jpan.internal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScmpResponder implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ScmpResponder.class);
  private final InternalChannel channel;

  private ScmpResponder(ScionService service, int port, DatagramChannel channel, Shim shim) {
    this.channel = new InternalChannel(service, port, channel, shim);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  public Consumer<Scmp.ErrorMessage> setScmpErrorListener(Consumer<Scmp.ErrorMessage> listener) {
    return channel.setScmpErrorListener(listener);
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
    return channel.setScmpEchoListener(listener);
  }

  public <T> void setOption(SocketOption<T> option, T t) throws IOException {
    channel.setOption(option, t);
  }

  /**
   * Install an SCMP echo responder. This method blocks until interrupted. While blocking, it will
   * answer all valid SCMP echo requests.
   *
   * <p>SCMP requests can be monitored and intercepted through a listener, see {@link
   * #setScmpEchoListener(Predicate)}.
   *
   * <p>This method blocks until {@link #close()} is called.
   *
   * @throws IOException If an IO exception occurs.
   */
  public void start() throws IOException {
    this.channel.start();
    this.channel.sendEchoResponses();
  }

  private static class InternalChannel extends AbstractDatagramChannel<InternalChannel> {
    private final Selector selector;
    private Predicate<Scmp.EchoMessage> echoListener;
    private final int port;
    private final Shim shim;

    protected InternalChannel(ScionService service, int port, DatagramChannel channel, Shim shim) {
      // We provide the no-op PathProvider. SCMP channels are never connected, so the
      // PathProvider will never be used.
      super(service, channel, PathProviderNoOp.create(PathPolicy.DEFAULT));
      this.shim = shim;
      this.port = port;
      try {
        this.selector = channel.provider().openSelector();
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
    }

    void start() throws IOException {
      // selector
      super.channel().configureBlocking(false);
      super.channel().register(selector, SelectionKey.OP_READ);

      // listen on ANY interface: 0.0.0.0 / [::]
      super.bind(new InetSocketAddress(port));
    }

    private ResponsePath receiveLoop(ByteBuffer buffer) throws IOException {
      while (selector.select() > 0) {
        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        if (iter.hasNext()) {
          SelectionKey key = iter.next();
          iter.remove();
          if (key.isReadable()) {
            DatagramChannel incoming = (DatagramChannel) key.channel();
            buffer.clear();
            InetSocketAddress srcAddress = (InetSocketAddress) incoming.receive(buffer);
            buffer.flip();
            if (validate(buffer)) {
              InternalConstants.HdrTypes hdrType = ScionHeaderParser.extractNextHeader(buffer);
              // From here on we use linear reading using the buffer's position() mechanism
              buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
              // Check for extension headers.
              // This should be mostly unnecessary, however we sometimes saw SCMP error headers
              // wrapped in extensions headers.
              hdrType = receiveExtensionHeader(buffer, hdrType);

              if (hdrType != InternalConstants.HdrTypes.SCMP) {
                if (shim != null) {
                  shim.forward(buffer, super.channel());
                }
                continue; // drop
              }
              return ScionHeaderParser.extractResponsePath(buffer, srcAddress);
            }
          }
        }
      }
      return null;
    }

    void sendEchoResponses() throws IOException {
      readLock().lock();
      writeLock().lock();
      try {
        if (shim != null) {
          shim.signalReadiness();
        }
        while (true) {
          ByteBuffer buffer = getBufferReceive(DEFAULT_BUFFER_SIZE);
          ResponsePath path = receiveLoop(buffer);
          if (path == null) {
            return; // interrupted
          }

          Scmp.Type type = ScmpParser.extractType(buffer);
          log.info("Received SCMP message {} from {}", type, path.getRemoteAddress());
          if (type == Scmp.Type.INFO_128) {
            Scmp.EchoMessage msg = (Scmp.EchoMessage) ScmpParser.consume(buffer, path);

            if (!checkEchoListener(msg)) {
              continue;
            }

            // EchoHeader = 8 + data
            int len = 8 + msg.getData().length;
            ByteUtil.MutInt srcPort = new ByteUtil.MutInt(-1);
            buildHeader(
                buffer, msg.getPath(), len, InternalConstants.HdrTypes.SCMP.code(), srcPort);
            ScmpParser.buildScmpPing(
                buffer, Scmp.Type.INFO_129, srcPort.get(), msg.getSequenceNumber(), msg.getData());
            buffer.flip();
            msg.setSizeSent(buffer.remaining());
            sendRaw(buffer, path);
            log.info("Responded to SCMP {} from {}", type, path.getRemoteAddress());
          } else {
            if (shim != null) {
              shim.forward(buffer, super.channel());
            } else {
              log.info("Dropped SCMP message with type {} from {}", type, path.getRemoteAddress());
            }
          }
        }
      } finally {
        writeLock().unlock();
        readLock().unlock();
      }
    }

    protected boolean checkEchoListener(Scmp.EchoMessage scmpMsg) {
      synchronized (this) {
        if (echoListener != null && scmpMsg.getTypeCode() == Scmp.TypeCode.TYPE_128) {
          return echoListener.test(scmpMsg);
        }
      }
      return true;
    }

    public Predicate<Scmp.EchoMessage> setScmpEchoListener(Predicate<Scmp.EchoMessage> listener) {
      synchronized (this) {
        Predicate<Scmp.EchoMessage> old = echoListener;
        echoListener = listener;
        return old;
      }
    }

    @Override
    public void close() throws IOException {
      selector.close();
      super.close();
    }
  }

  public InetSocketAddress getLocalAddress() throws IOException {
    return channel.getLocalAddress();
  }

  public static class Builder {
    private ScionService service;
    private boolean serviceIsSet = false;
    private int port = Constants.SCMP_PORT;
    private DatagramChannel channel;
    private Shim shim;

    public Builder setLocalPort(int localPort) {
      this.port = localPort;
      return this;
    }

    public Builder setService(ScionService service) {
      this.service = service;
      this.serviceIsSet = true;
      return this;
    }

    public ScmpResponder build() {
      ScionService service2 = serviceIsSet ? service : ScionService.defaultService();
      try {
        channel = channel == null ? DatagramChannel.open() : channel;
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
      return new ScmpResponder(service2, port, channel, shim);
    }

    public Builder setShim(Shim shim) {
      this.shim = shim;
      return this;
    }
  }
}
