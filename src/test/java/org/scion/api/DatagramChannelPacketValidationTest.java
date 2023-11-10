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

package org.scion.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.DatagramChannel;
import org.scion.internal.ScionHeaderParser;

class DatagramChannelPacketValidationTest {

  // TODO put MSG and packetBytes into separate class, they are dependent.
  private static final String MSG = "Hello scion";
  private final String PRE = "SCION packet validation failed: ";
  private final AtomicReference<SocketAddress> localAddress = new AtomicReference<>();
  private final AtomicInteger receiveCount = new AtomicInteger();
  private final AtomicReference<Exception> failure = new AtomicReference<>();
  private CountDownLatch barrier;


  private static final byte[] packetBytes = {
          0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
          0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 2,
          1, 0, 32, 0, 1, 0, -103, -90, 100, -20, 100, -13, 0, 63, 0, 0,
          0, 2, 62, 57, -82, 1, -16, 51, 0, 63, 0, 1, 0, 0, -104, 77,
          -24, 2, -64, -11, 0, 100, 31, -112, 0, 19, -15, -27, 72, 101, 108, 108,
          111, 32, 115, 99, 105, 111, 110,
  };

  @BeforeEach
  public void beforeEach() {
    localAddress.set(null);
    receiveCount.set(0);
    failure.set(null);
    barrier = null;
  }

  @Test
  void validate_length() {
    // packet too short
    for (int i = 0; i < packetBytes.length; i++) {
      ByteBuffer bb = ByteBuffer.allocate(i);
      bb.put(packetBytes, 0, i);
      bb.flip();
      String result = ScionHeaderParser.validate(bb);
      assertNotNull(result);
      assertTrue(result.startsWith(PRE + "Invalid packet length:"), result);
    }

    // correct length
    assertNull(ScionHeaderParser.validate(ByteBuffer.wrap(packetBytes)));

    // packet too long
    for (int i = packetBytes.length + 1; i < 2 * packetBytes.length; i++) {
      ByteBuffer bb = ByteBuffer.allocate(i);
      bb.put(packetBytes);
      for (int j = packetBytes.length; j < i; j++) {
        bb.put((byte) 0);
      }
      bb.flip();
      String result = ScionHeaderParser.validate(bb);
      assertNotNull(result);
      assertTrue(result.startsWith(PRE + "Invalid packet length:"), result);
    }
  }

  @Test
  public void receive_validationFails_isBlocking_noThrow() throws IOException, InterruptedException {
    barrier = new CountDownLatch(1);
    Thread serverThread = startServer();
    barrier.await(); // Wait for thread to start

    // client - send bad message
    for (int i = 0; i < 10; i++) {
      try (java.nio.channels.DatagramChannel channel = java.nio.channels.DatagramChannel.open()) {
        ByteBuffer outgoing = ByteBuffer.allocate(10);
        channel.send(outgoing, localAddress.get());
      }
    }

    // client send good message
    try (java.nio.channels.DatagramChannel channel = java.nio.channels.DatagramChannel.open()) {
      ByteBuffer outgoing = ByteBuffer.wrap(packetBytes);
      channel.send(outgoing, localAddress.get());
    }

    serverThread.join();

    // check results
    assertNull(failure.get());
    assertEquals(1, receiveCount.get());
  }

  private Thread startServer() {
    Thread serverThread = new Thread(() -> {
      try {
        try (DatagramChannel server = DatagramChannel.open()) {
          server.configureBlocking(true);
          server.bind(null);
          localAddress.set(server.getLocalAddress());
          barrier.countDown();

          ByteBuffer response = ByteBuffer.allocate(500);
          assertNotNull(server.receive(response));

          // make sure we receive exactly one message
          receiveCount.incrementAndGet();

          response.flip();
          String pong = Charset.defaultCharset().decode(response).toString();
          if (!MSG.equals(pong)) {
            failure.set(new IllegalStateException(pong));
          }
        }
      } catch (IOException e) {
        failure.set(e);
      }
    });
    serverThread.start();
    return serverThread;
  }
}
