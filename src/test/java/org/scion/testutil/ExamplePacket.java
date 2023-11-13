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

package org.scion.testutil;

public class ExamplePacket {

  public static final String MSG = "Hello scion";

  /**
   * Packet bytes for a message sent in the "tiny"network config in scionproto.
   *
   * DST = IPv6 = ::1
   * SRC = IPv4 = 127.0.0.2
   * Payload: "Hello scion"
   *
   * Common Header:   VER=0  TrafficClass=0  FlowID=1  NextHdr=17  HdrLen=21/84  PayloadLen=19  PathType=1  DT=0  DL=3  ST=0  SL=0  RSV=0
   * Address Header:   dstIsdAs=1-ff00:0:112  srcIsdAs=1-ff00:0:110  dstHost=0/::1  srcHost=0/127.0.0.2
   * Path header:   currINF=0  currHP=1  reserved=0  seg0Len=2  seg1Len=0  seg2Len=0
   *   info0=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, P=false, C=true, reserved=0, segID=39334, timestamp=1693213939}
   *     hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false, E=false, expiryTime=63, consIngress=0, consEgress=2, mac=68417453420595}
   *     hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false, E=false, expiryTime=63, consIngress=1, consEgress=0, mac=167460372398325}
   * UdpPseudoHeader{srcPort=100, dstPort=8080, length=19, checkSum=61925}
   * Payload: Hello scion
   */
  public static final byte[] PACKET_BYTES = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 2,
    1, 0, 32, 0, 1, 0, -103, -90, 100, -20, 100, -13, 0, 63, 0, 0,
    0, 2, 62, 57, -82, 1, -16, 51, 0, 63, 0, 1, 0, 0, -104, 77,
    -24, 2, -64, -11, 0, 100, 31, -112, 0, 19, -15, -27, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };

//  TODO something is really off here! Why is dstHost==srcHost?  Double check go-lient recording?
//      Difference to PACKET_BYTES_2 This should also be a recording?????

  /**
   * Packet bytes for a return packet sent as answer to PACKET_BYTES.
   *
   * Common Header:   VER=0  TrafficClass=0  FlowID=1  NextHdr=17  HdrLen=21/84  PayloadLen=19  PathType=1  DT=0  DL=0  ST=0  SL=0  RSV=0
   * Address Header:   dstIsdAs=1-ff00:0:110  srcIsdAs=1-ff00:0:112  dstHost=0/127.0.0.2  srcHost=0/127.0.0.2
   * Path header:   currINF=0  currHP=0  reserved=0  seg0Len=2  seg1Len=0  seg2Len=0
   *   info0=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, P=false, C=false, reserved=0, segID=39334, timestamp=1693213939}
   *     hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false, E=false, expiryTime=63, consIngress=1, consEgress=0, mac=167460372398325}
   *     hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false, E=false, expiryTime=63, consIngress=0, consEgress=2, mac=68417453420595}
   * UdpPseudoHeader{srcPort=100, dstPort=8080, length=19, checkSum=61925}
   * Payload: Hello scion
   */
  public static final byte[] PACKET_BYTES_RESPONSE = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 3, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 18, 127, 0, 0, 2,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
    0, 0, 32, 0, 0, 0, -103, -90, 100, -20, 100, -13, 0, 63, 0, 1,
    0, 0, -104, 77, -24, 2, -64, -11, 0, 63, 0, 0, 0, 2, 62, 57,
    -82, 1, -16, 51, 0, 100, 31, -112, 0, 19, -15, -27, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };

//  public static final byte[] PACKET_BYTE_RESPONSE = {
//    0, 0, 0, 1, 17, 24, 0, 23, 1, 51, 0, 0, 0, 1, -1, 0,
//    0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 18, 0, 0, 0, 0,
//    0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 2, 0, 0, 0, 0,
//    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 32, 0,
//    0, 0, 5, 236, 101, 82, 26, 132, 0, 63, 0, 1, 0, 0, 83, 112,
//    226, 175, 67, 19, 0, 63, 0, 0, 0, 2, 6, 247, 250, 61, 74, 4,
//    31, 144, 48, 57, 0, 23, 53, 131, 82, 101, 58, 32, 72, 101, 108, 108,
//    111, 32, 115, 99, 105, 111, 110,
//  };

  public static final byte[] PACKET_BYTES_RESPONSE_NEW = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 3, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 18, 127, 0, 0, 1,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
    0, 0, 32, 0, 0, 0, 209-256, 63, 101, 82, 62, 233-256, 0, 63, 0, 1,
    0, 0, 140-256, 169-256, 147-256, 252-256, 103, 151-256, 0, 63, 0, 0, 0, 2, 21, 234-256,
    128-256, 94, 83, 237-256, 31, 144-256, 172-256, 114, 0, 19, 69, 216-256, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };

  /**
   * Source: 1-ff00:0:110,127.0.0.2:12345
   * Destination: 1-ff00:0:112,[::1]:8080
   * Sending packet to first hop: 127.0.0.10:31004
   */
  public static final byte[] PACKET_BYTES_CLIENT_NEW = {
    0, 0, 0, 1, 17, 24, 0, 19, 1, 51, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 2, 0, 0, 32, 0,
    1, 0, 19, 244-256, 101, 82, 69, 218-256, 0, 63, 0, 0, 0, 2, 87, 221-256,
    204-256, 108, 235-256, 194-256, 0, 63, 0, 1, 0, 0, 222-256, 244-256, 79, 135-256, 50, 34,
    48, 57, 31, 144-256, 0, 19, 194-256, 16, 72, 101, 108, 108, 111, 32, 115, 99,
    105, 111, 110,
  };

  /**
   * Connected as: 1-ff00:0:112,[::1]:8080
   */
  public static final byte[] PACKET_BYTES_SERVER_NEW = {
    0, 0, 0, 1, 17, 24, 0, 19, 1, 51, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 18, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 2, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 32, 0,
    0, 0, 68, 41, 101, 82, 69, 218-256, 0, 63, 0, 1, 0, 0, 222-256, 244-256,
    79, 135-256, 50, 34, 0, 63, 0, 0, 0, 2, 87, 221-256, 204-256, 108, 235-256, 194-256,
    31, 144-256, 48, 57, 0, 19, 194-256, 16, 72, 101, 108, 108, 111, 32, 115, 99,
    105, 111, 110,
  };

  public static final byte[] PACKET_BYTES_CLIENT_E2E = {
    0, 0, 0, 1, 17, 24, 0, 19, 1, 51, 0, 0, 0, 1, -1, 0,
          0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1, 0, 0, 32, 0,
          1, 0, 188-256, 245-256, 101, 82, 87, 46, 0, 63, 0, 0, 0, 2, 189-256, 172-256,
          44, 70, 171-256, 162-256, 0, 63, 0, 1, 0, 0, 179-256, 83, 230-256, 10, 0, 182-256,
          173-256, 156-256, 31, 144-256, 0, 19, 68, 174-256, 72, 101, 108, 108, 111, 32, 115, 99,
          105, 111, 110,
  };

  public static final byte[] PACKET_BYTES_SERVER_E2E = {
0, 0, 0, 1, 17, 24, 0, 19, 1, 51, 0, 0, 0, 1, -1, 0,
          0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 18, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 32, 0,
          0, 0, 1, 89, 101, 82, 87, 46, 0, 63, 0, 1, 0, 0, 179-256, 83,
          230-256, 10, 0, 182-256, 0, 63, 0, 0, 0, 2, 189-256, 172-256, 44, 70, 171-256, 162-256,
          31, 144-256, 173-256, 156-256, 0, 19, 68, 174-256, 72, 101, 108, 108, 111, 32, 115, 99,
          105, 111, 110,
  };

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
   * Common Header:   VER=0  TrafficClass=0  FlowID=1  NextHdr=17  HdrLen=21/84  PayloadLen=19  PathType=1  DT=0  DL=3  ST=0  SL=0  RSV=0
   * Address Header:   dstIsdAs=1-ff00:0:112  srcIsdAs=1-ff00:0:110  dstHost=0/::1  srcHost=0/127.0.0.2
   * Path header:   currINF=0  currHP=0  reserved=0  seg0Len=2  seg1Len=0  seg2Len=0
   *   info0=InfoField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, P=false, C=true, reserved=0, segID=24449, timestamp=1694521570}
   *     hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false, E=false, expiryTime=63, consIngress=0, consEgress=2, mac=236420714296865}
   *     hop=HopField{r0=false, r1=false, r2=false, r3=false, r4=false, r5=false, r6=false, I=false, E=false, expiryTime=63, consIngress=1, consEgress=0, mac=150919809188202}
   * UdpPseudoHeader{srcPort=100, dstPort=8080, length=19, checkSum=61925}
   * Payload: Hello scion
   */
  public static final byte[] PACKET_BYTES_2 = {
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
}
