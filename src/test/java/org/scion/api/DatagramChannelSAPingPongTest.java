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
import org.junit.jupiter.api.Test;
import org.scion.DatagramChannel;
import org.scion.ScionSocketAddress;
import org.scion.testutil.MockNetwork;

class DatagramChannelSAPingPongTest {

  private static final int N_REPEAT = 5;
  private static final String MSG = "Hello world!";

  private int nClient = 0;
  private int nServer = 0;

  @Test
  void testPingPong() throws InterruptedException {
    MockNetwork.startTiny();

    InetSocketAddress serverAddress = MockNetwork.getTinyServerAddress();
    Thread server = new Thread(() -> server(serverAddress), "Server-thread");
    server.start();
    ScionSocketAddress scionAddress = ScionSocketAddress.create(serverAddress);
    Thread client = new Thread(() -> client(scionAddress), "Client-thread");
    client.start();

    client.join();
    server.join();

    MockNetwork.stopTiny();

    assertEquals(N_REPEAT, nClient);
    assertEquals(N_REPEAT, nServer);
  }

  private void client(SocketAddress serverAddress) {
    try (DatagramChannel channel = DatagramChannel.open().configureBlocking(true)) {

      for (int i = 0; i < N_REPEAT; i++) {
        ByteBuffer sendBuf = ByteBuffer.wrap(MSG.getBytes());
        channel.send(sendBuf, serverAddress);

        // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
        ByteBuffer response = ByteBuffer.allocate(512);
        SocketAddress addr;
        do {
          addr = channel.receive(response);
        } while (addr == null);

        response.flip();
        String pong = Charset.defaultCharset().decode(response).toString();
        assertEquals(MSG, pong);

        nClient++;
      }
    } catch (IOException e) {
      System.out.println("CLIENT: I/O error: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }

  private void server(InetSocketAddress localAddress) {
    try (DatagramChannel channel = DatagramChannel.open().bind(localAddress)) {
      channel.configureBlocking(true);
      assertEquals(localAddress, channel.getLocalAddress());
      service(channel);
    } catch (IOException ex) {
      System.out.println("SERVER: I/O error: " + ex.getMessage());
    }
  }

  private void service(DatagramChannel channel) throws IOException {
    for (int i = 0; i < N_REPEAT; i++) {
      ByteBuffer request = ByteBuffer.allocate(512);
      // System.out.println("SERVER: --- USER - Waiting for packet --------------------- " +
      SocketAddress addr = channel.receive(request);

      request.flip();
      String msg = Charset.defaultCharset().decode(request).toString();
      assertEquals(MSG, msg);

      // System.out.println("SERVER: --- USER - Sending packet ----------------------");
      request.flip();
      channel.send(request, addr);
      nServer++;
    }
  }
}
