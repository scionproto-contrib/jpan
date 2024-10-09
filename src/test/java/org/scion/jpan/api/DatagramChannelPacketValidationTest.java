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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionSocketOptions;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.testutil.ExamplePacket;

class DatagramChannelPacketValidationTest {

  private static final String MSG = ExamplePacket.MSG;
  private static final byte[] packetBytes = ExamplePacket.PACKET_BYTES_SERVER_E2E_PING;
  private final AtomicReference<SocketAddress> localAddress = new AtomicReference<>();
  private final AtomicInteger receiveCount = new AtomicInteger();
  private final AtomicInteger receiveBadCount = new AtomicInteger();
  private final AtomicReference<Exception> failure = new AtomicReference<>();
  private CountDownLatch barrier;

  @BeforeEach
  public void beforeEach() {
    localAddress.set(null);
    receiveCount.set(0);
    receiveBadCount.set(0);
    failure.set(null);
    barrier = null;
  }

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void validate_length() {
    String PRE = "SCION packet validation failed: ";
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
  void receive_validationFails_nonBlocking_noThrow() throws IOException, InterruptedException {
    // silently drop bad packets
    receive_validationFails_isBlocking_noThrow(false, false);
  }

  @Test
  void receive_validationFails_nonBlocking_throw() throws IOException, InterruptedException {
    // throw exception when receiving bad packet
    receive_validationFails_isBlocking_noThrow(true, false);
  }

  @Test
  void receive_validationFails_isBlocking_noThrow() throws IOException, InterruptedException {
    // silently drop bad packets
    receive_validationFails_isBlocking_noThrow(false, true);
  }

  @Test
  void receive_validationFails_isBlocking_throw() throws IOException, InterruptedException {
    // throw exception when receiving bad packet
    receive_validationFails_isBlocking_noThrow(true, true);
  }

  private void receive_validationFails_isBlocking_noThrow(boolean throwBad, boolean isBlocking)
      throws IOException, InterruptedException {
    barrier = new CountDownLatch(1);
    ExecutorService serverThread = startServer(throwBad, isBlocking);
    // Wait for thread to start
    assertTrue(barrier.await(1, TimeUnit.SECONDS), String.valueOf(failure.get()));

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

    serverThread.shutdown();
    assertTrue(serverThread.awaitTermination(1, TimeUnit.SECONDS), String.valueOf(failure.get()));

    // check results
    assertNull(failure.get());
    assertEquals(1, receiveCount.get());
  }

  private ExecutorService startServer(boolean openThrowOnBadPacket, boolean isBlocking) {
    ExecutorService ex = Executors.newSingleThreadExecutor();
    ex.execute(
        () -> {
          try {
            try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
              channel.configureBlocking(isBlocking);
              if (openThrowOnBadPacket) {
                channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
              }
              channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));
              localAddress.set(channel.getLocalAddress());
              barrier.countDown();

              ByteBuffer response = ByteBuffer.allocate(500);
              // repeat until we get no exception
              boolean failed;
              do {
                failed = false;
                try {
                  if (isBlocking) {
                    assertNotNull(channel.receive(response));
                  } else {
                    while (channel.receive(response) == null) {
                      Thread.sleep(10);
                    }
                  }
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
          } catch (Exception e) {
            failure.set(e);
          }
        });
    return ex;
  }
}
