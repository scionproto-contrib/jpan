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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.DatagramChannel;
import org.scion.ScionSocketOptions;
import org.scion.internal.ScionHeaderParser;
import org.scion.testutil.ExamplePacket;

class DatagramChannelPacketValidationTest {

  private final String PRE = "SCION packet validation failed: ";
  private final AtomicReference<SocketAddress> localAddress = new AtomicReference<>();
  private final AtomicInteger receiveCount = new AtomicInteger();
  private final AtomicInteger receiveBadCount = new AtomicInteger();
  private final AtomicReference<Exception> failure = new AtomicReference<>();
  private CountDownLatch barrier;
  private static final String MSG = ExamplePacket.MSG;
  private static final byte[] packetBytes = ExamplePacket.PACKET_BYTES;

  @BeforeEach
  public void beforeEach() {
    localAddress.set(null);
    receiveCount.set(0);
    receiveBadCount.set(0);
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

  @Disabled
  @Test
  public void receive_validationFails_nonBlocking_noThrow()
          throws IOException, InterruptedException {
    // silently drop bad packets
    //receive_validationFails_isBlocking_noThrow(false);
  }

  @Disabled
  @Test
  public void receive_validationFails_nonBlocking_throw() throws IOException, InterruptedException {
    // throw exception when receiving bad packet
    //receive_validationFails_isBlocking_noThrow(true);
  }

  @Test
  public void receive_validationFails_isBlocking_noThrow()
          throws IOException, InterruptedException {
    // silently drop bad packets
    receive_validationFails_isBlocking_noThrow(false);
  }

  @Test
  public void receive_validationFails_isBlocking_throw() throws IOException, InterruptedException {
    // throw exception when receiving bad packet
    receive_validationFails_isBlocking_noThrow(true);
  }

  private void receive_validationFails_isBlocking_noThrow(boolean throwBad)
      throws IOException, InterruptedException {
    barrier = new CountDownLatch(1);
    Thread serverThread = startServer(throwBad);
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

  private Thread startServer(boolean openThrowOnBadPacket) {
    Thread serverThread =
        new Thread(
            () -> {
              try {
                try (DatagramChannel channel = DatagramChannel.open()) {
                  channel.configureBlocking(true);
                  if (openThrowOnBadPacket) {
                    channel.setOption(ScionSocketOptions.API_THROW_PARSER_FAILURE, true);
                  }
                  channel.bind(null);
                  localAddress.set(channel.getLocalAddress());
                  barrier.countDown();

                  ByteBuffer response = ByteBuffer.allocate(500);
                  // repeat until we get no exception
                  boolean failed;
                  do {
                    failed = false;
                    try {
                      assertNotNull(channel.receive(response));
                    } catch (Exception e) {
                      receiveBadCount.incrementAndGet();
                      failed = true;
                    }
                  } while (failed);

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