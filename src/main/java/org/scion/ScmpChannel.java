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
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.scion.internal.PathHeaderParser;

public class ScmpChannel implements AutoCloseable {
  private final ScmpDatagramChannel channel;
  private final RequestPath path;
  private final AtomicReference<Scmp.Message> error = new AtomicReference<>();
  private int timeOutMs = 1000;

  ScmpChannel(RequestPath path) throws IOException {
    this(path, 12345);
  }

  ScmpChannel(RequestPath path, int port) throws IOException {
    this.path = path;
    InetSocketAddress local = new InetSocketAddress("0.0.0.0", port);
    ScionService service = Scion.defaultService();
    this.channel = ScmpDatagramChannel.open(service).bind(local);
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
  public Scmp.EchoResult sendEchoRequest(int sequenceNumber, ByteBuffer data) throws IOException {
    if (!channel.isConnected()) {
      channel.connect(path);
    }
    // Hack: we do not modify the AtomicReference.It simply serves as a memory barrier
    // to facilitate concurrent access to the result.
    AtomicReference<Scmp.EchoResult> result = new AtomicReference<>();
    AtomicReference<IOException> exception = new AtomicReference<>();

    result.set(Scmp.EchoResult.createRequest(sequenceNumber, path, data));
    Thread t = new Thread(() -> sendEchoRequest(result, exception));
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
      Scmp.EchoResult echo = result.get();
      echo.setTimedOut(timeOutMs * 1_000_000L);
      return echo;
    }
    return result.get();
  }

  private void sendEchoRequest(
      AtomicReference<Scmp.EchoResult> result, AtomicReference<IOException> exception) {
    try {
      long sendNanos = System.nanoTime();
      Scmp.EchoResult msg = channel.sendEchoRequest(result.get());
      long nanos = System.nanoTime() - sendNanos;
      if (msg.getTypeCode() == Scmp.ScmpTypeCode.TYPE_129) {
        msg.setNanoSeconds(nanos);
      } else {
        // error
        throw new IOException("SCMP error: " + msg.getTypeCode().getText());
      }
    } catch (IOException e) {
      exception.set(e);
    }
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
  public Collection<Scmp.TracerouteResult> sendTracerouteRequest() throws IOException {
    ConcurrentLinkedQueue<Scmp.TracerouteResult> results = new ConcurrentLinkedQueue<>();
    try {
      List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
      for (int i = 0; i < nodes.size(); i++) {
        if (!sendConcurrentTraceRequest(i, nodes.get(i), results)) {
          // timeout: abort
          break;
        }
      }
    } finally {
      channel.setTracerouteListener(null);
    }
    return results;
  }

  private boolean sendConcurrentTraceRequest(
      int sequenceNumber,
      PathHeaderParser.Node node,
      ConcurrentLinkedQueue<Scmp.TracerouteResult> results)
      throws IOException {
    AtomicReference<IOException> exception = new AtomicReference<>();

    Thread t = new Thread(() -> sendTracerouteRequest(sequenceNumber, node, results, exception));
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
      results.add(Scmp.TracerouteResult.createTimedOut(timeOutMs * 1_000_000L));
      return false;
    }
    return true;
  }

  private void sendTracerouteRequest(
      int sequenceNumber,
      PathHeaderParser.Node node,
      ConcurrentLinkedQueue<Scmp.TracerouteResult> results,
      AtomicReference<IOException> exception) {
    try {
      long sendNanos = System.nanoTime();
      Scmp.TracerouteResult trace = Scmp.TracerouteResult.createRequest(sequenceNumber, path);
      trace = channel.sendTracerouteRequest(trace, node);
      long nanos = System.nanoTime() - sendNanos;
      if (trace.getTypeCode() == Scmp.ScmpTypeCode.TYPE_131) {
        trace.setNanoSeconds(nanos);
        results.add(trace);
      } else {
        // error
        throw new IOException("SCMP error: " + trace.getTypeCode().getText());
      }
    } catch (IOException e) {
      exception.set(e);
    }
  }

  public Consumer<Scmp.Message> setScmpErrorListener(Consumer<Scmp.Message> listener) {
    return channel.setScmpErrorListener(listener);
  }

  public synchronized <T> ScmpChannel setOption(SocketOption<T> option, T t) throws IOException {
    channel.setOption(option, t);
    return this;
  }

  public void setTimeOut(int milliSeconds) {
    this.timeOutMs = milliSeconds;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
