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
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.testutil.MockDNS;
import org.scion.testutil.MockDaemon;

class DatagramChannelApiConcurrencyTest {

  private static final int dummyPort = 44444;
  private static final InetAddress dummyIPv4;
  private static final InetSocketAddress dummyAddress;
  private static final DatagramPacket dummyPacket;

  static {
    try {
      dummyIPv4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
      dummyAddress = new InetSocketAddress(dummyIPv4, dummyPort);
      dummyPacket = new DatagramPacket(new byte[100], 100, dummyAddress);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeEach
  public void beforeEach() throws IOException {
    MockDaemon.createAndStartDefault();
  }

  @AfterEach
  public void afterEach() throws IOException {
    MockDaemon.closeDefault();
    MockDNS.clear();
  }

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  /**
   * Test 2x receive() and 1x send().
   */
  @Disabled
  @Test
  void concurrentReceive() throws IOException {
    AtomicInteger receiveCount = new AtomicInteger();
    CountDownLatch senderLatch = new CountDownLatch(1);
    ByteBuffer buffer = ByteBuffer.allocate(100);
    buffer.put("Hello scion!".getBytes());
    buffer.flip();
    try (DatagramChannel server = DatagramChannel.open()) {
      server.bind(dummyAddress);
      server.configureBlocking(false);

      try (DatagramChannel client = DatagramChannel.open()) {
        client.configureBlocking(true);
        Thread r1 = new Thread(() -> receive(client, receiveCount));
        Thread r2 = new Thread(() -> receive(client, receiveCount));
        Thread s1 = new Thread(() -> send(client, senderLatch));
        try {
          r1.start();
          r2.start();

          // A little race here is difficult to avoid ....
          assertEquals(0, receiveCount.get());

          s1.start();

          // Check that sending in parallel works
          senderLatch.await(1, TimeUnit.SECONDS);
          assertEquals(0, receiveCount.get());

          // Check that these work
          assertTrue(client.isBlocking());
          assertFalse(client.isConnected());
          assertNull(client.getConnectionPath());
          assertNull(client.getRemoteAddress());

          // check that receive is responsive
          ResponsePath path = server.receive(buffer);
          server.send(buffer, path);
          // wait
          synchronized (receiveCount) {
            receiveCount.wait(1000);
          }
          assertEquals(1, receiveCount.get());

          // send again to trigger 2nd receiver
          buffer.flip();
          server.send(buffer, path);
          // wait
          synchronized (receiveCount) {
            receiveCount.wait(1000);
          }
          assertEquals(2, receiveCount.get());
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        } finally {
          r1.interrupt();
          r2.interrupt();
          s1.interrupt();
        }
      }
    }
  }

  private void receive(DatagramChannel channel, AtomicInteger receiveCount) {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    try {
      channel.receive(buffer);
      buffer.flip();
      assertEquals(12, buffer.remaining());
      receiveCount.incrementAndGet();
      synchronized (receiveCount) {
        receiveCount.notifyAll();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void send(DatagramChannel channel, CountDownLatch latch) {
    ByteBuffer buffer = ByteBuffer.wrap("Hello scion!".getBytes());
    try {
      buffer.flip();
      channel.send(buffer, dummyAddress);
      latch.countDown();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Test 2x read() and 1x write().
   */
  @Disabled
  @Test
  void concurrentRead() throws IOException {
    AtomicInteger receiveCount = new AtomicInteger();
    AtomicInteger sendCount = new AtomicInteger();
    CountDownLatch senderLatch = new CountDownLatch(1);
    ByteBuffer buffer = ByteBuffer.allocate(100);
    buffer.put("Hello scion!".getBytes());
    buffer.flip();
    try (DatagramChannel server = DatagramChannel.open()) {
      server.bind(dummyAddress);
      server.configureBlocking(false);

      try (DatagramChannel client = DatagramChannel.open()) {
        client.connect(dummyAddress);
        client.configureBlocking(true);
        Thread r1 = new Thread(() -> read(client, receiveCount));
        Thread r2 = new Thread(() -> read(client, receiveCount));
        Thread s1 = new Thread(() -> write(client, sendCount, senderLatch));
        try {
          r1.start();
          r2.start();

          // A little race here is difficult to avoid ....
          assertEquals(0, receiveCount.get());

          s1.start();

          // Check that sending in parallel works
          assertTrue(senderLatch.await(1, TimeUnit.SECONDS));
          assertEquals(1, sendCount.get());
          assertEquals(0, receiveCount.get());

          // Check that these work
          assertTrue(client.isBlocking());
          assertFalse(client.isConnected());
          assertNull(client.getConnectionPath());
          assertNull(client.getRemoteAddress());

          // check that receive is responsive
          ResponsePath path = server.receive(buffer);
          server.send(buffer, path);
          // wait
          synchronized (receiveCount) {
            receiveCount.wait(1000);
          }
          assertEquals(1, receiveCount.get());

          // send again to trigger 2nd receiver
          buffer.flip();
          server.send(buffer, path);
          // wait
          synchronized (receiveCount) {
            receiveCount.wait(1000);
          }
          assertEquals(2, receiveCount.get());
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        } finally {
          r1.interrupt();
          r2.interrupt();
          s1.interrupt();
        }
      }
    }
  }

  private void read(DatagramChannel channel, AtomicInteger receiveCount) {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    try {
      channel.read(buffer);
      buffer.flip();
      assertEquals(12, buffer.remaining());
      receiveCount.incrementAndGet();
      synchronized (receiveCount) {
        receiveCount.notifyAll();
      }
    } catch (IOException e) {
      e.printStackTrace(); // TODO handle better
      throw new RuntimeException(e);
    }
  }

  private void write(DatagramChannel channel, AtomicInteger sendCount, CountDownLatch latch) {
    ByteBuffer buffer = ByteBuffer.wrap("Hello scion!".getBytes());
    try {
      buffer.flip();
      channel.write(buffer);
      latch.countDown();
      sendCount.incrementAndGet();
    } catch (IOException e) {
      e.printStackTrace(); // TODO handle better
      throw new RuntimeException(e);
    }
  }
}
