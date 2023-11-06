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
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.DatagramChannel;
import org.scion.ScionSocketAddress;
import org.scion.testutil.MockNetwork;

class DatagramChannelMultiPingPongTest {

  private static final int N_REPEAT = 50;
  private static final int N_CLIENTS = 20;
  private static final String MSG = "Hello world!";
  private static final CountDownLatch BARRIER = new CountDownLatch(N_CLIENTS + 1);

  private final AtomicInteger nClient = new AtomicInteger();
  private final AtomicInteger nServer = new AtomicInteger();
  private final ConcurrentLinkedQueue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

  @BeforeEach
  public void beforeEach() {
    MockNetwork.getAndResetForwardCount();
  }

  @Test
  void testPingPong() throws InterruptedException {
    MockNetwork.startTiny();

    InetSocketAddress serverAddress = MockNetwork.getTinyServerAddress();
    Thread server = new Thread(() -> server(serverAddress), "Server-thread");
    server.start();
    ScionSocketAddress scionAddress = ScionSocketAddress.create(serverAddress);
    Thread[] clients = new Thread[N_CLIENTS];
    for (int i = 0; i < clients.length; i++) {
      int id = i;
      clients[i] = new Thread(() -> client(scionAddress, id), "Client-thread-" + i);
      clients[i].start();
    }

    // This enables shutdown in case of an error.
    // Wait for all threads to finish.
    if (!BARRIER.await(10, TimeUnit.SECONDS)) {
      for (Thread client : clients) {
        client.interrupt();
      }
      server.interrupt();
      if (!BARRIER.await(1, TimeUnit.SECONDS)) {
        checkExceptions();
        fail();
      }
    }

    MockNetwork.stopTiny();

    checkExceptions();
    assertEquals(N_REPEAT * N_CLIENTS * 2, MockNetwork.getAndResetForwardCount());
    assertEquals(N_REPEAT * N_CLIENTS, nClient.get());
    assertEquals(N_REPEAT * N_CLIENTS, nServer.get());
  }

  private void checkExceptions() {
    for (Throwable e : exceptions) {
      e.printStackTrace();
    }
    assertEquals(0, exceptions.size());
    exceptions.clear();
  }

  private void client(ScionSocketAddress serverAddress, int id) {
    String message = MSG + "-" + id;
    try (DatagramChannel channel = DatagramChannel.open()) {
      channel.configureBlocking(true);

      for (int i = 0; i < N_REPEAT; i++) {
        ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());
        channel.send(sendBuf, serverAddress);

        // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
        ByteBuffer response = ByteBuffer.allocate(512);
        ScionSocketAddress addr = channel.receive(response);
        assertNotNull(addr);
        assertEquals(serverAddress.getAddress(), addr.getAddress());
        assertEquals(serverAddress.getPort(), addr.getPort());

        response.flip();
        String pong = Charset.defaultCharset().decode(response).toString();
        assertEquals(message, pong);

        nClient.incrementAndGet();
      }
    } catch (IOException e) {
      System.out.println("CLIENT: I/O error: " + e.getMessage());
      exceptions.add(e);
      throw new RuntimeException(e);
    } catch (Exception e) {
      exceptions.add(e);
    } finally {
      BARRIER.countDown();
    }
  }

  private void server(InetSocketAddress localAddress) {
    try (DatagramChannel channel = DatagramChannel.open().bind(localAddress)) {
      channel.configureBlocking(true);
      assertEquals(localAddress, channel.getLocalAddress());
      service(channel);
    } catch (IOException e) {
      System.out.println("SERVER: I/O error: " + e.getMessage());
      exceptions.add(e);
    } catch (Exception e) {
      exceptions.add(e);
    } finally {
      BARRIER.countDown();
    }
  }

  private void service(DatagramChannel channel) throws IOException {
    for (int i = 0; i < N_REPEAT * N_CLIENTS; i++) {
      ByteBuffer request = ByteBuffer.allocate(512);
      // System.out.println("SERVER: --- USER - Waiting for packet --------------------- " + i);
      SocketAddress addr = channel.receive(request);

      request.flip();
      String msg = Charset.defaultCharset().decode(request).toString();
      assertTrue(msg.startsWith(MSG));
      assertTrue(MSG.length() + 3 >= msg.length());

      // System.out.println("SERVER: --- USER - Sending packet ---------------------- " + i);
      request.flip();
      channel.send(request, addr);
      nServer.incrementAndGet();
    }
  }
}
