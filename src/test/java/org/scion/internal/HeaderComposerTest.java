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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.ScionPathService;
import org.scion.PathServiceHelper;
import org.scion.ScionUtil;
import org.scion.proto.daemon.Daemon;

public class HeaderComposerTest {

  // Recorded before sending a packet
  private static final byte[] packetBytes = {
    0, 0, 0, 1, 17, 21, 0, 19, // 8
    1, 48, 0, 0, 0, 1, -1, 0, // 16
    0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0, // 32
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 2, // 48
    0, 0, 32, 0, 1, 0, 95, (129 - 256), 101, 0, 88, (226 - 256), 0, 63, 0, 0, // 64
    0, 2, (215 - 256), 5, (252 - 256), (177 - 256), 118, 33, // 72
    0, 63, 0, 1, 0, 0, (137 - 256), 66, // 80
    (193 - 256), (157 - 256), (193 - 256), 106, 0, 100, 31, -112, // 88
    0, 19, -15, -27, 72, 101, 108, 108, // 96
    111, 32, 115, 99, 105, 111, 110, // 103
  };

  private ScionPathService pathService = null;

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
    long dstIA = ScionUtil.ParseIA("1-ff00:0:112");
    String msg = "Hello scion";
    byte[] sendBuf = msg.getBytes();
    DatagramPacket userPacket = new DatagramPacket(sendBuf, sendBuf.length, address, dstPort);

    // Socket internal - compose header data
    pathService = ScionPathService.create();
    long srcIA = pathService.getLocalIsdAs();
    Daemon.Path path = PathServiceHelper.getPathList(pathService, srcIA, dstIA).get(0);
    scionHeader.setSrcIA(srcIA);
    scionHeader.setDstIA(dstIA);
    InetAddress srcAddress = InetAddress.getByName("127.0.0.2");
    scionHeader.setSrcHostAddress(srcAddress);
    scionHeader.setDstHostAddress(userPacket.getAddress());

    // Socket internal = write header
    int offset = scionHeader.write(data, userPacket.getLength(), path.getRaw().size(), Constants.PathTypes.SCION);
    assertEquals(1, scionHeader.pathType().code());
    offset = pathHeaderScion.writePath(data, offset, path);

    // Pseudo header
    offset = overlayHeaderUdp.write(data, offset, userPacket.getLength(), 100, dstPort);
    // System.out.println(overlayHeaderUdp);

    System.arraycopy(userPacket.getData(), userPacket.getOffset(), p.getData(), offset, userPacket.getLength());
    p.setLength(offset + userPacket.getLength());

    // Socket internal - prepare send
    int underlayPort = -1;
    InetAddress underlayAddress = InetAddress.getByName("127.0.0.1"); // TODO ????????????
    // First hop
    // TODO ?!?!?!?!?!
    // p.setPort(underlayPort);
    p.setPort(
        31012); // TODO !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!??????????????//????????????????????????
    p.setAddress(underlayAddress);
    // System.out.println("Sending to underlay: " + underlayAddress + " : " + underlayPort);

    // assertEquals(offset, writeOffset);
    for (int i = 0; i < p.getLength(); i++) {
      //      System.out.println(
      //          "i="
      //              + i
      //              + ":  "
      //              + Integer.toHexString(Byte.toUnsignedInt(packetBytes[i]))
      //              + " - "
      //              + Integer.toHexString(Byte.toUnsignedInt(data[i])));
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
      assertEquals(packetBytes[i], data[i]);
    }
  }
}
