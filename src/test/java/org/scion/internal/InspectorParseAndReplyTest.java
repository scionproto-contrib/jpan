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

import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.demo.inspector.Constants;
import org.scion.demo.inspector.OverlayHeader;
import org.scion.demo.inspector.PathHeaderScion;
import org.scion.demo.inspector.ScionHeader;
import org.scion.testutil.ExamplePacket;

class InspectorParseAndReplyTest {
  private static final byte[] packetBytes = ExamplePacket.PACKET_BYTES_SERVER_E2E_PING;
  private static final byte[] responseBytes = ExamplePacket.PACKET_BYTES_SERVER_E2E_PONG;

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  /** Parse a packet and create a reply packet. */
  @Test
  void testParseAndReply() {
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();
    ByteBuffer data = ByteBuffer.wrap(packetBytes);

    scionHeader.read(data);
    assertEquals(1, scionHeader.pathType().code());
    pathHeaderScion.read(data);
    // Overlay header
    overlayHeaderUdp.read(data);

    byte[] payload = new byte[data.remaining()];
    data.get(payload);

    // Send packet
    ByteBuffer newData = ByteBuffer.allocate(data.limit());

    DatagramPacket userInput = new DatagramPacket(payload, payload.length);
    scionHeader.reverse();
    pathHeaderScion.reverse();
    overlayHeaderUdp.reverse();
    scionHeader.write(
        newData, userInput.getLength(), pathHeaderScion.length(), Constants.PathTypes.SCION);
    pathHeaderScion.write(newData);
    overlayHeaderUdp.write(newData, userInput.getLength());
    newData.put(userInput.getData(), 0, userInput.getLength());

    assertEquals(data.position(), newData.position());
    assertArrayEquals(responseBytes, newData.array());
  }
}
