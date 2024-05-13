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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import org.scion.jpan.internal.InternalConstants;
import org.scion.jpan.internal.PathHeaderParser;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.internal.ScmpParser;

public class ScmpChannel implements AutoCloseable {
  private int timeOutMs = 1000;
  private final InternalChannel channel;

  ScmpChannel(RequestPath path) throws IOException {
    this(Scion.defaultService(), path, 12345);
  }

  ScmpChannel(ScionService service, RequestPath path, int port) throws IOException {
    channel = new InternalChannel(service, path, port);
  }

  /**
   * Sends a SCMP echo request to the connected destination.
   *
   * @param sequenceNumber sequence number of the request
   * @param data user data that is sent with the request
   * @return A SCMP result. If a reply is received, the result contains the reply and the time in
   *     milliseconds that the reply took. If the request timed out, the result contains no message
   *     and the time is equal to the time-out duration.
   * @throws IOException if an IO error occurs or if an SCMP error is received.
   */
  public Scmp.EchoMessage sendEchoRequest(int sequenceNumber, ByteBuffer data) throws IOException {
    RequestPath path = (RequestPath) channel.getConnectionPath();
    Scmp.EchoMessage request = Scmp.EchoMessage.createRequest(sequenceNumber, path, data);
    sendScmpRequest(() -> channel.sendEchoRequest(request), Scmp.TypeCode.TYPE_129);
    return request;
  }

  /**
   * Sends a SCMP traceroute request to the connected destination.
   *
   * @return A list of SCMP results, one for each hop on the route. For every reply received, the
   *     result contains the reply and the time in milliseconds that the reply took. If the request
   *     timed out, the result contains no message and the time is equal to the time-out duration.
   *     If a request times out, the traceroute is aborted.
   * @throws IOException if an IO error occurs or if an SCMP error is received.
   */
  public List<Scmp.TracerouteMessage> sendTracerouteRequest() throws IOException {
    List<Scmp.TracerouteMessage> requests = new ArrayList<>();
    RequestPath path = (RequestPath) channel.getConnectionPath();
    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    for (int i = 0; i < nodes.size(); i++) {
      Scmp.TracerouteMessage request = Scmp.TracerouteMessage.createRequest(i, path);
      requests.add(request);
      PathHeaderParser.Node node = nodes.get(i);
      sendScmpRequest(() -> channel.sendTracerouteRequest(request, node), Scmp.TypeCode.TYPE_131);
      if (request.isTimedOut()) {
        break;
      }
    }
    return requests;
  }

  private void sendScmpRequest(IOCallable<Scmp.TimedMessage> sender, Scmp.TypeCode expectedTypeCode)
      throws IOException {
    long sendNanos = System.nanoTime();
    Scmp.TimedMessage result = sender.call();
    long nanos = System.nanoTime() - sendNanos;
    if (result.getTypeCode() == expectedTypeCode) {
      result.setNanoSeconds(nanos);
    } else if (result.isTimedOut()) {
      result.setNanoSeconds(timeOutMs * 1_000_000L);
    } else {
      throw new IOException("SCMP error: " + result.getTypeCode().getText());
    }
  }

  public void setTimeOut(int milliSeconds) {
    this.timeOutMs = milliSeconds;
  }

  public int getTimeOut() {
    return this.timeOutMs;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  public Consumer<Scmp.Message> setScmpErrorListener(Consumer<Scmp.Message> listener) {
    return channel.setScmpErrorListener(listener);
  }

  public <T> void setOption(SocketOption<T> option, T t) throws IOException {
    channel.setOption(option, t);
  }

  @FunctionalInterface
  private interface IOCallable<V> {
    V call() throws IOException;
  }

  private class InternalChannel extends AbstractDatagramChannel<InternalChannel> {
    private final Selector selector;

    protected InternalChannel(ScionService service, RequestPath path, int port) throws IOException {
      super(service);

      // selector
      this.selector = Selector.open();
      super.channel().configureBlocking(false);
      super.channel().register(selector, SelectionKey.OP_READ);

      // listen on ANY interface: 0.0.0.0 / [::]
      super.bind(new InetSocketAddress("[::]", port));
      super.connect(path);
    }

    Scmp.TimedMessage sendEchoRequest(Scmp.EchoMessage request) throws IOException {
      writeLock().lock();
      try {
        Path path = request.getPath();
        super.channel().connect(path.getFirstHopAddress());
        ByteBuffer buffer = getBufferSend(DEFAULT_BUFFER_SIZE);
        // EchoHeader = 8 + data
        int len = 8 + request.getData().length;
        buildHeader(buffer, request.getPath(), len, InternalConstants.HdrTypes.SCMP);
        int localPort = super.getLocalAddress().getPort();
        ScmpParser.buildScmpPing(buffer, localPort, request.getSequenceNumber(), request.getData());
        buffer.flip();
        request.setSizeSent(buffer.remaining());
        sendRaw(buffer, path.getFirstHopAddress());

        int sizeReceived = receiveRequest(request);
        request.setSizeReceived(sizeReceived);
        return request;
      } finally {
        writeLock().unlock();
        if (super.channel().isConnected()) {
          super.channel().disconnect();
        }
      }
    }

    Scmp.TimedMessage sendTracerouteRequest(
        Scmp.TracerouteMessage request, PathHeaderParser.Node node) throws IOException {
      writeLock().lock();
      try {
        Path path = request.getPath();
        super.channel().connect(path.getFirstHopAddress());
        ByteBuffer buffer = getBufferSend(DEFAULT_BUFFER_SIZE);
        // TracerouteHeader = 24
        int len = 24;
        buildHeader(buffer, path, len, InternalConstants.HdrTypes.SCMP);
        int interfaceNumber = request.getSequenceNumber();
        int localPort = super.getLocalAddress().getPort();
        ScmpParser.buildScmpTraceroute(buffer, localPort, interfaceNumber);
        buffer.flip();

        // Set flags for border routers to return SCMP packet
        int posPath = ScionHeaderParser.extractPathHeaderPosition(buffer);
        buffer.put(posPath + node.posHopFlags, node.hopFlags);

        sendRaw(buffer, path.getFirstHopAddress());

        receiveRequest(request);
        return request;
      } finally {
        writeLock().unlock();
        if (super.channel().isConnected()) {
          super.channel().disconnect();
        }
      }
    }

    int receiveRequest(Scmp.TimedMessage request) throws IOException {
      readLock().lock();
      try {
        ByteBuffer buffer = getBufferReceive(DEFAULT_BUFFER_SIZE);
        ResponsePath receivePath = receiveWithTimeout(buffer);
        if (receivePath != null) {
          ScmpParser.consume(buffer, request);
          request.setPath(receivePath);
          checkListeners(request);
        } else {
          request.setTimedOut();
        }
        return buffer.position();
      } finally {
        readLock().unlock();
      }
    }

    private ResponsePath receiveWithTimeout(ByteBuffer buffer) throws IOException {
      while (true) {
        buffer.clear();
        if (selector.select(timeOutMs) == 0) {
          return null;
        }

        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        if (iter.hasNext()) {
          SelectionKey key = iter.next();
          iter.remove();
          if (key.isReadable()) {
            java.nio.channels.DatagramChannel incoming = (DatagramChannel) key.channel();
            InetSocketAddress srcAddress = (InetSocketAddress) incoming.receive(buffer);
            buffer.flip();
            if (validate(buffer)) {
              InternalConstants.HdrTypes hdrType = ScionHeaderParser.extractNextHeader(buffer);
              // From here on we use linear reading using the buffer's position() mechanism
              buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
              // Check for extension headers.
              // This should be mostly unnecessary, however we sometimes saw SCMP error headers
              // wrapped in extensions headers.
              hdrType = super.receiveExtensionHeader(buffer, hdrType);

              if (hdrType != InternalConstants.HdrTypes.SCMP) {
                continue; // drop
              }
              return ScionHeaderParser.extractResponsePath(buffer, srcAddress);
            }
          }
        }
      }
    }

    @Override
    public void close() throws IOException {
      super.close();
      selector.close();
    }
  }

  /**
   * Get the currently connected path. The connected path is set during channel creation.
   *
   * @return the current Path
   * @see ScionDatagramChannel#getConnectionPath()
   */
  public RequestPath getConnectionPath() {
    return (RequestPath) channel.getConnectionPath();
  }

  public InetSocketAddress getLocalAddress() throws IOException {
    return channel.getLocalAddress();
  }

  public InetSocketAddress getRemoteAddress() throws IOException {
    return channel.getRemoteAddress();
  }

  public PathPolicy getPathPolicy() {
    return channel.getPathPolicy();
  }

  /**
   * Set the path policy. The default path policy is set in {@link PathPolicy#DEFAULT}. If the
   * socket is connected, this method will request a new path using the new policy.
   *
   * <p>After initially setting the path policy, it is used to request a new path during write() and
   * send() whenever a path turns out to be close to expiration.
   *
   * @param pathPolicy the new path policy
   * @see PathPolicy#DEFAULT
   * @see ScionDatagramChannel#setPathPolicy(PathPolicy)
   */
  public void setPathPolicy(PathPolicy pathPolicy) throws IOException {
    channel.setPathPolicy(pathPolicy);
  }

  /**
   * Specify an source address override. See {@link
   * ScionDatagramChannel#setOverrideSourceAddress(InetSocketAddress)}.
   *
   * @param overrideSourceAddress Override address
   * @deprecated This will likely be removed in a future version
   */
  @Deprecated
  public void setOverrideSourceAddress(InetSocketAddress overrideSourceAddress) {
    channel.setOverrideSourceAddress(overrideSourceAddress);
  }
}
