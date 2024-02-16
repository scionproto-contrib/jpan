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

  private synchronized Scmp.EchoResult sendEchoRequest(Scmp.EchoResult request) throws IOException {
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
    Scmp.Message scmpMsg = null;
    ResponsePath receivePath = null;
    while (scmpMsg == null) {
      receivePath = receiveFromChannel(bufferReceive, InternalConstants.HdrTypes.SCMP);
      scmpMsg = ScmpParser.consume(bufferReceive, request);
    }
    scmpMsg.setPath(receivePath);
    checkListeners(scmpMsg);
    return (Scmp.EchoResult) scmpMsg;
  }

  private Scmp.TracerouteResult sendTracerouteRequest(
      Scmp.TracerouteResult request, PathHeaderParser.Node node) throws IOException {
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
    Scmp.Message scmpMsg = null;
    ResponsePath receivePath = null;
    while (scmpMsg == null) {
      receivePath = receiveFromChannel(bufferReceive, InternalConstants.HdrTypes.SCMP);
      scmpMsg = ScmpParser.consume(bufferReceive, request);
    }

    scmpMsg.setPath(receivePath);
    checkListeners(scmpMsg);
    return (Scmp.TracerouteResult) scmpMsg;
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
    RequestPath path = (RequestPath) getCurrentPath();
    if (!isConnected()) {
      connect(path);
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
      Scmp.EchoResult msg = sendEchoRequest(result.get());
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
    Path path = getCurrentPath();
    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    for (int i = 0; i < nodes.size(); i++) {
      if (!sendConcurrentTraceRequest(i, path, nodes.get(i), results)) {
        // timeout: abort
        break;
      }
    }
    return results;
  }

  private boolean sendConcurrentTraceRequest(
      int sequenceNumber,
      Path path,
      PathHeaderParser.Node node,
      ConcurrentLinkedQueue<Scmp.TracerouteResult> results)
      throws IOException {
    AtomicReference<IOException> exception = new AtomicReference<>();

    Thread t =
        new Thread(() -> sendTracerouteRequest(path, sequenceNumber, node, results, exception));
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
      Path path,
      int sequenceNumber,
      PathHeaderParser.Node node,
      ConcurrentLinkedQueue<Scmp.TracerouteResult> results,
      AtomicReference<IOException> exception) {
    try {
      Scmp.TracerouteResult trace = Scmp.TracerouteResult.createRequest(sequenceNumber, path);
      long sendNanos = System.nanoTime();
      trace = sendTracerouteRequest(trace, node);
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

  public void setTimeOut(int milliSeconds) {
    this.timeOutMs = milliSeconds;
  }
}
