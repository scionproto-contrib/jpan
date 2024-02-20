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
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.internal.InternalConstants;
import org.scion.internal.PathHeaderParser;
import org.scion.internal.ScmpParser;

public class ScmpChannel extends AbstractDatagramChannel<ScmpChannel> implements AutoCloseable {
  private final ByteBuffer bufferReceive;
  private final ByteBuffer bufferSend;
  private final AtomicReference<Scmp.Message> error = new AtomicReference<>();
  private int timeOutMs = 1000;

  ScmpChannel(RequestPath path) throws IOException {
    this(path, 12345);
  }

  ScmpChannel(RequestPath path, int port) throws IOException {
    super(Scion.defaultService());
    configureBlocking(true);
    this.bufferReceive = ByteBuffer.allocateDirect(getOption(StandardSocketOptions.SO_RCVBUF));
    this.bufferSend = ByteBuffer.allocateDirect(getOption(StandardSocketOptions.SO_SNDBUF));

    setPath(path);
    InetSocketAddress local = new InetSocketAddress("0.0.0.0", port);
    this.bind(local);
  }

  private synchronized Scmp.EchoMessage sendEchoRequest(Scmp.EchoMessage request)
      throws IOException {
    // send
    // EchoHeader = 8 + data
    int len = 8 + request.getData().length;
    Path path = request.getPath();
    buildHeaderNoRefresh(bufferSend, request.getPath(), len, InternalConstants.HdrTypes.SCMP);
    ScmpParser.buildScmpPing(
        bufferSend, getLocalAddress().getPort(), request.getSequenceNumber(), request.getData());
    bufferSend.flip();
    sendRaw(bufferSend, path.getFirstHopAddress());

    // receive
    ResponsePath receivePath = receiveFromChannel(bufferReceive, InternalConstants.HdrTypes.SCMP);
    ScmpParser.consume(bufferReceive, request);
    request.setPath(receivePath);
    checkListeners(request);
    return request;
  }

  private Scmp.TracerouteMessage sendTracerouteRequest(
      Scmp.TracerouteMessage request, PathHeaderParser.Node node) throws IOException {
    Path path = request.getPath();
    // send
    // TracerouteHeader = 24
    int len = 24;
    // TODO we are modifying the raw path here, this is bad! It breaks concurrent usage.
    //   we should only modify the outgoing packet.
    byte[] raw = path.getRawPath();
    byte backup = raw[node.posHopFlags];
    raw[node.posHopFlags] = node.hopFlags;

    buildHeaderNoRefresh(bufferSend, path, len, InternalConstants.HdrTypes.SCMP);
    int interfaceNumber = request.getSequenceNumber();
    ScmpParser.buildScmpTraceroute(bufferSend, getLocalAddress().getPort(), interfaceNumber);
    bufferSend.flip();
    sendRaw(bufferSend, path.getFirstHopAddress());
    // Clean up!  // TODO this is really bad!
    raw[node.posHopFlags] = backup;

    // receive
    ResponsePath receivePath = receiveFromChannel(bufferReceive, InternalConstants.HdrTypes.SCMP);
    ScmpParser.consume(bufferReceive, request);

    request.setPath(receivePath);
    checkListeners(request);
    return request;
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
  //  public Scmp.EchoMessage sendEchoRequestOld(int sequenceNumber, ByteBuffer data) throws
  // IOException {
  //    RequestPath path = (RequestPath) getCurrentPath();
  //    if (!isConnected()) {
  //      connect(path);
  //    }
  //    // Hack: we do not modify the AtomicReference.It simply serves as a memory barrier
  //    // to facilitate concurrent access to the result.
  //    AtomicReference<Scmp.EchoMessage> result = new AtomicReference<>();
  //    AtomicReference<IOException> exception = new AtomicReference<>();
  //
  //    result.set(Scmp.EchoMessage.createRequest(sequenceNumber, path, data));
  //    Thread t = new Thread(() -> sendEchoRequest(result, exception));
  //    t.start();
  //    try {
  //      t.join(timeOutMs);
  //    } catch (InterruptedException e) {
  //      Thread.currentThread().interrupt();
  //      throw new RuntimeException(e);
  //    }
  //
  //    if (exception.get() != null) {
  //      throw new IOException(exception.get());
  //    }
  //    if (error.get() != null) {
  //      // we received a SCMP error
  //      throw new IOException(error.get().getTypeCode().getText());
  //    }
  //    if (t.isAlive()) {
  //      // timeout
  //      t.interrupt();
  //      Scmp.EchoMessage echo = result.get();
  //      echo.setTimedOut(timeOutMs * 1_000_000L);
  //      return echo;
  //    }
  //    return result.get();
  //  }
  //
  //  private void sendEchoRequest(
  //      AtomicReference<Scmp.EchoMessage> result, AtomicReference<IOException> exception) {
  //    try {
  //      long sendNanos = System.nanoTime();
  //      sendEchoRequest(result.get());
  //      long nanos = System.nanoTime() - sendNanos;
  //      if (result.get().getTypeCode() == Scmp.ScmpTypeCode.TYPE_129) {
  //        result.get().setNanoSeconds(nanos);
  //      } else {
  //        // error
  //        throw new IOException("SCMP error: " + result.get().getTypeCode().getText());
  //      }
  //    } catch (IOException e) {
  //      exception.set(e);
  //    }
  //  }

  public Scmp.EchoMessage sendEchoRequest(int sequenceNumber, ByteBuffer data) throws IOException {
    RequestPath path = (RequestPath) getCurrentPath();
    if (!isConnected()) {
      connect(path);
    }
    // Hack: we do not modify the AtomicReference.It simply serves as a memory barrier
    // to facilitate concurrent access to the result.
    AtomicReference<Scmp.EchoMessage> result = new AtomicReference<>();
    result.set(Scmp.EchoMessage.createRequest(sequenceNumber, path, data));
    ScmpExecutor ex = new ScmpExecutor(result.get(), () -> sendEchoRequest(result.get()));
    sendConcurrentScmpRequest(ex, Scmp.ScmpTypeCode.TYPE_129);
    return result.get();
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
    ConcurrentLinkedQueue<Scmp.TracerouteMessage> results = new ConcurrentLinkedQueue<>();
    Path path = getCurrentPath();
    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    for (int i = 0; i < nodes.size(); i++) {
      AtomicReference<Scmp.TracerouteMessage> result = new AtomicReference<>();
      result.set(Scmp.TracerouteMessage.createRequest(i, path));
      results.add(result.get());
      PathHeaderParser.Node node = nodes.get(i);
      ScmpExecutor ex =
          new ScmpExecutor(result.get(), () -> sendTracerouteRequest(result.get(), node));
      sendConcurrentScmpRequest(ex, Scmp.ScmpTypeCode.TYPE_131);
      if (result.get().isTimedOut()) {
        break;
      }
    }
    return results;
  }

  private void sendConcurrentScmpRequest(ScmpExecutor executor, Scmp.ScmpTypeCode expectedTypeCode)
      throws IOException {
    AtomicReference<IOException> exception = new AtomicReference<>();

    Thread t = new Thread(() -> sendScmpRequest(exception, executor, expectedTypeCode));
    t.start();
    try {
      t.join(timeOutMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }

    if (exception.get() != null) {
      throw new IOException(exception.get());
    }
    if (error.get() != null) {
      // we received a SCMP error
      throw new IOException(error.get().getTypeCode().getText());
    }
    if (t.isAlive()) {
      // timeout
      t.interrupt();
      executor.msg.get().setTimedOut(timeOutMs * 1_000_000L);
    }
  }

  private void sendScmpRequest(
      AtomicReference<IOException> exception,
      ScmpExecutor executor,
      Scmp.ScmpTypeCode expectedTypeCode) {
    try {
      long sendNanos = System.nanoTime();
      executor.executor.call();
      long nanos = System.nanoTime() - sendNanos;
      Scmp.TimedMessage result = executor.msg.get();
      if (result.getTypeCode() == expectedTypeCode) {
        result.setNanoSeconds(nanos);
      } else {
        // error
        exception.set(new IOException("SCMP error: " + result.getTypeCode().getText()));
      }
    } catch (IOException e) {
      exception.set(e);
    } catch (Exception e) {
      exception.set(new IOException(e));
    }
  }

  private static class ScmpExecutor {
    final AtomicReference<Scmp.TimedMessage> msg = new AtomicReference<>();
    final Callable<Scmp.TimedMessage> executor;

    ScmpExecutor(Scmp.TimedMessage msg, Callable<Scmp.TimedMessage> exec) {
      this.msg.set(msg);
      this.executor = exec;
    }
  }

  public void setTimeOut(int milliSeconds) {
    this.timeOutMs = milliSeconds;
  }
}
