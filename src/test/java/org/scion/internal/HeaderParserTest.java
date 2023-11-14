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
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.scion.demo.inspector.Constants;
import org.scion.demo.inspector.OverlayHeader;
import org.scion.demo.inspector.PathHeaderScion;
import org.scion.demo.inspector.ScionHeader;
import org.scion.testutil.ExamplePacket;

public class HeaderParserTest {
  private static final byte[] packetBytes = ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING;

  /**
   * Parse a packet and create a duplicate from the parse packet. The generated content should be
   * identical to the original content.
   */
  @Test
  public void testParseAndReverse() {
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();
    byte[] data = packetBytes;

    int offset = scionHeader.read(data, 0);
    // System.out.println("Common header: " + commonHeader);
    assertEquals(1, scionHeader.pathType().code());

    // System.out.println("Address header: " + addressHeader);
    offset = pathHeaderScion.read(data, offset);

    // Pseudo header
    offset = overlayHeaderUdp.read(data, offset);
    // System.out.println(overlayHeaderUdp);

    byte[] payload = new byte[data.length - offset];
    System.arraycopy(data, offset, payload, 0, payload.length);

    // Send packet
    byte[] newData = new byte[data.length];

    DatagramPacket userInput = new DatagramPacket(payload, payload.length);
    scionHeader.setSrcHostAddress(new byte[] {127, 0, 0, 1});
    int writeOffset =
        scionHeader.write(
            newData, 0, userInput.getLength(), pathHeaderScion.length(), Constants.PathTypes.SCION);
    writeOffset = pathHeaderScion.write(newData, writeOffset);
    writeOffset = overlayHeaderUdp.write(newData, writeOffset, userInput.getLength());

    // payload
    System.arraycopy(userInput.getData(), 0, newData, writeOffset, userInput.getLength());

    assertEquals(offset, writeOffset);
    assertArrayEquals(data, newData);

    // After reversing, they should not be equal
    scionHeader.reverse();
    pathHeaderScion.reverse();
    // write
    writeOffset =
        scionHeader.write(
            newData, 0, userInput.getLength(), pathHeaderScion.length(), Constants.PathTypes.SCION);
    writeOffset = pathHeaderScion.write(newData, writeOffset);
    overlayHeaderUdp.write(newData, writeOffset, userInput.getLength());
    assertFalse(Arrays.equals(data, newData));

    // Reversing again -> equal again!
    scionHeader.reverse();
    pathHeaderScion.reverse();
    // write
    writeOffset =
        scionHeader.write(
            newData, 0, userInput.getLength(), pathHeaderScion.length(), Constants.PathTypes.SCION);
    writeOffset = pathHeaderScion.write(newData, writeOffset);
    overlayHeaderUdp.write(newData, writeOffset, userInput.getLength());
    assertArrayEquals(data, newData);
  }
}
