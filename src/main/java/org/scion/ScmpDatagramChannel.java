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
import java.util.function.Consumer;
import org.scion.internal.InternalConstants;
import org.scion.internal.ScmpParser;

@Deprecated
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
    Scmp.Message scmpMsg =
        ScmpParser.consume(bufferReceive, Scmp.EchoMessage.createEmpty(receivePath));
    checkListeners(scmpMsg);
    return scmpMsg;
  }

  public synchronized Consumer<Scmp.EchoMessage> setEchoListener(
      Consumer<Scmp.EchoMessage> listener) {
    return super.setEchoListener(listener);
  }
}
