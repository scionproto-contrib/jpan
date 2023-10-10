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

package org.scion;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import org.junit.jupiter.api.Test;
import org.scion.testutil.MockNetwork;

public class DatagramSocketPingPongTest {

  private static final int N_REPEAT = 5;
  private static final String MSG = "Hello world!";

  private int nClient = 0;
  private int nServer = 0;

  @Test
  public void testPingPong() throws InterruptedException {
    MockNetwork.startTiny();

    InetSocketAddress serverAddress = MockNetwork.getTinyServerAddress();
    Thread server = new Thread(() -> server(serverAddress), "Server-thread");
    server.start();
    Thread client = new Thread(() -> client(serverAddress), "Client-thread");
    client.start();

    client.join();
    server.join();

    MockNetwork.stopTiny();

    assertEquals(N_REPEAT, nClient);
    assertEquals(N_REPEAT, nServer);
  }

  private void client(SocketAddress serverAddress) {
    try (DatagramSocket socket = new DatagramSocket(null)) {
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
      throw new RuntimeException(e);
    }
  }

  public void server(InetSocketAddress localAddress) {
    try (DatagramSocket socket = new DatagramSocket(localAddress)) {
      service(socket);
    } catch (IOException ex) {
      System.out.println("SERVER: I/O error: " + ex.getMessage());
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
