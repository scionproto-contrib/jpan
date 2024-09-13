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
import org.scion.jpan.internal.InternalConstants;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.internal.ScmpParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScmpResponder implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ScmpResponder.class);
  private final InternalChannel channel;

  ScmpResponder() throws IOException {
    this(Scion.defaultService(), 12345);
  }

  ScmpResponder(ScionService service, int port) throws IOException {
    this.channel = new InternalChannel(service, port);
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  public Consumer<Scmp.Message> setScmpErrorListener(Consumer<Scmp.Message> listener) {
    return channel.setScmpErrorListener(listener);
  }

  /**
   * Install a listener for echo messages. The listener is called for every incoming echo request
   * message. A response will be sent iff the listener returns 'true'. Any time spent in the
   * listener counts towards the RTT of the echo request.
   *
   * <p>The listener will only be called for messages received during `setUpScmpEchoResponder()`.
   *
   * @param listener THe listener function
   * @return Any previously installed listener or 'null' if none was installed.
   * @see #startScmpEchoResponder()
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
   * <p>This method blocks until the responder's thread is interrupted.
   *
   * @throws IOException If an IO exception occurs.
   */
  public void start() throws IOException {
    this.channel.sendEchoResponses();
  }

  private class InternalChannel extends AbstractDatagramChannel<InternalChannel> {
    private final Selector selector;
    private Predicate<Scmp.EchoMessage> echoListener;

    protected InternalChannel(ScionService service, int port) throws IOException {
      super(service);

      // selector
      this.selector = Selector.open();
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
        while (true) {
          ByteBuffer buffer = getBufferReceive(DEFAULT_BUFFER_SIZE);
          ResponsePath path = receiveLoop(buffer);
          if (path == null) {
            return; // interrupted
          }

          Scmp.Type type = ScmpParser.extractType(buffer);
          log.info("Received SCMP message {} from {}", type, path.getRemoteAddress());
          if (type == Scmp.Type.INFO_128) {
            Scmp.EchoMessage msg = (Scmp.EchoMessage) Scmp.createMessage(Scmp.Type.INFO_128, path);
            ScmpParser.consume(buffer, msg);

            if (!checkEchoListener(msg)) {
              continue;
            }

            // EchoHeader = 8 + data
            int len = 8 + msg.getData().length;
            buildHeader(buffer, msg.getPath(), len, InternalConstants.HdrTypes.SCMP);
            int port = msg.getIdentifier();
            ScmpParser.buildScmpPing(
                buffer, Scmp.Type.INFO_129, port, msg.getSequenceNumber(), msg.getData());
            buffer.flip();
            msg.setSizeSent(buffer.remaining());
            sendRaw(buffer, path);
            log.info("Responded to SCMP {} from {}", type, path.getRemoteAddress());
          } else {
            log.info("Dropped SCMP message with type {} from {}", type, path.getRemoteAddress());
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
}
