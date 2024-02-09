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

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scion.Scion;
import org.scion.ScionService;
import org.scion.ScionUtil;
import org.scion.demo.inspector.Constants;
import org.scion.demo.inspector.OverlayHeader;
import org.scion.demo.inspector.PathHeaderScion;
import org.scion.demo.inspector.ScionHeader;
import org.scion.testutil.ExamplePacket;
import org.scion.testutil.MockDaemon;

class InspectorComposeTest {

  // Recorded before sending a packet
  private static final byte[] packetBytes = ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING;

  private Scion.CloseableService pathService = null;

  @BeforeAll
  public static void beforeAll() throws IOException {
    MockDaemon.createAndStartDefault();
  }

  @AfterAll
  public static void afterAll() throws IOException {
    MockDaemon.closeDefault();
    // Defensive clean up
    ScionService.closeDefault();
  }

  @AfterEach
  public void afterEach() {
    // path service
    if (pathService != null) {
      try {
        pathService.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      pathService = null;
    }
  }

  /** Compose a packet from scratch using the inspector classes. */
  @Test
  void testCompose() throws IOException {
    MockDaemon.getAndResetCallCount(); // reset counter
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();
    byte[] dataBytes = new byte[500];
    ByteBuffer data = ByteBuffer.wrap(dataBytes);
    DatagramPacket p = new DatagramPacket(dataBytes, dataBytes.length);

    // User side
    String hostname = "::1";
    int dstPort = 8080;
    InetAddress dstAddress = InetAddress.getByName(hostname);
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    String msg = "Hello scion";
    byte[] sendBuf = msg.getBytes();
    DatagramPacket userPacket = new DatagramPacket(sendBuf, sendBuf.length, dstAddress, dstPort);

    // Socket internal - compose header data
    pathService = Scion.newServiceWithDaemon(MockDaemon.DEFAULT_ADDRESS_STR);
    long srcIA = pathService.getLocalIsdAs();
    InetSocketAddress dstSocketAddress = new InetSocketAddress(dstAddress, dstPort);
    byte[] path = pathService.getPaths(dstIA, dstSocketAddress).get(0).getRawPath();
    scionHeader.setSrcIA(srcIA);
    scionHeader.setDstIA(dstIA);
    InetAddress srcAddress = InetAddress.getByName("127.0.0.1");
    scionHeader.setSrcHostAddress(srcAddress.getAddress());
    scionHeader.setDstHostAddress(userPacket.getAddress().getAddress());

    // Socket internal = write header
    scionHeader.write(data, userPacket.getLength(), path.length, Constants.PathTypes.SCION);
    assertEquals(1, scionHeader.pathType().code());
    pathHeaderScion.writePath(data, path);

    // Overlay header
    overlayHeaderUdp.write(data, userPacket.getLength(), 44444, dstPort);

    data.put(userPacket.getData(), 0, userPacket.getLength());
    p.setLength(data.position());

    // NB: We expect everything to match here, including timestamp and MAC.
    //     This is because our mock-daemon has a fixed stored (correct) path.
    //     Only the UDP checksum will differ.
    for (int i = 0; i < p.getLength(); i++) {
      //      if (i >= 54 && i <= 59) {
      //        // ignore segID field and timestamp.
      //        continue;
      //      }
      //      if (i >= 66 && i <= 71) {
      //        // ignore MAC #1
      //        continue;
      //      }
      //      if (i >= 78 && i <= 83) {
      //        // ignore MAC #2
      //        continue;
      //      }
      if (i >= 90 && i <= 91) {
        // ignore UDP checksum
        continue;
      }
      assertEquals(packetBytes[i], data.get(i), "Mismatch at position " + i);
    }
    assertEquals(2, MockDaemon.getAndResetCallCount());
  }
}
