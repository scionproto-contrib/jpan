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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scion.testutil.MockDaemon;
import org.scion.testutil.MockNetwork;

public class PingPongSocketTest {

  private static final InetSocketAddress MOCK_BR1_ADDRESS =
          new InetSocketAddress("127.0.0.1", 30333);

  private static final int N_REPEAT = 5;
  private static final String MSG = "Hello world!";

  private int nClient = 0;
  private int nServer = 0;

  @Deprecated // TODO remove
  private static final int CLIENT_PORT = 33345;

  @Test
  public void testPingPong() throws IOException, InterruptedException {
    MockDaemon daemon = MockDaemon.createForBorderRouter(MOCK_BR1_ADDRESS).start();

    InetSocketAddress br1Dst = new InetSocketAddress("127.0.0.1", 12200);
    InetSocketAddress br2Dst = new InetSocketAddress("127.0.0.1", CLIENT_PORT);
    InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", 22233);

    MockNetwork.startTiny(MOCK_BR1_ADDRESS.getPort(), 30444, serverAddress, br2Dst);

    Thread server = new Thread(() -> server(serverAddress), "Server-thread");
    server.start();
    Thread client = new Thread(() -> client(serverAddress), "Client-thread");
    client.start();

    client.join();
    server.join();

    MockNetwork.stopTiny();
    daemon.close();

    assertEquals(N_REPEAT, nClient);
    assertEquals(N_REPEAT, nServer);
  }

  private void client(SocketAddress serverAddress) {
    try {
      client2(serverAddress);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void client2(SocketAddress serverAddress) throws IOException {
    try {
      //ScionDatagramSocket socket = new ScionDatagramSocket(null);
      ScionDatagramSocket socket  = new ScionDatagramSocket(CLIENT_PORT);
      socket.setDstIsdAs("1-ff00:0:112");

      for (int i = 0; i < N_REPEAT; i++) {
          byte[] sendBuf = MSG.getBytes();
        DatagramPacket request =
            new DatagramPacket(sendBuf, sendBuf.length, serverAddress);
        socket.send(request);
        // System.out.println("CLIENT: Sent!");

        // System.out.println("CLIENT: Receiving ... (" + socket.getLocalSocketAddress() + ")");
        byte[] buffer = new byte[512];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        socket.receive(response);

        String pong = new String(buffer, 0, response.getLength());
        assertEquals(MSG, pong);

        // System.out.println(pong);

        nClient++;
      }

    } catch (SocketTimeoutException e) {
      System.out.println("CLIENT: Timeout error: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }

  public void server(InetSocketAddress localAddress) {
    try {
      ScionDatagramSocket socket = new ScionDatagramSocket(localAddress);
      service(socket);
    } catch (SocketException ex) {
      System.out.println("CLIENT: Socket error: " + ex.getMessage());
    } catch (IOException ex) {
      System.out.println("CLIENT: I/O error: " + ex.getMessage());
    }
  }

  private void service(ScionDatagramSocket socket) throws IOException {
    for (int i = 0; i < N_REPEAT; i++) {
      DatagramPacket request = new DatagramPacket(new byte[65536], 65536);
      // System.out.println("SERVER: --- USER - Waiting for packet ---------------------- " + socket.getLocalSocketAddress());
      socket.receive(request);

      String msg = new String(request.getData(), request.getOffset(), request.getLength());
      // System.out.println("SERVER: Received (from " + request.getSocketAddress() + "): " + msg);
      assertEquals(MSG, msg);

      byte[] buffer = msg.getBytes();
      InetAddress clientAddress = request.getAddress();
      int clientPort = request.getPort();
      assertEquals(clientPort, CLIENT_PORT);

      // System.out.println("SERVER: --- USER - Sending packet ----------------------");
      // TODO fix this, we should not specify the daemon port here!!
      DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress, 23232);
      socket.send(response);
      nServer++;
    }
  }
}
