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

package org.scion;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import org.scion.internal.InternalConstants;
import org.scion.internal.PathHeaderParser;
import org.scion.internal.ScionHeaderParser;
import org.scion.internal.ScmpParser;

public class ScmpChannel implements AutoCloseable {
  private int timeOutMs = 1000;
  private final InternalChannel channel;

  ScmpChannel(RequestPath path) throws IOException {
    this(path, 12345);
  }

  ScmpChannel(RequestPath path, int port) throws IOException {
    channel = new InternalChannel(Scion.defaultService(), path, port);
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
    RequestPath path = (RequestPath) channel.getCurrentPath();
    Scmp.EchoMessage request = Scmp.EchoMessage.createRequest(sequenceNumber, path, data);
    sendScmpRequest(() -> channel.sendEchoRequest(request), Scmp.ScmpTypeCode.TYPE_129);
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
  public Collection<Scmp.TracerouteMessage> sendTracerouteRequest() throws IOException {
    List<Scmp.TracerouteMessage> requests = new ArrayList<>();
    RequestPath path = (RequestPath) channel.getCurrentPath();
    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    for (int i = 0; i < nodes.size(); i++) {
      Scmp.TracerouteMessage request = Scmp.TracerouteMessage.createRequest(i, path);
      requests.add(request);
      PathHeaderParser.Node node = nodes.get(i);
      sendScmpRequest(
          () -> channel.sendTracerouteRequest(request, node), Scmp.ScmpTypeCode.TYPE_131);
      if (request.isTimedOut()) {
        break;
      }
    }
    return requests;
  }

  private void sendScmpRequest(
      IOCallable<Scmp.TimedMessage> sender, Scmp.ScmpTypeCode expectedTypeCode) throws IOException {
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
    private final ByteBuffer bufferReceive;
    private final ByteBuffer bufferSend;
    private final Selector selector;

    protected InternalChannel(ScionService service, RequestPath path, int port) throws IOException {
      super(service);
      this.bufferReceive = ByteBuffer.allocateDirect(getOption(StandardSocketOptions.SO_RCVBUF));
      this.bufferSend = ByteBuffer.allocateDirect(getOption(StandardSocketOptions.SO_SNDBUF));

      // selector
      this.selector = Selector.open();
      super.channel().configureBlocking(false);
      super.channel().register(selector, SelectionKey.OP_READ);

      super.setPath(path);
      // listen on ANY interface: 0.0.0.0 / [::]
      super.bind(new InetSocketAddress("[::]", port));
    }

    synchronized Scmp.TimedMessage sendEchoRequest(Scmp.EchoMessage request) throws IOException {
      // EchoHeader = 8 + data
      int len = 8 + request.getData().length;
      Path path = request.getPath();
      buildHeaderNoRefresh(bufferSend, request.getPath(), len, InternalConstants.HdrTypes.SCMP);
      ScmpParser.buildScmpPing(
          bufferSend, getLocalAddress().getPort(), request.getSequenceNumber(), request.getData());
      bufferSend.flip();
      request.setSizeSent(bufferSend.remaining());
      sendRaw(bufferSend, path.getFirstHopAddress());

      receiveRequest(request);
      request.setSizeReceived(bufferReceive.position());
      return request;
    }

    synchronized Scmp.TimedMessage sendTracerouteRequest(
        Scmp.TracerouteMessage request, PathHeaderParser.Node node) throws IOException {
      Path path = request.getPath();
      // TracerouteHeader = 24
      int len = 24;
      buildHeaderNoRefresh(bufferSend, path, len, InternalConstants.HdrTypes.SCMP);
      int interfaceNumber = request.getSequenceNumber();
      ScmpParser.buildScmpTraceroute(bufferSend, getLocalAddress().getPort(), interfaceNumber);
      bufferSend.flip();

      // Set flags for border routers to return SCMP packet
      int posPath = ScionHeaderParser.extractPathHeaderPosition(bufferSend);
      bufferSend.put(posPath + node.posHopFlags, node.hopFlags);

      sendRaw(bufferSend, path.getFirstHopAddress());

      receiveRequest(request);
      return request;
    }

    synchronized void receiveRequest(Scmp.TimedMessage request) throws IOException {
      ResponsePath receivePath = receiveWithTimeout(bufferReceive);
      if (receivePath != null) {
        ScmpParser.consume(bufferReceive, request);
        request.setPath(receivePath);
        checkListeners(request);
      } else {
        request.setTimedOut();
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
}
