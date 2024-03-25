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

package org.scion.demo.inspector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.ScionService;
import org.scion.ScionUtil;
import org.scion.testutil.ExamplePacket;

public class ScionPacketInspectorTest {

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void test() throws IOException {
    // client
    ByteBuffer composed = compose("Hello scion".getBytes());

    byte[] expectedCompose = ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING;
    for (int i = 0; i < expectedCompose.length; i++) {
      if (i >= 90 && i <= 91) {
        continue; // skip UDP checksum
      }
      assertEquals(expectedCompose[i], composed.get(i), "i=" + i);
    }

    // server
    composed.flip();
    ByteBuffer reply = composeReply(composed, "Hello scion".getBytes());
    byte[] expectedReply = ExamplePacket.PACKET_BYTES_SERVER_E2E_PONG;
    for (int i = 0; i < expectedReply.length; i++) {
      if (i >= 54 && i <= 55) {
        continue; // skip segmentID
      }
      if (i >= 90 && i <= 91) {
        continue; // skip UDP checksum
      }
      assertEquals(expectedReply[i], reply.get(i), "i=" + i);
    }
  }

  private static ByteBuffer compose(byte[] payload) {
    // Send packet
    ByteBuffer newData = ByteBuffer.allocate(10000);
    ScionPacketInspector spi = ScionPacketInspector.createEmpty();

    DatagramPacket userInput = new DatagramPacket(payload, payload.length);
    ScionHeader scionHeader = spi.getScionHeader();
    scionHeader.setSrcHostAddress(new byte[] {127, 0, 0, 1});
    scionHeader.setDstHostAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
    scionHeader.setSrcIA(ScionUtil.parseIA("1-ff00:0:110"));
    scionHeader.setDstIA(ScionUtil.parseIA("1-ff00:0:112"));

    // There may not be a daemon running so we use a hard coded path in this example.
    // byte[] path =
    // ScionService.defaultService().getPath(ScionUtil.parseIA("1-ff00:0:112")).getRawPath();
    byte[] path = ExamplePacket.PATH_RAW_TINY_110_112;
    PathHeaderScion pathHeader = spi.getPathHeaderScion();
    pathHeader.read(ByteBuffer.wrap(path)); // Initialize path

    // UDP overlay header
    OverlayHeader overlayHeaderUdp = spi.getOverlayHeaderUdp();
    overlayHeaderUdp.set(userInput.getLength(), 44444, 8080);

    spi.writePacket(newData, payload);
    return newData;
  }

  public static ByteBuffer composeReply(ByteBuffer data, byte[] userData) throws IOException {
    ScionPacketInspector spi = ScionPacketInspector.readPacket(data);

    // reverse path etc
    spi.reversePath();

    // Probably a bit larger than necessary ...
    ByteBuffer newData = ByteBuffer.allocate(data.limit() + userData.length);
    spi.writePacket(newData, userData);

    return newData;
  }
}
