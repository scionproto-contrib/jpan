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
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.PingPongChannelHelper;

class DatagramChannelPingPongTest {

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void testWithServerDefault() {
    PingPongChannelHelper.Server serverFn = (socket) -> server(socket, false);
    PingPongChannelHelper.Client clientFn = this::client;
    PingPongChannelHelper pph = PingPongChannelHelper.newBuilder(1, 10, 10).build();
    pph.runPingPong(serverFn, clientFn);
    assertEquals(2 * 10 * 10, MockNetwork.getAndResetForwardCount());
  }

  @Test
  void testWithServerNoService() {
    PingPongChannelHelper.Server serverFn = (socket) -> server(socket, true);
    PingPongChannelHelper.Client clientFn = this::client;
    PingPongChannelHelper pph =
        PingPongChannelHelper.newBuilder(1, 10, 10).serverService(null).build();
    pph.runPingPong(serverFn, clientFn);
    assertEquals(2 * 10 * 10, MockNetwork.getAndResetForwardCount());
  }

  private void client(ScionDatagramChannel channel, Path requestPath, int id) throws IOException {
    ByteBuffer sendBuf = ByteBuffer.wrap(MSG.getBytes());
    channel.send(sendBuf, requestPath);

    // System.out.println("CLIENT: Receiving ... (" + socket.getLocalSocketAddress() + ")");
    ByteBuffer response = ByteBuffer.allocate(512);
    channel.receive(response);

    response.flip();
    String pong = new String(response.array(), 0, response.limit());
    assertEquals(MSG, pong);
  }

  private void server(ScionDatagramChannel channel, boolean isServiceNull) throws IOException {
    if (isServiceNull) {
      assertNull(channel.getService());
    } else {
      assertNotNull(channel.getService());
    }
    ByteBuffer buffer = ByteBuffer.allocate(200);
    // System.out.println("SERVER: --- USER - Waiting for packet ---------------------- ");
    ScionSocketAddress path = channel.receive(buffer);

    buffer.flip();
    String msg = new String(buffer.array(), 0, buffer.remaining());
    assertEquals(MSG, msg);

    // System.out.println(
    //    "SERVER: --- USER - Sending packet -------- " + clientAddress + " : " + clientPort);
    channel.send(buffer, path);
  }
}
