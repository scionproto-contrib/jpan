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

package org.scion;

import java.net.*;
import java.nio.ByteBuffer;
import org.scion.internal.ScionHeaderParser;

class ScionPacketHelper {
  // TODO move into DatagramChannel?

  public static void getUserData(ByteBuffer buffer, ByteBuffer userBuffer) {
    ScionHeaderParser.readUserData(buffer, userBuffer);
  }

  public static ScionSocketAddress getRemoteAddressAndPath(
      ByteBuffer buffer, InetSocketAddress firstHopAddress) {
    return ScionHeaderParser.readRemoteSocketAddress(buffer, firstHopAddress);
  }

  private ScionPacketHelper() {}

  public static void writeHeader(
      ByteBuffer data,
      InetSocketAddress srcSocketAddress,
      ScionSocketAddress dstSocketAddress,
      int payloadLength) {
    // TODO request new path after a while? Yes! respect path expiry! -> Do that in ScionService!

    long srcIA = dstSocketAddress.getPath().getSourceIsdAs();
    long dstIA = dstSocketAddress.getIsdAs();
    int srcPort = srcSocketAddress.getPort();
    int dstPort = dstSocketAddress.getPort();
    InetAddress srcAddress = srcSocketAddress.getAddress();
    InetAddress dstAddress = dstSocketAddress.getAddress();

    byte[] path = dstSocketAddress.getPath().getRawPath();
    ScionHeaderParser.write(
        data, payloadLength, path.length, srcIA, srcAddress, dstIA, dstAddress);
    ScionHeaderParser.writePath(data, path);
    ScionHeaderParser.writeUdpOverlayHeader(data, payloadLength, srcPort, dstPort);
  }
}
