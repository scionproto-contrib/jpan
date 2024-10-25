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
import static org.scion.jpan.testutil.PingPongHelperBase.MSG;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.ScionDatagramSocket;
import org.scion.jpan.ScionService;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.PingPongSocketHelper;

/** This test uses two threads that concurrently send() and receive() on the same socket. */
class DatagramSocketConcurrentPingPongTest {

  private static class Entry {
    String msg;
    InetSocketAddress address;

    Entry(String msg, InetSocketAddress address) {
      this.msg = msg;
      this.address = address;
    }
  }

  private final LinkedBlockingDeque<Entry> queue = new LinkedBlockingDeque<>();

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void test() throws IOException {
    PingPongSocketHelper.Server receiverFn = this::receiver;
    PingPongSocketHelper.Server senderFn = this::sender;
    PingPongSocketHelper.Client clientFn = this::client;
    PingPongSocketHelper pph =
        PingPongSocketHelper.newBuilder(2, 5, 100).resetCounters(false).build();
    pph.runPingPongSharedServerSocket(receiverFn, senderFn, clientFn);
    assertEquals(2 * 5 * 100, MockNetwork.getAndResetForwardCount());
  }

  private void client(ScionDatagramSocket socket, Path requestPath, int id) throws IOException {
    byte[] sendBuf = MSG.getBytes();
    InetAddress addr = requestPath.getRemoteAddress();
    int port = requestPath.getRemotePort();
    DatagramPacket request = new DatagramPacket(sendBuf, sendBuf.length, addr, port);
    socket.send(request);
    // System.out.println("CLIENT: Sent ... (" + request.getSocketAddress() + ")");

    // System.out.println("CLIENT: Receiving ... (" + socket.getLocalSocketAddress() + ")");
    byte[] buffer = new byte[512];
    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
    socket.receive(response);

    String pong = new String(buffer, 0, response.getLength());
    assertEquals(MSG, pong);
  }

  private void receiver(ScionDatagramSocket socket) throws IOException {
    DatagramPacket request = new DatagramPacket(new byte[200], 200);
    // System.out.println("SERVER: --- receiver - waiting ----- " + socket.getLocalSocketAddress());
    socket.receive(request);

    String msg = new String(request.getData(), request.getOffset(), request.getLength());
    assertEquals(MSG, msg);

    queue.offer(new Entry(msg, (InetSocketAddress) request.getSocketAddress()));
    // System.out.println("SERVER: --- receiver - added -------- " + msg);
  }

  private void sender(ScionDatagramSocket socket) throws IOException {
    // System.out.println("SERVER: --- sender - waiting ----- " + socket.getLocalSocketAddress());
    Entry e;
    try {
      e = queue.poll(10, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      throw new RuntimeException(ex);
    }

    byte[] buffer = e.msg.getBytes();
    InetAddress clientAddress = e.address.getAddress();
    int clientPort = e.address.getPort();

    // System.out.println("SERVER: --- sender - sending ---- " + e.address);
    DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress, clientPort);
    socket.send(response);
  }
}
