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

package org.scion.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.scion.ScionSocketAddress;
import org.scion.ScionUtil;

public class HeaderParser2Test {

  // Original incoming packet
  private static final byte[] packetBytes = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 2,
    1, 0, 32, 0, 1, 0, -103, -90, 100, -20, 100, -13, 0, 63, 0, 0,
    0, 2, 62, 57, -82, 1, -16, 51, 0, 63, 0, 1, 0, 0, -104, 77,
    -24, 2, -64, -11, 0, 100, 31, -112, 0, 19, -15, -27, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };

  // reversed packet
  private static final byte[] reversedBytes = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 3, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 18, 127, 0, 0, 2,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
    0, 0, 32, 0, 0, 0, -103, -90, 100, -20, 100, -13, 0, 63, 0, 1,
    0, 0, -104, 77, -24, 2, -64, -11, 0, 63, 0, 0, 0, 2, 62, 57,
    -82, 1, -16, 51, 0, 100, 31, -112, 0, 19, -15, -27, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };

  /**
   * Parse and re-serialize the packet. The generated content should be identical to the original
   * content
   */
  @Test
  public void testParse() {
    ByteBuffer buffer = ByteBuffer.wrap(packetBytes);
    InetSocketAddress firstHop = new InetSocketAddress("127.0.0.42", 23456); // TODO test

    ByteBuffer userRcvBuffer = ByteBuffer.allocate(10000);
    ScionHeaderParser.readUserData(buffer, userRcvBuffer);
    ScionSocketAddress remoteAddr = ScionHeaderParser.readRemoteSocketAddress(buffer, firstHop);
    userRcvBuffer.flip();

    // payload
    byte[] payloadBytes = new byte[userRcvBuffer.remaining()];
    userRcvBuffer.get(payloadBytes);
    String payload = new String(payloadBytes);
    assertEquals("Hello scion", payload);

    // remote address
    assertEquals(100, remoteAddr.getPort());
    assertEquals("127.0.0.2", remoteAddr.getHostName());
    assertEquals("1-ff00:0:110", ScionUtil.toStringIA(remoteAddr.getIsdAs()));
    assertEquals(firstHop, remoteAddr.getPath().getFirstHopAddress());

    // path
    byte[] path = remoteAddr.getPath().getRawPath();
    assertEquals(36, path.length);
    for (int i = 0; i < path.length; i++) {
      assertEquals(reversedBytes[i + 48], path[i]);
    }
  }
}
