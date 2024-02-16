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

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import org.scion.internal.InternalConstants;
import org.scion.internal.PathHeaderParser;
import org.scion.internal.ScmpParser;

public class ScmpDatagramChannel extends AbstractDatagramChannel<ScmpDatagramChannel>
    implements Closeable {

  private final ByteBuffer bufferReceive;
  private final ByteBuffer bufferSend;

  protected ScmpDatagramChannel() throws IOException {
    this(null);
  }

  protected ScmpDatagramChannel(ScionService service) throws IOException {
    super(service);
    configureBlocking(true);
    this.bufferReceive = ByteBuffer.allocateDirect(getOption(StandardSocketOptions.SO_RCVBUF));
    this.bufferSend = ByteBuffer.allocateDirect(getOption(StandardSocketOptions.SO_SNDBUF));
  }

  public static ScmpDatagramChannel open() throws IOException {
    return new ScmpDatagramChannel();
  }

  public static ScmpDatagramChannel open(ScionService service) throws IOException {
    return new ScmpDatagramChannel(service);
  }

  synchronized Scmp.Message receiveScmp() throws IOException {
    ResponsePath receivePath = receiveFromChannel(bufferReceive, InternalConstants.HdrTypes.SCMP);
    if (receivePath == null) {
      return null; // non-blocking, nothing available
    }

    Scmp.Message scmpMsg = ScmpParser.consume(bufferReceive, receivePath);
    checkListeners(scmpMsg);
    return scmpMsg;
  }

  //  synchronized Scmp.Message receiveScmp(Scmp.Message msg) throws IOException {
  //    ResponsePath receivePath = receiveFromChannel(bufferReceive,
  // InternalConstants.HdrTypes.SCMP);
  //    if (receivePath == null) {
  //      return null; // non-blocking, nothing available
  //    }
  //    msg.setPath(receivePath);
  //    Scmp.Message scmpMsg = ScmpParser.consume(bufferReceive, msg);
  //    checkListeners(scmpMsg);
  //    return scmpMsg;
  //  }

  @Deprecated // TODO REMOVE THIS
  public synchronized void receive() throws IOException {
    receiveFromChannel(bufferReceive, InternalConstants.HdrTypes.UDP);
  }

  @Deprecated
  public synchronized Scmp.Message sendEchoRequest(Path path, int sequenceNumber, ByteBuffer data)
      throws IOException {
    // send
    // EchoHeader = 8 + data
    int len = 8 + data.remaining();
    Path actualPath = buildHeader(bufferSend, path, len, InternalConstants.HdrTypes.SCMP);
    ScmpParser.buildScmpPing(bufferSend, getLocalAddress().getPort(), sequenceNumber, data);
    bufferSend.flip();
    sendRaw(bufferSend, actualPath.getFirstHopAddress());

    // receive
    ResponsePath receivePath = receiveFromChannel(bufferReceive, InternalConstants.HdrTypes.SCMP);
    Scmp.Message scmpMsg = ScmpParser.consume(bufferReceive, receivePath);
    checkListeners(scmpMsg);
    return scmpMsg;
  }

  synchronized Scmp.EchoResult sendEchoRequest(Scmp.EchoResult request) throws IOException {
    // send
    // EchoHeader = 8 + data
    int len = 8 + request.getData().length;
    Path path = buildHeader(bufferSend, request.getPath(), len, InternalConstants.HdrTypes.SCMP);
    request.setPath(path);
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

  void sendTracerouteRequestOld(RequestPath path, int interfaceNumber, PathHeaderParser.Node node)
      throws IOException {
    path = ensureUpToDate(path);
    // TracerouteHeader = 24
    int len = 24;
    // TODO we are modifying the raw path here, this is bad! It breaks concurrent usage.
    //   we should only modify the outgoing packet.
    byte[] raw = path.getRawPath();
    raw[node.posHopFlags] = node.hopFlags;

    buildHeaderNoRefresh(bufferSend, path, len, InternalConstants.HdrTypes.SCMP);
    ScmpParser.buildScmpTraceroute(bufferSend, getLocalAddress().getPort(), interfaceNumber);
    bufferSend.flip();
    sendRaw(bufferSend, path.getFirstHopAddress());
    // Clean up!  // TODO this is really bad!
    raw[node.posHopFlags] = 0;
  }

  Scmp.TracerouteResult sendTracerouteRequest(
      Scmp.TracerouteResult request, PathHeaderParser.Node node) throws IOException {
    Path path = request.getPath();
    // send
    // TracerouteHeader = 24
    int len = 24;
    // TODO we are modifying the raw path here, this is bad! It breaks concurrent usage.
    //   we should only modify the outgoing packet.
    byte[] raw = path.getRawPath();
    raw[node.posHopFlags] = node.hopFlags;

    buildHeaderNoRefresh(bufferSend, path, len, InternalConstants.HdrTypes.SCMP);
    int interfaceNumber = request.getSequenceNumber();
    ScmpParser.buildScmpTraceroute(bufferSend, getLocalAddress().getPort(), interfaceNumber);
    bufferSend.flip();
    sendRaw(bufferSend, path.getFirstHopAddress());
    // Clean up!  // TODO this is really bad!
    raw[node.posHopFlags] = 0;

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
}
