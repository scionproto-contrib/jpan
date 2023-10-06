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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

public class DatagramChannelApiTest {

  @Test
  public void send_RequiresInetSocketAddress() throws IOException {
    ScionDatagramChannel channel = new ScionDatagramChannel();
    SocketAddress addr =
        new SocketAddress() {
          @Override
          public int hashCode() {
            return super.hashCode();
          }
        };
    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              channel.send(ByteBuffer.allocate(10), addr);
            });

    String expectedMessage = "must be of type InetSocketAddress";
    String actualMessage = exception.getMessage();

    assertTrue(actualMessage.contains(expectedMessage));
  }

  @Test
  public void sendPath_RequiresInetSocketAddress() throws IOException {
    ScionDatagramChannel channel = new ScionDatagramChannel();
    SocketAddress addr =
        new SocketAddress() {
          @Override
          public int hashCode() {
            return super.hashCode();
          }
        };
    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              channel.send(ByteBuffer.allocate(10), addr, null);
            });

    String expectedMessage = "must be of type InetSocketAddress";
    String actualMessage = exception.getMessage();

    assertTrue(actualMessage.contains(expectedMessage));
  }
}
