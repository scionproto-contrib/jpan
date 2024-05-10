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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionService;
import org.scion.jpan.testutil.PingPongChannelHelper;

/** Test receive()/send(InetAddress) operations on DatagramChannel. */
class DatagramChannelMultiSendInetAddrTest {

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void test() {
    PingPongChannelHelper.Server serverFn = PingPongChannelHelper::defaultServer;
    PingPongChannelHelper.Client clientFn = this::client;
    PingPongChannelHelper pph = new PingPongChannelHelper(1, 20, 50, false);
    pph.runPingPong(serverFn, clientFn);
  }

  private void client(ScionDatagramChannel channel, Path serverAddress, int id) throws IOException {
    String message = PingPongChannelHelper.MSG + "-" + id;
    ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());
    // Test send() with InetAddress
    InetAddress inetServerAddress = serverAddress.getRemoteAddress();
    InetSocketAddress inetServerSocketAddress =
        new InetSocketAddress(inetServerAddress, serverAddress.getRemotePort());
    channel.send(sendBuf, inetServerSocketAddress);

    // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
    ByteBuffer response = ByteBuffer.allocate(512);
    Path address = channel.receive(response);
    assertNotNull(address);
    assertEquals(serverAddress.getRemoteAddress(), address.getRemoteAddress());
    assertEquals(serverAddress.getRemotePort(), address.getRemotePort());

    response.flip();
    String pong = Charset.defaultCharset().decode(response).toString();
    assertEquals(message, pong);
  }
}
