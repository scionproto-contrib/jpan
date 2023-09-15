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
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.scion.DaemonClient;
import org.scion.ScionDatagramSocket;
import org.scion.Util;
import org.scion.proto.daemon.Daemon;

public class HeaderComposerTest {


  private static final byte[] packBytes = {
          0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
          0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 2,
          0, 0, 32, 0, 1, 0, 95, (129-256), 101, 0, 88, (226-256), 0, 63, 0, 0,
          0, 2, (215-256), 5, (252-256), (177-256), 118, 33, 0, 63, 0, 1, 0, 0, (137-256), 66,
          (193-256), (157-256), (193-256), 106, 0, 100, 31, -112, 0, 19, -15, -27, 72, 101, 108, 108,
          111, 32, 115, 99, 105, 111, 110,
  };
  private static final byte[] packetBytes = {
          0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
          0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 2,
          1, 0, 32, 0, 1, 0, -103, -90, 100, -20, 100, -13, 0, 63, 0, 0,
          0, 2, 62, 57, -82, 1, -16, 51, 0, 63, 0, 1, 0, 0, -104, 77,
          -24, 2, -64, -11, 0, 100, 31, -112, 0, 19, -15, -27, 72, 101, 108, 108,
          111, 32, 115, 99, 105, 111, 110,
  };

//  // From HeaderParserTest
//  private static final byte[] packetBytes = {
//          0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
//          0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
//          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 2,
//          1, 0, 32, 0, 1, 0, -103, -90, 100, -20, 100, -13, 0, 63, 0, 0,
//          0, 2, 62, 57, -82, 1, -16, 51, 0, 63, 0, 1, 0, 0, -104, 77,
//          -24, 2, -64, -11, 0, 100, 31, -112, 0, 19, -15, -27, 72, 101, 108, 108,
//          111, 32, 115, 99, 105, 111, 110,
//  };

  // 00 00 00 00 00 00 00 00 00 00 00 00 86 dd 60 09
  // 02 48 00 73 11 40 fd 00 f0 0d ca fe 00 00 00 00
  // 00 00 7f 00 00 09 fd 00 f0 0d ca fe 00 00 00 00
  // 00 00 7f 00 00 09 75 59 79 24 00 73 6e b2
  //             00 00
  //            00 01 11 15 00 17 01 03 00 00 00 01 ff 00 00 00
  //            01 10 00 01 ff 00 00 00 01 12 7f 00 00 02 00 00
  //            0060   00 00 00 00 00 00 00 00 00 00 00 00 00 01 00 00
  //            0070   20 00 00 00 cf 8d 64 ef 1d 56 00 3f 00 01 00 00
  //            0080   41 99 96 80 e6 17 00 3f 00 00 00 02 39 55 e0 37
  //            0090   36 a2 1f 90 00 64 00 17 65 58 52 65 3a 20 48 65
  //            00a0   6c 6c 6f 20 73 63 69 6f 6e

  // 0000   00 00 00 00 00 00 00 00 00 00 00 00 86 dd 60 0c   ..............`.
  // 0010   c0 78 00 73 11 40 fd 00 f0 0d ca fe 00 00 00 00   .x.s.@..........
  // 0020   00 00 7f 00 00 09 fd 00 f0 0d ca fe 00 00 00 00   ................
  // 0030   00 00 7f 00 00 09 9c 69 79 24 00 73 6e b2 00 00   .......iy$.sn...
  //  private static final byte[] packetBytes2 = {
  //    0x00, 0x00, 0x00, 0x01, 0x11, 0x15, 0x00, 0x17, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01, 0xff,
  // 0x00,
  //    0x00, 0x00, 0x01, 0x10, 0x00, 0x01, 0xff, 0x00, 0x00, 0x00, 0x01, 0x12, 0x7f, 0x00, 0x00,
  // 0x02,
  //    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  // 0x01,
  //    0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0xcf, 0x8d, 0x64, 0xef, 0x1d, 0x56, 0x00, 0x00, 0x00,
  // 0x00,
  //    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  // 0x00,
  //    0x00, 0x00, 0x00, 0x00, 0x1f, 0x90, 0x00, 0x64, 0x00, 0x0f, 0xf1, 0xe5, 0x52, 0x65, 0x3a,
  // 0x20,
  //    0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x20, 0x73, 0x63, 0x69, 0x6f, 0x6e
  //  };

  /**
   * Parse and re-serialize the packet. The generated content should be identical to the original
   * content
   */
  @Test
  public void testCompose() throws IOException {
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    PseudoHeader pseudoHeaderUdp = new PseudoHeader();
    byte[] data = new byte[500];

    // User side
    String hostname = "::1";
    int port = 8080;
    //        dstIA, err := addr.ParseIA("1-ff00:0:112")
    //        srcIA, err := addr.ParseIA("1-ff00:0:110")
    //        srcAddr, err := net.ResolveUDPAddr("udp", "127.0.0.2:100")
    //        dstAddr, err := net.ResolveUDPAddr("udp", "[::1]:8080")
    InetAddress address = InetAddress.getByName(hostname);
    ScionDatagramSocket socket = new ScionDatagramSocket();
    //socket.setDstIsdAs( "1-ff00:0:112");
    long dstIA = Util.ParseIA("1-ff00:0:112");
    String msg = "Hello scion";
    byte[] sendBuf = msg.getBytes();
    DatagramPacket userPacket = new DatagramPacket(sendBuf, sendBuf.length, address, port);


    // Socket internal - compose header data
    DaemonClient pathService = DaemonClient.create();
    long srcIA = pathService.getLocalIsdAs();
    List<Daemon.Path> path = pathService.getPath(srcIA, dstIA);
    scionHeader.setSrcIA(srcIA);
    scionHeader.setDstIA(dstIA);
    DatagramSocket dummySocket = new DatagramSocket(port); // TODO same port??? Is that possioble?
    scionHeader.setSrcHostAddress(dummySocket.getLocalAddress());
    scionHeader.setDstHostAddress(userPacket.getAddress());

    // Socket internal = write header
    int offset = scionHeader.write(data, userPacket.getLength(), pathHeaderScion, Constants.PathTypes.SCION);
    // System.out.println("Common header: " + commonHeader);
    assertEquals(1, scionHeader.pathType().code());

    // System.out.println("Address header: " + addressHeader);
    //offset = pathHeaderScion.write(data, offset);
    offset = pathHeaderScion.writePath(data, offset, path);

    // Pseudo header
    offset = pseudoHeaderUdp.write(data, offset, userPacket.getLength());
    System.out.println(pseudoHeaderUdp);

    byte[] newData = new byte[65000];
    DatagramPacket p = new DatagramPacket(newData, newData.length);
    System.arraycopy(
            userPacket.getData(), userPacket.getOffset(), p.getData(), offset, userPacket.getLength());
    p.setLength(offset + userPacket.getLength());
//    System.out.println(
//            "length: " + offset + " + " + userPacket.getLength() + "   vs  " + p.getData().length + "  -> " + p.getLength());



    // Socket internal - prepare send
    int underlayPort = -1;
    InetAddress underlayAddress = InetAddress.getByName("127.0.0.1"); // TODO ????????????
    // First hop
    // TODO ?!?!?!?!?!
    // p.setPort(underlayPort);
    p.setPort(31012);  // TODO !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!??????????????//????????????????????????
    p.setAddress(underlayAddress);
    System.out.println("Sending to underlay: " + underlayAddress + " : " + underlayPort);

    byte[] payload = new byte[data.length - offset];
    System.arraycopy(data, offset, payload, 0, payload.length);

    // -----------------------------------------------------------------------------------------------------------

    //        // Reverse everything
    //        commonHeader.reverse();
    //        addressHeader.reverse();
    //        pathHeaderScion.reverse();
    //        pseudoHeaderUdp.reverse();

    // Send packet
    //    byte[] newData = new byte[data.length];
    //
    //    DatagramPacket userInput = new DatagramPacket(payload, payload.length);
    //    InetAddress srcAddress = Inet6Address.getByAddress(new byte[] {127, 0, 0, 1});  // TODO
    // ????
    //    scionHeader.setSrcHostAddress(srcAddress);
    //    int writeOffset = scionHeader.write(newData, userInput, pathHeaderScion);
    //    writeOffset = pathHeaderScion.write(newData, writeOffset);
    //    writeOffset = pseudoHeaderUdp.write(newData, writeOffset, userInput.getLength());
    //
    //    // payload
    //    System.arraycopy(userInput.getData(), 0, newData, writeOffset, userInput.getLength());

    // Fix CurrInf which is "1" in the sample packet:
    // TODO payload[48] = 1;

    // PathMeta start at 110!!

    // assertEquals(offset, writeOffset);
    for (int i = 0; i < data.length; i++) {
      System.out.println("i=" + i + ":  "
                  + Integer.toHexString(Byte.toUnsignedInt(packetBytes[i]))
                  + " - "
                  + Integer.toHexString(Byte.toUnsignedInt(data[i])));
      if (i == 5) {
        continue; // TODO remove
      }
      assertEquals(packetBytes[i], data[i]);
    }

    assertArrayEquals(packetBytes, data);
  }
}
