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

  public static ScmpDatagramChannel open() throws IOException {
    return new ScmpDatagramChannel();
  }

  public static ScmpDatagramChannel open(ScionService service) throws IOException {
    return new ScmpDatagramChannel(service);
  }

  protected ScmpDatagramChannel() throws IOException {
    this(null);
  }

  protected ScmpDatagramChannel(ScionService service) throws IOException {
    super(service);
    configureBlocking(true);
    this.bufferReceive = ByteBuffer.allocateDirect(getOption(StandardSocketOptions.SO_RCVBUF));
    this.bufferSend = ByteBuffer.allocateDirect(getOption(StandardSocketOptions.SO_SNDBUF));
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

  synchronized Scmp.Message receiveScmp(Scmp.Message msg) throws IOException {
    ResponsePath receivePath = receiveFromChannel(bufferReceive, InternalConstants.HdrTypes.SCMP);
    if (receivePath == null) {
      return null; // non-blocking, nothing available
    }
    msg.setPath(receivePath);
    Scmp.Message scmpMsg = ScmpParser.consume(bufferReceive, msg);
    checkListeners(scmpMsg);
    return scmpMsg;
  }

  @Deprecated // TODO REMOVE THIS
  public synchronized void receive() throws IOException {
    receiveFromChannel(bufferReceive, InternalConstants.HdrTypes.UDP);
  }

  public synchronized void sendEchoRequest(Path path, int sequenceNumber, ByteBuffer data)
      throws IOException {
    // EchoHeader = 8 + data
    int len = 8 + data.remaining();
    Path actualPath = buildHeader(bufferSend, path, len, InternalConstants.HdrTypes.SCMP);
    ScmpParser.buildScmpPing(bufferSend, getLocalAddress().getPort(), sequenceNumber, data);
    bufferSend.flip();
    sendRaw(bufferSend, actualPath.getFirstHopAddress());
  }

  public synchronized Scmp.EchoResult sendEchoRequest(Scmp.EchoResult request) throws IOException {
    // EchoHeader = 8 + data
    int len = 8 + request.getData().length;
    Path actualPath =
        buildHeader(bufferSend, getCurrentPath(), len, InternalConstants.HdrTypes.SCMP);
    request.setPath(actualPath);
    ScmpParser.buildScmpPing(
        bufferSend, getLocalAddress().getPort(), request.getSequenceNumber(), request.getData());
    bufferSend.flip();
    sendRaw(bufferSend, actualPath.getFirstHopAddress());
    return request;
  }

  void sendTracerouteRequest(Path path, int interfaceNumber, PathHeaderParser.Node node)
      throws IOException {
    // TracerouteHeader = 24
    int len = 24;
    // TODO we are modifying the raw path here, this is bad! It breaks concurrent usage.
    //   we should only modify the outgoing packet.
    byte[] raw = path.getRawPath();
    raw[node.posHopFlags] = node.hopFlags;
    Path actualPath = buildHeader(bufferSend, path, len, InternalConstants.HdrTypes.SCMP);
    ScmpParser.buildScmpTraceroute(bufferSend, getLocalAddress().getPort(), interfaceNumber);
    bufferSend.flip();
    sendRaw(bufferSend, actualPath.getFirstHopAddress());
    // Clean up!  // TODO this is really bad!
    raw[node.posHopFlags] = 0;
  }
}
