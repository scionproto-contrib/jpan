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
import org.scion.internal.OverlayHeader;
import org.scion.internal.PathHeaderScionParser;
import org.scion.internal.ScionHeaderParser;
import org.scion.proto.daemon.Daemon;

class ScionPacketHelper {
  // TODO move into DatagramChannel?
  // TODO respect MTU; report MTU to user (?); test!!!

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

    boolean isClient = dstSocketAddress.getPath().getRawPath() == null;

    long srcIA = ScionService.defaultService().getLocalIsdAs();
    // TODO ? srcIA = dstAddress.getPath().getSourceIsdAs();
    long dstIA = dstSocketAddress.getIsdAs();
    int srcPort = srcSocketAddress.getPort();
    int dstPort = dstSocketAddress.getPort();
    InetAddress srcAddress = srcSocketAddress.getAddress();
    InetAddress dstAddress = dstSocketAddress.getAddress();

    if (isClient) {
      Daemon.Path path = dstSocketAddress.getPath().getPathInternal();
      ScionHeaderParser.write(
          data, payloadLength, path.getRaw().size(), srcIA, srcAddress, dstIA, dstAddress);
      PathHeaderScionParser.writePath(data, path.getRaw());
    } else {
      byte[] path = dstSocketAddress.getPath().getRawPath();
      ScionHeaderParser.write(
          data, payloadLength, path.length, srcIA, srcAddress, dstIA, dstAddress);
      PathHeaderScionParser.writePath(data, path);
    }
    OverlayHeader.write2(data, payloadLength, srcPort, dstPort);
  }
}
