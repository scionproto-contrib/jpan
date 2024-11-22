// Copyright 2024 ETH Zurich
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

package org.scion.jpan.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import org.junit.jupiter.api.Test;

class StunTest {

  @Test
  void test() throws IOException {
    ByteBuffer out = ByteBuffer.allocate(1000);
    STUN.TransactionID id = STUN.writeRequest(out);
    // STUN.TransactionID id = STUN.writeRequestLib(out);
    InetAddress addr = InetAddress.getByName("stun.solnet.ch");
    InetSocketAddress server = new InetSocketAddress(addr, 3478);
    try (DatagramChannel channel = DatagramChannel.open()) {

      out.flip();
      int sent = channel.send(out, server);
      System.out.println("Sent bytes: " + sent);

      System.out.println("Waiting ...");
      ByteBuffer in = ByteBuffer.allocate(1000);
      InetSocketAddress server2 = (InetSocketAddress) channel.receive(in);
      System.out.println("Received from: " + server2);
      in.flip();
      System.out.print("byte[] raw = {");
      for (int i = 0; i < in.remaining(); i++) {
        System.out.print(in.get(i) + ", ");
      }
      System.out.println("};");

      // Answer:
      byte[] raw = {
        1, 1, 0, 68, 66, -92, 18, 33, -122, 63, -4, -36, -46, -105, -40, -78, 104, 12, -106, 9, 0,
        1, 0, 8, 0, 1, -50, 39, -127, -124, -26, 73, 0, 4, 0, 8, 0, 1, 13, -105, -44, 101, 4, 120,
        0, 5, 0, 8, 0, 1, 13, -106, -44, 101, 4, 8, 0, 32, 0, 8, 0, 1, -116, -125, -61, 32, -12,
        104, -128, 34, 0, 16, 86, 111, 118, 105, 100, 97, 46, 111, 114, 103, 32, 48, 46, 57, 55, 0,
      };

      boolean isSTUN = STUN.isStunPacket(in, id);
      System.out.println("Is stun: " + isSTUN);
      if (isSTUN) {
        InetSocketAddress external = STUN.parseResponse(in);
        System.out.println("Address: " + external);
      }
    }
  }
}
