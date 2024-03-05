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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.testutil.ExamplePacket;
import org.scion.testutil.MockDatagramChannel;
import org.scion.testutil.MockNetwork;

/** Test path switching on DatagramChannel in case of network problems. */
class DatagramChannelErrorHandlingTest {

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Disabled
  @Test
  void testErrorHandling() throws IOException {
    MockDatagramChannel mock = MockDatagramChannel.open();
    MockNetwork.startTiny();
    InetSocketAddress dstAddr = new InetSocketAddress("127.0.0.1", 12345);
    try (DatagramChannel channel = Scion.defaultService().openChannel()) {
      AtomicInteger scmpReceived = new AtomicInteger();
      channel.setScmpErrorListener(
          message -> {
            scmpReceived.incrementAndGet();
            System.out.println("msg: " + message.getTypeCode());
            throw new IllegalArgumentException();
          });
      List<RequestPath> paths = Scion.defaultService().getPaths(ExamplePacket.DST_IA, dstAddr);
      assertEquals(2, paths.size());
      RequestPath path0 = paths.get(0);
      RequestPath path1 = paths.get(0);
      channel.connect(path0);
      channel.write(ByteBuffer.allocate(0));
      assertEquals(path0, channel.getConnectionPath());

      // TODO Use mock instead of daemon?
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.TypeCode.TYPE_5);
      channel.write(ByteBuffer.allocate(0));
      assertEquals(path0, channel.getConnectionPath());
      assertEquals(1, scmpReceived.get());
      // mock.setSendCallback((byteBuffer,path) -> {});

    } finally {
      mock.close();
    }
  }
}
