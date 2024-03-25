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
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.ScionService;
import org.scion.Scmp;

public class ScmpInspectorTest {

  // packetsize + 80
  private static final byte[] SCMP_PKT_SIZE =
      new byte[] {
        0, 0, 0, 1, -55, 18, 0, -113, 1, 0, 0, 0, 0, 1, -1, 0,
        0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 16, 127, 0, 0, 1,
        127, 0, 0, 10, 1, 0, 32, 0, 0, 0, 24, 20, 101, 91, -128, -92,
        0, 63, 0, 1, 0, 0, 61, 102, -2, -28, -2, 90, 0, 63, 0, 0,
        0, 2, -113, 50, 53, 14, -67, -73, -54, 7, 2, 28, 0, 0, 0, 1,
        0, 0, 18, 110, 0, 0, 0, 0, -68, -35, -51, 105, 33, -10, -65, -35,
        9, 49, -47, 0, -89, 25, -113, 124, 4, 19, 90, -96, 0, 0, 0, 0,
        0, 0, 0, 1, 17, 21, 0, 99, 1, 48, 0, 0, 0, 1, -1, 0,
        0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 1,
        0, 0, 32, 0, 1, 0, 24, 20, 101, 91, -128, -92, 0, 63, 0, 0,
        0, 2, -113, 50, 53, 14, -67, -73, 0, 63, 0, 1, 0, 0, 61, 102,
        -2, -28, -2, 90, -91, 117, 31, -112, 0, 19, 0, 0, 72, 101, 108, 108,
        111, 32, 115, 99, 105, 111, 110,
      };

  // packetsize - 12
  private static final byte[] SCMP_PACKET_SIZE2 =
      new byte[] {
        0, 0, 0, 1, -55, 18, 0, -113, 1, 0, 0, 0, 0, 1, -1, 0,
        0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 16, 127, 0, 0, 1,
        127, 0, 0, 10, 1, 0, 32, 0, 0, 0, -86, 97, 101, 91, -115, -3,
        0, 63, 0, 1, 0, 0, 73, 104, -37, 2, 13, 57, 0, 63, 0, 0,
        0, 2, -6, -111, -118, 17, 87, -92, -54, 7, 2, 28, 0, 0, 0, 1,
        0, 0, 1, -13, 0, 0, 0, 0, -7, -64, -14, 51, -41, 56, -31, 97,
        73, 37, 25, -59, -57, 76, 74, 61, 4, 19, 59, 65, 0, 0, 0, 0,
        0, 0, 0, 1, 17, 21, 0, 7, 1, 48, 0, 0, 0, 1, -1, 0,
        0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 1,
        0, 0, 32, 0, 1, 0, -86, 97, 101, 91, -115, -3, 0, 63, 0, 0,
        0, 2, -6, -111, -118, 17, 87, -92, 0, 63, 0, 1, 0, 0, 73, 104,
        -37, 2, 13, 57, -44, 60, 31, -112, 0, 19, 0, 0, 72, 101, 108, 108,
        111, 32, 115, 99, 105, 111, 110,
      };

  // srcISDAS + 33
  private static final byte[] WRONG_SRC_ISD_AS =
      new byte[] {
        0, 0, 0, 1, -55, 18, 0, -113, 1, 0, 0, 0, 0, 1, -1, 0,
        0, 0, 1, 49, 0, 1, -1, 0, 0, 0, 1, 16, 127, 0, 0, 1,
        127, 0, 0, 10, 1, 0, 32, 0, 0, 0, 50, 39, 101, 91, -125, 30,
        0, 63, 0, 1, 0, 0, -34, -5, -33, 33, 50, -3, 0, 63, 0, 0,
        0, 2, 0, 33, 97, -10, -106, 109, -54, 7, 2, 28, 0, 0, 0, 1,
        0, 0, 22, -10, 0, 0, 0, 0, 91, 80, -88, -16, -127, -7, -116, 5,
        -25, -80, 91, -17, -89, 7, -18, -121, 4, 33, -38, 65, 0, 0, 0, 20,
        0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
        0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 49, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 1,
        0, 0, 32, 0, 1, 0, 50, 39, 101, 91, -125, 30, 0, 63, 0, 0,
        0, 2, 0, 33, 97, -10, -106, 109, 0, 63, 0, 1, 0, 0, -34, -5,
        -33, 33, 50, -3, -36, 49, 31, -112, 0, 19, 0, 0, 72, 101, 108, 108,
        111, 32, 115, 99, 105, 111, 110,
      };

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void testScmpError_WrongPacketSize() throws IOException {
    ByteBuffer data = ByteBuffer.wrap(SCMP_PKT_SIZE).asReadOnlyBuffer();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(data);
    ScmpHeader hdr = spi.getScmpHeader();
    assertEquals(Scmp.TypeCode.TYPE_4_CODE_19, hdr.getCode());
    assertEquals(Scmp.Type.ERROR_4, hdr.getType());
  }

  @Test
  void testScmpError_WrongPacketSize2() throws IOException {
    ByteBuffer data = ByteBuffer.wrap(SCMP_PACKET_SIZE2).asReadOnlyBuffer();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(data);
    ScmpHeader hdr = spi.getScmpHeader();
    assertEquals(Scmp.TypeCode.TYPE_4_CODE_19, hdr.getCode());
    assertEquals(Scmp.Type.ERROR_4, hdr.getType());
  }

  @Test
  void testScmpError_WrongSrcIsdAs() throws IOException {
    ByteBuffer data = ByteBuffer.wrap(WRONG_SRC_ISD_AS).asReadOnlyBuffer();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(data);
    ScmpHeader hdr = spi.getScmpHeader();
    assertEquals(Scmp.TypeCode.TYPE_4_CODE_33, hdr.getCode());
    assertEquals(Scmp.Type.ERROR_4, hdr.getType());
  }
}
