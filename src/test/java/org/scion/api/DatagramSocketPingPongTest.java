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
import java.util.ArrayList;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.DatagramSocket;
import org.scion.testutil.MockNetwork;

@Disabled // TODO this does not work anymore.
class DatagramSocketPingPongTest {

  private static final int N_REPEAT = 5;
  private static final String MSG = "Hello world!";
  private static final Object BARRIER = new Object();

  private int nClient = 0;
  private int nServer = 0;
  private final ArrayList<Throwable> exceptions = new ArrayList<>();

  @Test
  void testPingPong() throws InterruptedException {
    MockNetwork.startTiny();

    InetSocketAddress serverAddress = MockNetwork.getTinyServerAddress();
    Thread server = new Thread(() -> server(serverAddress), "Server-thread");
    server.start();
    Thread client = new Thread(() -> client(serverAddress), "Client-thread");
    client.start();

    // This enables shutdown in case of an error
    synchronized (BARRIER) {
      BARRIER.wait(60_000);
    }
    // Wait some more to allow normal shutdown
    synchronized (BARRIER) {
      BARRIER.wait(100);
    }
    client.interrupt();
    server.interrupt();

    client.join();
    server.join();

    MockNetwork.stopTiny();

    checkExceptions();

    assertEquals(N_REPEAT, nClient);
    assertEquals(N_REPEAT, nServer);
  }

  private void checkExceptions() {
    for (Throwable e : exceptions) {
      e.printStackTrace();
    }
    assertEquals(0, exceptions.size());
    exceptions.clear();
  }

  private void client(SocketAddress serverAddress) {
    try (org.scion.DatagramSocket socket = new org.scion.DatagramSocket(null)) {
      for (int i = 0; i < N_REPEAT; i++) {
        byte[] sendBuf = MSG.getBytes();
        DatagramPacket request = new DatagramPacket(sendBuf, sendBuf.length, serverAddress);
        socket.send(request);

        // System.out.println("CLIENT: Receiving ... (" + socket.getLocalSocketAddress() + ")");
        byte[] buffer = new byte[512];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        socket.receive(response);

        String pong = new String(buffer, 0, response.getLength());
        assertEquals(MSG, pong);
        nClient++;
      }
    } catch (IOException e) {
      System.out.println("CLIENT: I/O error: " + e.getMessage());
      exceptions.add(e);
      throw new RuntimeException(e);
    } catch (Exception e) {
      exceptions.add(e);
    } finally {
      synchronized (BARRIER) {
        BARRIER.notifyAll();
      }
    }
  }

  private void server(InetSocketAddress localAddress) {
    try (org.scion.DatagramSocket socket = new org.scion.DatagramSocket(localAddress)) {
      service(socket);
    } catch (IOException e) {
      System.out.println("SERVER: I/O error: " + e.getMessage());
      exceptions.add(e);
    } catch (Exception e) {
      exceptions.add(e);
    } finally {
      synchronized (BARRIER) {
        BARRIER.notifyAll();
      }
    }
  }

  private void service(DatagramSocket socket) throws IOException {
    for (int i = 0; i < N_REPEAT; i++) {
      DatagramPacket request = new DatagramPacket(new byte[65536], 65536);
      // System.out.println("SERVER: --- USER - Waiting for packet ---------------------- " +
      socket.receive(request);

      String msg = new String(request.getData(), request.getOffset(), request.getLength());
      assertEquals(MSG, msg);

      byte[] buffer = msg.getBytes();
      InetAddress clientAddress = request.getAddress();
      int clientPort = request.getPort();

      // System.out.println("SERVER: --- USER - Sending packet ----------------------");
      // TODO test that the port is NOT ignored.
      DatagramPacket response =
          new DatagramPacket(buffer, buffer.length, clientAddress, clientPort);
      socket.send(response);
      nServer++;
    }
  }
}
