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
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.scion.internal.PathHeaderParser;

class ScmpChannel {
  private final AtomicLong nowNanos = new AtomicLong();
  private final ByteBuffer sendBuffer = ByteBuffer.allocateDirect(8);
  private int currentInterface;
  private final int localPort;
  private DatagramChannel channel;
  private RequestPath path;
  private List<PathHeaderParser.Node> nodes;

  private final List<Scmp.Result<Scmp.ScmpTraceroute>> result = new ArrayList<>();
  private Scmp.ScmpMessage error = null;

  public ScmpChannel(RequestPath path) {
    this(path, 12345);
  }

  public ScmpChannel(RequestPath path, int port) {
    this.localPort = port;
    this.path = path;
  }

  private void traceListener(Scmp.ScmpTraceroute msg) {
    long nanos = Instant.now().getNano() - nowNanos.get();
    result.add(new Scmp.Result(msg, nanos));
    send();
  }

  private void errorListener(Scmp.ScmpMessage msg) {
    error = msg;
    Thread.currentThread().interrupt();
    throw new RuntimeException();
  }

  private String getPassedMillies() {
    long nanos = Instant.now().getNano() - nowNanos.get();
    return String.format("%.4f", nanos / (double) 1_000_000);
  }

  public List<Scmp.Result<Scmp.ScmpTraceroute>> sendTraceroute() throws IOException {
    InetSocketAddress local = new InetSocketAddress("0.0.0.0", localPort);
    ScionService service = Scion.defaultService();
    try (DatagramChannel channel = service.openChannel().bind(local)) {
      channel.configureBlocking(true);
      this.channel = channel;

      channel.setScmpErrorListener(this::errorListener);
      channel.setTracerouteListener(this::traceListener);

      InetSocketAddress destinationAddress =
          new InetSocketAddress(Inet4Address.getByAddress(new byte[] {0, 0, 0, 0}), 23456);
      nodes = PathHeaderParser.getTraceNodes(path.getRawPath());

      Thread thread =
          new Thread(
              () -> {
                send();

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

      channel.disconnect();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    if (error != null) {
      throw new IOException(error.getTypeCode().getText());
    }
    return result;
  }

  private void send() {
    if (currentInterface >= path.getInterfacesList().size()) {
      Thread.currentThread().interrupt();
      return;
    }

    sendBuffer.clear();
    sendBuffer.putLong(localPort);
    sendBuffer.flip();
    nowNanos.set(Instant.now().getNano());
    try {
      // TODO why pass in path???????! Why not channel.default path?
      channel.sendTracerouteRequest(path, currentInterface, nodes.get(currentInterface));
      currentInterface++;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
