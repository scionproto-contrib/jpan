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
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.testutil.MockNetwork;
import org.scion.testutil.PingPongHelper;

/** Test path switching (changing first hop) on DatagramChannel. */
class DatagramChannelPathSwitchTest {

  private final PathPolicy alternatingPolicy =
      new PathPolicy() {
        private int count = 0;

        @Override
        public RequestPath filter(List<RequestPath> paths) {
          return paths.get(count++ % 2);
        }
      };

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void test() {
    PingPongHelper.Server serverFn = PingPongHelper::defaultServer;
    PingPongHelper.Client clientFn = this::client;
    PingPongHelper pph = new PingPongHelper(1, 2, 10);
    pph.runPingPong(serverFn, clientFn, false);
    assertEquals(2 * 10, MockNetwork.getForwardCount(0));
    assertEquals(2 * 10, MockNetwork.getForwardCount(1));
    assertEquals(2 * 2 * 10, MockNetwork.getAndResetForwardCount());
  }

  private void client(DatagramChannel channel, Path serverAddress, int id) throws IOException {
    String message = PingPongHelper.MSG + "-" + id;
    ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());

    // Use a path policy that alternates between 1st and 2nd path
    // -> setPathPolicy() sets a new path!
    channel.setPathPolicy(alternatingPolicy);
    channel.write(sendBuf);

    // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
    ByteBuffer response = ByteBuffer.allocate(512);
    int len = channel.read(response);
    assertEquals(message.length(), len);

    response.flip();
    String pong = Charset.defaultCharset().decode(response).toString();
    assertEquals(message, pong);
  }
}
