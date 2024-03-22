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
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.socket.DatagramSocket;
import org.scion.testutil.MockDNS;
import org.scion.testutil.MockDaemon;

class DatagramSocketApiConcurrencyTest {

  private static final int dummyPort = 44444;
  private static final InetAddress dummyIPv4;
  private static final InetSocketAddress dummyAddress;

  static {
    try {
      dummyIPv4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
      dummyAddress = new InetSocketAddress(dummyIPv4, dummyPort);
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
  @Test
  void concurrentReceive() throws IOException {
    concurrentReceive(this::receive, this::receive, this::send, false);
  }

  private interface Reader {
    void run(DatagramSocket socket, AtomicInteger receiveCount);
  }

  private interface Writer {
    void run(DatagramSocket socket, CountDownLatch receiveCount);
  }

  /**
   * Test 2x receive() and 1x send().
   */
  private void concurrentReceive(Reader c1, Reader c2, Writer w1, boolean connect) throws IOException {
    AtomicInteger receiveCount = new AtomicInteger();
    CountDownLatch senderLatch = new CountDownLatch(1);
    ByteBuffer buffer = ByteBuffer.allocate(100);
    buffer.put("Hello scion!".getBytes());
    buffer.flip();
    try (DatagramSocket server = new DatagramSocket(dummyAddress)) {
      try (DatagramSocket client = new DatagramSocket()) {
        if (connect) {
          client.connect(dummyAddress);
        }
        Thread r1 = new Thread(() -> c1.run(client, receiveCount));
        Thread r2 = new Thread(() -> c2.run(client, receiveCount));
        Thread s1 = new Thread(() -> w1.run(client, senderLatch));
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
          assertEquals(connect, client.isConnected());
          if (connect) {
            assertEquals(dummyAddress, client.getRemoteSocketAddress());
          } else {
            assertNull(client.getConnectionPath());
            assertNull(client.getRemoteSocketAddress());
          }

          // check that receive is responsive
          byte[] bytes = "Hello scion!".getBytes();
          DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
          server.receive(packet);
          server.send(packet);
          // wait
          synchronized (receiveCount) {
            receiveCount.wait(1000);
          }
          assertEquals(1, receiveCount.get());

          // send again to trigger 2nd receiver
          buffer.flip();
          server.send(packet);
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

  private void receive(DatagramSocket socket, AtomicInteger receiveCount) {
    DatagramPacket packet = new DatagramPacket(new byte[100], 100);
    try {
      socket.receive(packet);
      assertEquals(12, packet.getLength());
      receiveCount.incrementAndGet();
      synchronized (receiveCount) {
        receiveCount.notifyAll();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void send(DatagramSocket socket, CountDownLatch latch) {
    byte[] bytes = "Hello scion!".getBytes();
    DatagramPacket packet = new DatagramPacket(bytes, bytes.length, dummyAddress);
    try {
      socket.send(packet);
      latch.countDown();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
