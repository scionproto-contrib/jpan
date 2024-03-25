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
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.DatagramChannel;
import org.scion.Path;
import org.scion.RequestPath;
import org.scion.ScionService;
import org.scion.testutil.PingPongHelper;

/** Test read()/write() operations on DatagramChannel connected with a path. */
class DatagramChannelMultiWriteConnectedPathTest {

  private static final String MSG = "Hello world!";

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void test() {
    PingPongHelper.Server serverFn = this::server;
    PingPongHelper.Client clientFn = this::client;
    PingPongHelper pph = new PingPongHelper(1, 10, 10);
    pph.runPingPong(serverFn, clientFn);
  }

  private void client(DatagramChannel channel, RequestPath serverAddress, int id)
      throws IOException {
    String message = MSG + "-" + id;
    ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());
    channel.disconnect();
    channel.connect(serverAddress);
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

  private void server(DatagramChannel channel) throws IOException {
    ByteBuffer request = ByteBuffer.allocate(512);
    // System.out.println("SERVER: --- USER - Waiting for packet --------------------- " + i);
    Path path = channel.receive(request);

    request.flip();
    String msg = Charset.defaultCharset().decode(request).toString();
    assertTrue(msg.startsWith(MSG), msg);
    assertTrue(MSG.length() + 3 >= msg.length());

    // System.out.println("SERVER: --- USER - Sending packet ---------------------- " + i);
    request.flip();
    channel.send(request, path);
  }
}
