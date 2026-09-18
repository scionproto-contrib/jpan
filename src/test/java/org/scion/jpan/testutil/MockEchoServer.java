// Copyright 2026 ETH Zurich
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

package org.scion.jpan.testutil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Plain (non-SNAP) UDP "mirror": sends back, verbatim, whatever datagram it receives. */
public class MockEchoServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MockEchoServer.class);

  private final DatagramChannel channel;
  private volatile boolean running;
  private Thread thread;

  private MockEchoServer(DatagramChannel channel) {
    this.channel = channel;
  }

  public static MockEchoServer start() throws IOException {
    DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
    channel.configureBlocking(true);
    channel.bind(new InetSocketAddress("127.0.0.1", 0));
    MockEchoServer server = new MockEchoServer(channel);
    server.startInternal();
    return server;
  }

  private void startInternal() {
    running = true;
    thread = new Thread(this::loop, "MockEchoServer");
    thread.setDaemon(true);
    thread.start();
  }

  private void loop() {
    ByteBuffer buf = ByteBuffer.allocate(65535);
    while (running) {
      try {
        buf.clear();
        InetSocketAddress sender = (InetSocketAddress) channel.receive(buf);
        if (sender == null) {
          TestUtil.sleep(2);
          continue;
        }
        buf.flip();
        channel.send(buf, sender);
        log.debug("MockEchoServer echoed {} bytes back to {}", buf.limit(), sender);
      } catch (ClosedChannelException e) {
        break;
      } catch (IOException e) {
        if (running) {
          log.error("Error in MockEchoServer loop", e);
        }
      }
    }
  }

  public InetSocketAddress getAddress() throws IOException {
    return (InetSocketAddress) channel.getLocalAddress();
  }

  @Override
  public void close() {
    running = false;
    try {
      channel.close();
    } catch (IOException e) {
      log.warn("Error closing MockEchoServer channel", e);
    }
    if (thread != null) {
      try {
        thread.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
