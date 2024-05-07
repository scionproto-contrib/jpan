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
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.DatagramChannel;
import org.scion.jpan.Path;
import org.scion.jpan.ScionService;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.PingPongChannelHelper;

/** Test write/send of multiple packets at once. */
class DatagramChannelStreamTest {

  private static final int N_BULK = 10;

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void test() {
    PingPongChannelHelper.Server serverFn = this::server;
    PingPongChannelHelper.Client clientFn = this::client;
    PingPongChannelHelper pph = new PingPongChannelHelper(1, 2, 2);
    pph.runPingPong(serverFn, clientFn, false);
    assertEquals(2 * 2 * 2 * N_BULK, MockNetwork.getAndResetForwardCount());
  }

  private void client(DatagramChannel channel, Path serverAddress, int id) throws IOException {
    String message = PingPongChannelHelper.MSG + "-" + id;
    ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());

    for (int i = 0; i < N_BULK; i++) {
      sendBuf.rewind();
      channel.write(sendBuf);
    }

    // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
    ByteBuffer response = ByteBuffer.allocate(512);

    for (int i = 0; i < N_BULK; i++) {
      response.clear();
      int len = channel.read(response);
      assertEquals(message.length(), len);
      response.flip();
      String pong = Charset.defaultCharset().decode(response).toString();
      assertEquals(message, pong);
    }
  }

  private static class Pair {
    Path path;
    String msg;

    Pair(Path path, String msg) {
      this.path = path;
      this.msg = msg;
    }
  }

  public void server(DatagramChannel channel) throws IOException {
    ByteBuffer request = ByteBuffer.allocate(512);
    //    System.out.println(
    //        "SERVER: --- USER - Waiting for packet --------------------- "
    //            + Thread.currentThread().getName());
    ArrayList<Pair> received = new ArrayList<>();
    for (int i = 0; i < N_BULK; i++) {
      request.clear();
      Path returnAddress = channel.receive(request);
      request.flip();
      String msg = Charset.defaultCharset().decode(request).toString();
      received.add(new Pair(returnAddress, msg));
      assertTrue(msg.startsWith(PingPongChannelHelper.MSG), msg);
      assertTrue(PingPongChannelHelper.MSG.length() + 3 >= msg.length());
    }

    for (int i = 0; i < N_BULK; i++) {

      //    System.out.println(
      //        "SERVER: --- USER - Sending packet ---------------------- "
      //            + Thread.currentThread().getName());
      Pair p = received.get(i);
      request.clear();
      request.put(p.msg.getBytes());
      request.flip();
      channel.send(request, p.path);
    }
  }
}
