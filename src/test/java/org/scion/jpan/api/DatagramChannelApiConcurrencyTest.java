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
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionSocketAddress;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.ManagedThreadNews;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockDaemon;

class DatagramChannelApiConcurrencyTest {

  private static final int dummyPort = 32000;
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

  /** Test 2x receive() and 1x send(). */
  @Test
  void concurrentReceive() throws IOException {
    concurrentReceive(this::receive, this::receive, this::send, false);
  }

  /** Test 2x read() and 1x write(). */
  @Test
  void concurrentRead() throws IOException {
    concurrentReceive(this::read, this::read, this::write, true);
  }

  private interface Reader {
    void run(ScionDatagramChannel channel, AtomicInteger receiveCount, ManagedThreadNews cb);
  }

  private interface Writer {
    void run(ScionDatagramChannel channel, CountDownLatch receiveCount, ManagedThreadNews cb);
  }

  /** Test 2x receive() and 1x send(). */
  private void concurrentReceive(Reader c1, Reader c2, Writer w1, boolean connect)
      throws IOException {
    AtomicInteger receiveCount = new AtomicInteger();
    CountDownLatch senderLatch = new CountDownLatch(1);
    ByteBuffer buffer = ByteBuffer.allocate(100);
    buffer.put("Hello scion!".getBytes());
    buffer.flip();
    try (ScionDatagramChannel server = ScionDatagramChannel.open()) {
      server.bind(dummyAddress);
      server.configureBlocking(false);

      try (ScionDatagramChannel client = ScionDatagramChannel.open()) {
        client.configureBlocking(true);
        if (connect) {
          client.connect(dummyAddress);
        }
        ManagedThread rcv1 = ManagedThread.newBuilder().build();
        ManagedThread rcv2 = ManagedThread.newBuilder().build();
        ManagedThread sender = ManagedThread.newBuilder().build();
        try {
          rcv1.submit(mtn -> c1.run(client, receiveCount, mtn));
          rcv2.submit(mtn -> c2.run(client, receiveCount, mtn));

          // A little race here is difficult to avoid ....
          assertEquals(0, receiveCount.get());

          sender.submit(mtn -> w1.run(client, senderLatch, mtn));

          // Check that sending in parallel works
          senderLatch.await(1, TimeUnit.SECONDS);
          assertEquals(0, receiveCount.get());

          // Check that these work
          assertTrue(client.isBlocking());
          assertEquals(connect, client.isConnected());
          if (connect) {
            assertEquals(dummyAddress, client.getRemoteAddress());
          } else {
            assertNull(client.getConnectionPath());
            assertNull(client.getRemoteAddress());
          }

          // check that receive is responsive
          ScionSocketAddress responseAddress = server.receive(buffer);
          server.send(buffer, responseAddress);
          // wait
          synchronized (receiveCount) {
            receiveCount.wait(1000);
          }
          assertEquals(1, receiveCount.get());

          // send again to trigger 2nd receiver
          buffer.flip();
          server.send(buffer, responseAddress);
          // wait
          synchronized (receiveCount) {
            receiveCount.wait(1000);
          }
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        } finally {
          rcv1.join();
          rcv2.join();
          sender.join();
        }
        assertEquals(2, receiveCount.get());
      }
    }
  }

  private void receive(
      ScionDatagramChannel channel, AtomicInteger receiveCount, ManagedThreadNews cb) {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    try {
      cb.reportStarted();
      channel.receive(buffer);
      buffer.flip();
      assertEquals(12, buffer.remaining());
      receiveCount.incrementAndGet();
      synchronized (receiveCount) {
        receiveCount.notifyAll();
      }
    } catch (IOException e) {
      cb.reportException(e);
      throw new RuntimeException(e);
    }
  }

  private void send(ScionDatagramChannel channel, CountDownLatch latch, ManagedThreadNews cb) {
    ByteBuffer buffer = ByteBuffer.wrap("Hello scion!".getBytes());
    try {
      buffer.flip();
      cb.reportStarted();
      channel.send(buffer, dummyAddress);
      latch.countDown();
    } catch (IOException e) {
      cb.reportException(e);
      throw new RuntimeException(e);
    }
  }

  private void read(
      ScionDatagramChannel channel, AtomicInteger receiveCount, ManagedThreadNews cb) {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    try {
      cb.reportStarted();
      channel.read(buffer);
      buffer.flip();
      assertEquals(12, buffer.remaining());
      receiveCount.incrementAndGet();
      synchronized (receiveCount) {
        receiveCount.notifyAll();
      }
    } catch (IOException e) {
      cb.reportException(e);
      throw new RuntimeException(e);
    }
  }

  private void write(ScionDatagramChannel channel, CountDownLatch latch, ManagedThreadNews cb) {
    ByteBuffer buffer = ByteBuffer.wrap("Hello scion!".getBytes());
    try {
      buffer.flip();
      cb.reportStarted();
      channel.write(buffer);
      latch.countDown();
    } catch (IOException e) {
      cb.reportException(e);
      throw new RuntimeException(e);
    }
  }
}
