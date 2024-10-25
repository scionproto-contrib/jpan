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
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionService;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.PingPongChannelHelper;

/** Test path switching (changing first hop) on DatagramChannel. */
class DatagramChannelPathSwitchTest {

  private final PathPolicy alternatingPolicy =
      new PathPolicy() {
        private int count = 0;

        @Override
        public Path filter(List<Path> paths) {
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
    PingPongChannelHelper.Server serverFn = PingPongChannelHelper::defaultServer;
    PingPongChannelHelper.Client clientFn = this::client;
    PingPongChannelHelper pph =
        PingPongChannelHelper.newBuilder(1, 2, 10).resetCounters(false).build();
    pph.runPingPong(serverFn, clientFn);
    // TODO: This sometimes reports 22 i.o. 20.
    assertEquals(
        2 * 10,
        MockNetwork.getForwardCount(0),
        "Actual: " + MockNetwork.getForwardCount(0) + "/" + MockNetwork.getForwardCount(1));
    assertEquals(2 * 10, MockNetwork.getForwardCount(1));
    assertEquals(2 * 2 * 10, MockNetwork.getAndResetForwardCount());
  }

  private void client(ScionDatagramChannel channel, Path serverAddress, int id) throws IOException {
    String message = PingPongChannelHelper.MSG + "-" + id;
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
