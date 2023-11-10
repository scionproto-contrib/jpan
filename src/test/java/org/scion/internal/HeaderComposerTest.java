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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scion.PackageVisibilityHelper;
import org.scion.Scion;
import org.scion.ScionUtil;
import org.scion.demo.inspector.Constants;
import org.scion.demo.inspector.OverlayHeader;
import org.scion.demo.inspector.PathHeaderScion;
import org.scion.demo.inspector.ScionHeader;
import org.scion.proto.daemon.Daemon;
import org.scion.testutil.ExamplePacket;
import org.scion.testutil.MockDaemon;

public class HeaderComposerTest {

  // Recorded before sending a packet
  private static final byte[] packetBytes = ExamplePacket.PACKET_BYTES_2;

  private static MockDaemon daemon;

  private Scion.CloseableService pathService = null;


  @BeforeAll
  public static void beforeAll() throws IOException {
    daemon = MockDaemon.create().start();
  }

  @AfterAll
  public static void afterAll() throws IOException {
    if (daemon != null) {
      daemon.close();
    }
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

  /**
   * Parse and re-serialize the packet. The generated content should be identical to the original
   * content
   */
  @Test
  public void testCompose() throws IOException {
    MockDaemon.getAndResetCallCount(); // reset counter
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();
    byte[] data = new byte[500];
    DatagramPacket p = new DatagramPacket(data, data.length);

    // User side
    String hostname = "::1";
    int dstPort = 8080;
    //        dstIA, err := addr.ParseIA("1-ff00:0:112")
    //        srcIA, err := addr.ParseIA("1-ff00:0:110")
    //        srcAddr, err := net.ResolveUDPAddr("udp", "127.0.0.2:100")
    //        dstAddr, err := net.ResolveUDPAddr("udp", "[::1]:8080")
    InetAddress address = InetAddress.getByName(hostname);
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    String msg = "Hello scion";
    byte[] sendBuf = msg.getBytes();
    DatagramPacket userPacket = new DatagramPacket(sendBuf, sendBuf.length, address, dstPort);

    // Socket internal - compose header data
    pathService = Scion.newServiceForAddress(MockDaemon.DEFAULT_ADDRESS_STR);
    long srcIA = pathService.getLocalIsdAs();
    Daemon.Path path = PackageVisibilityHelper.getPathList(pathService, srcIA, dstIA).get(0);
    scionHeader.setSrcIA(srcIA);
    scionHeader.setDstIA(dstIA);
    InetAddress srcAddress = InetAddress.getByName("127.0.0.2");
    scionHeader.setSrcHostAddress(srcAddress.getAddress());
    scionHeader.setDstHostAddress(userPacket.getAddress().getAddress());

    // Socket internal = write header
    int offset = scionHeader.write(data, 0, userPacket.getLength(), path.getRaw().size(), Constants.PathTypes.SCION);
    assertEquals(1, scionHeader.pathType().code());
    offset = pathHeaderScion.writePath(data, offset, path);

    // Pseudo header
    offset = overlayHeaderUdp.write(data, offset, userPacket.getLength(), 100, dstPort);

    System.arraycopy(userPacket.getData(), userPacket.getOffset(), p.getData(), offset, userPacket.getLength());
    p.setLength(offset + userPacket.getLength());

    for (int i = 0; i < p.getLength(); i++) {
      if (i >= 54 && i <= 59) {
        // ignore segID field and timestamp.
        // TODO test if timestamp is useful!
        continue;
      }
      if (i >= 66 && i <= 71) {
        // ignore MAC #1
        continue;
      }
      if (i >= 78 && i <= 83) {
        // ignore MAC #2
        continue;
      }
      if (i >= 90 && i <= 91) {
        // ignore UDP checksum
        continue;
      }
      assertEquals(packetBytes[i], data[i], "Mismatch at position " + i);
    }
    assertEquals(2, MockDaemon.getAndResetCallCount());
  }
}
