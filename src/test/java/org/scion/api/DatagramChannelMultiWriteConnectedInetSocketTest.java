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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.DatagramChannel;
import org.scion.Path;
import org.scion.ScionService;
import org.scion.testutil.PingPongHelper;

/** Test read()/write() operations on DatagramChannel connected with an InetSocketAddress. */
class DatagramChannelMultiWriteConnectedInetSocketTest {

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void test() {
    PingPongHelper.Server serverFn = PingPongHelper::defaultServer;
    PingPongHelper.Client clientFn = this::client;
    PingPongHelper pph = new PingPongHelper(1, 10, 10);
    pph.runPingPong(serverFn, clientFn);
  }

  private void client(DatagramChannel channel, Path serverAddress, int id) throws IOException {
    String message = PingPongHelper.MSG + "-" + id;
    ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());
    channel.disconnect();
    // Test send() with InetAddress
    InetAddress inetAddress = InetAddress.getByAddress(serverAddress.getDestinationAddress());
    InetSocketAddress inetServerSocketAddress =
        new InetSocketAddress(inetAddress, serverAddress.getDestinationPort());
    channel.connect(inetServerSocketAddress);
    assertTrue(channel.isConnected());
    channel.write(sendBuf);

    // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
    ByteBuffer response = ByteBuffer.allocate(512);
    int len = channel.read(response);
    assertEquals(14, len);

    response.flip();
    String pong = Charset.defaultCharset().decode(response).toString();
    assertEquals(message, pong);
  }
}
