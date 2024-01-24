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
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.scion.internal.PathHeaderParser;

public class ScmpChannel implements AutoCloseable {
  private final DatagramChannel channel;
  private final RequestPath path;
  private Scmp.ScmpMessage error;

  ScmpChannel(RequestPath path) throws IOException {
    this(path, 12345);
  }

  ScmpChannel(RequestPath path, int port) throws IOException {
    this.path = path;
    InetSocketAddress local = new InetSocketAddress("0.0.0.0", port);
    ScionService service = Scion.defaultService();
    this.channel = service.openChannel().bind(local);
    channel.configureBlocking(true);
    channel.setScmpErrorListener(this::errorListener);
  }

  private void errorListener(Scmp.ScmpMessage msg) {
    error = msg;
    Thread.currentThread().interrupt();
    throw new RuntimeException();
  }

  public Scmp.Result<Scmp.ScmpEcho> sendEchoRequest(int sequenceNumber, ByteBuffer data)
      throws IOException {
    if (!channel.isConnected()) {
      channel.connect(path);
    }
    // TODO setOption(SO_TIMEOUT);
    long sendNanos = Instant.now().getNano();
    // TODO why pass in path???????! Why not channel.default path?
    channel.sendEchoRequest(path, sequenceNumber, data);

    Scmp.ScmpEcho msg = (Scmp.ScmpEcho) channel.receiveScmp();
    long nanos = Instant.now().getNano() - sendNanos;

    if (error != null) {
      // I know, this is not completely thread safe...
      throw new IOException(error.getTypeCode().getText());
    }
    return new Scmp.Result<>(msg, nanos);
  }

  public List<Scmp.Result<Scmp.ScmpTraceroute>> sendTracerouteRequest() throws IOException {
    List<Scmp.Result<Scmp.ScmpTraceroute>> traceResults = new ArrayList<>();
    try {
      List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());

      for (int i = 0; i < path.getInterfacesList().size(); i++) {
        long sendNanos = Instant.now().getNano();
        try {
          // TODO why pass in path???????! Why not channel.default path?
          channel.sendTracerouteRequest(path, i, nodes.get(i));

          Scmp.ScmpTraceroute msg = (Scmp.ScmpTraceroute) channel.receiveScmp();
          long nanos = Instant.now().getNano() - sendNanos;
          traceResults.add(new Scmp.Result<>(msg, nanos));

        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    } finally {
      channel.setTracerouteListener(null);
    }
    if (error != null) {
      // I know, this is not completely thread safe...
      throw new IOException(error.getTypeCode().getText());
    }
    return traceResults;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
