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

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.List;

import org.scion.internal.OverlayHeader;
import org.scion.internal.PathHeaderScionParser;
import org.scion.internal.ScionHeaderParser;
import org.scion.proto.daemon.Daemon;

class ScionPacketHelper {
  // TODO refactor to remove this code from public API
  //  - implement the interface in ScionDatagramChannel and ScionDatagramSocket
  //  - move Interface into "internal"
  //  OR: just make this helper-class non-public -> done.

  // TODO respect MTU; report MTU to user (?); test!!!

  public static void getUserData(ByteBuffer buffer, ByteBuffer userBuffer) {
    ScionHeaderParser.readUserData(buffer, userBuffer);
  }

  public static ScionSocketAddress getRemoteAddressAndPath(ByteBuffer buffer, InetSocketAddress firstHopAddress) {
    return ScionHeaderParser.readRemoteSocketAddress(buffer, firstHopAddress);
  }

  public enum PathState {
    NO_PATH,
    RCV_PATH,
    SEND_PATH
  }

  private ScionPacketHelper() { }

  public static void writeHeader(ByteBuffer data, InetSocketAddress srcSocketAddress,
                                ScionSocketAddress dstSocketAddress,
                                int payloadLength) throws IOException {
    // TODO request new path after a while? Yes! respect path expiry! -> Do that in ScionService!

    PathState pathState;
    if (dstSocketAddress.getPath().getRawPath() != null) {
      pathState = ScionPacketHelper.PathState.RCV_PATH;
    } else {//if dstAddress.getPath(){ // TODO/
      pathState = PathState.NO_PATH;
    }

    long srcIA = ScionService.defaultService().getLocalIsdAs();
    // TODO ? srcIA = dstAddress.getPath().getSourceIsdAs();
    long dstIA = dstSocketAddress.getIsdAs();
    int srcPort = srcSocketAddress.getPort();
    int dstPort = dstSocketAddress.getPort();
    InetAddress srcAddress = srcSocketAddress.getAddress();
    InetAddress dstAddress = dstSocketAddress.getAddress();

    // TODO cleanup
    switch (pathState) {
      case NO_PATH:
      {
        // TODO clean up
        ScionPath scionPath = dstSocketAddress.getPath();
        Daemon.Path path;
        if (scionPath != null) {
          path = scionPath.getPathInternal();
          dstIA = scionPath.getDestinationIsdAs();
        } else {
          // TODO there should already be a path, or not?
          List<Daemon.Path> paths = ScionService.defaultService().getPathList(srcIA, dstIA);
          if (paths.isEmpty()) {
            throw new IOException(
                    "No path found from "
                            + ScionUtil.toStringIA(srcIA)
                            + " to "
                            + ScionUtil.toStringIA(dstIA));
          }
          path = paths.get(0); // Just pick the first path for now. // TODO
        }

        ScionHeaderParser.write(
                        data,
                        payloadLength,
                        path.getRaw().size(),
                        srcIA, srcAddress, dstIA, dstAddress);
        PathHeaderScionParser.writePath(data, path.getRaw());
        OverlayHeader.write2(data, payloadLength, srcPort, dstPort);
        break;
      }
      case RCV_PATH:
      {
        byte[] path = dstSocketAddress.getPath().getRawPath();
                ScionHeaderParser.write(
                        data,
                        payloadLength,
                        path.length,
                        srcIA, srcAddress, dstIA, dstAddress);
        PathHeaderScionParser.writePath(data, path);
        OverlayHeader.write2(data, payloadLength, srcPort, dstPort);
        break;
      }
      case SEND_PATH:
      {
        Daemon.Path path = dstSocketAddress.getPath().getPathInternal();
                ScionHeaderParser.write(
                        data,
                        payloadLength,
                        path.getRaw().size(),
                        srcIA, srcAddress, dstIA, dstAddress);
        PathHeaderScionParser.writePath(data, path.getRaw());
        OverlayHeader.write2(data, payloadLength, srcPort, dstPort);
        break;
      }
      default:
        throw new IllegalStateException(pathState.name());
    }
  }
}