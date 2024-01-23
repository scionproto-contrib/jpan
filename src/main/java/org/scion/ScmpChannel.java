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
import java.nio.channels.ClosedByInterruptException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.internal.PathHeaderParser;

public class ScmpChannel implements AutoCloseable {
  private final AtomicLong nowNanos = new AtomicLong();
  private int currentInterface;
  private final DatagramChannel channel;
  private final RequestPath path;
  private List<PathHeaderParser.Node> nodes;
  private List<Scmp.Result<Scmp.ScmpTraceroute>> traceResults;
  private final AtomicReference<Scmp.ScmpMessage> error = new AtomicReference<>();

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

  private void traceListener(Scmp.ScmpTraceroute msg) {
    long nanos = Instant.now().getNano() - nowNanos.get();
    traceResults.add(new Scmp.Result<>(msg, nanos));
    sendTraceRequest();
  }

  private void errorListener(Scmp.ScmpMessage msg) {
    error.set(msg);
    Thread.currentThread().interrupt();
    throw new RuntimeException();
  }

  public Scmp.Result<Scmp.ScmpEcho> sendEchoRequest(int sequenceNumber, ByteBuffer data)
      throws IOException {
    if (!channel.isConnected()) {
      channel.connect(path);
    }
    // TODO setOption(SO_TIMEOUT);
    nowNanos.set(Instant.now().getNano());
    // TODO why pass in path???????! Why not channel.default path?
    channel.sendEchoRequest(path, sequenceNumber, data);

    Scmp.ScmpEcho msg = (Scmp.ScmpEcho) channel.receiveScmp();
    long nanos = Instant.now().getNano() - nowNanos.get();

    if (error.get() != null) {
      // I know, this is not completely thread safe...
      throw new IOException(error.get().getTypeCode().getText());
    }
    return new Scmp.Result<>(msg, nanos);
  }

  public List<Scmp.Result<Scmp.ScmpTraceroute>> sendTracerouteRequest() throws IOException {
    traceResults = new ArrayList<>();
    try {
      channel.setTracerouteListener(this::traceListener);
      nodes = PathHeaderParser.getTraceNodes(path.getRawPath());

      // TODO remove threading and use channel.receiveScmp() instead
      Thread thread =
          new Thread(
              () -> {
                sendTraceRequest();

                try {
                  channel.receive(null);
                } catch (ClosedByInterruptException e) {
                  // Ignore
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
      thread.start();
      thread.join();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } finally {
      channel.setTracerouteListener(null);
    }
    if (error.get() != null) {
      // I know, this is not completely thread safe...
      throw new IOException(error.get().getTypeCode().getText());
    }
    return traceResults;
  }

  private void sendTraceRequest() {
    if (currentInterface >= path.getInterfacesList().size()) {
      Thread.currentThread().interrupt();
      return;
    }

    nowNanos.set(Instant.now().getNano());
    try {
      // TODO why pass in path???????! Why not channel.default path?
      channel.sendTracerouteRequest(path, currentInterface, nodes.get(currentInterface));
      currentInterface++;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
