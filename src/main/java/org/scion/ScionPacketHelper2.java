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

class ScionPacketHelper2 {
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

  private ScionPacketHelper2() { }

  public static int writeHeader(byte[] data, InetSocketAddress srcAddress, ScionSocketAddress dstAddress,
                         int payloadLength) throws IOException {

    // synchronized because we use `buffer`
    // TODO request new path after a while? Yes! respect path expiry! -> Do that in ScionService!

    PathState pathState;
    if (dstAddress.getPath().getRawPath() != null) {
      pathState = ScionPacketHelper2.PathState.RCV_PATH;
    } else {//if dstAddress.getPath(){ // TODO/
      pathState = PathState.NO_PATH;
    }

    long srcIA = ScionService.defaultService().getLocalIsdAs();
    // TODO ? srcIA = dstAddress.getPath().getSourceIsdAs();
    long dstIA = dstAddress.getIsdAs();
    int srcPort = srcAddress.getPort();
    int dstPort = dstAddress.getPort();

    // TODO use local field Datagram Packer?!
    int offset = 0;

    switch (pathState) {
      case NO_PATH:
      {
//        if (srcIA == 0) {
//          srcIA = ScionService.defaultService().getLocalIsdAs();
//        }
        ScionPath scionPath = dstAddress.getPath();
        Daemon.Path path;
        if (scionPath != null) {
          path = scionPath.getPathInternal();
          dstIA = scionPath.getDestinationIsdAs();
        } else {
//          if (srcIA == 0 || dstIA == 0) {
//            throw new IllegalStateException("srcIA/dstIA not set!"); // TODO fix / remove
//          }
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

        offset =
                ScionHeaderParser.write(
                        data,
                        offset,
                        payloadLength,
                        path.getRaw().size(),
                        srcIA, srcAddress, dstIA, dstAddress);
        offset = PathHeaderScionParser.writePath(data, offset, path.getRaw());
        offset = OverlayHeader.write2(data, offset, payloadLength, srcPort, dstPort);
        break;
      }
      case RCV_PATH:
      {
        byte[] path = dstAddress.getPath().getRawPath();
        offset =
                ScionHeaderParser.write(
                        data,
                        offset,
                        payloadLength,
                        path.length,
                        srcIA, srcAddress, dstIA, dstAddress);
        offset = PathHeaderScionParser.writePath(data, offset, path);
        offset = OverlayHeader.write2(data, offset, payloadLength, srcPort, dstPort);
        break;
      }
      case SEND_PATH:
      {
        Daemon.Path path = dstAddress.getPath().getPathInternal();
        offset =
                ScionHeaderParser.write(
                        data,
                        offset,
                        payloadLength,
                        path.getRaw().size(),
                        srcIA, srcAddress, dstIA, dstAddress);
        offset = PathHeaderScionParser.writePath(data, offset, path.getRaw());
        offset = OverlayHeader.write2(data, offset, payloadLength, srcPort, dstPort);
        break;
      }
      default:
        throw new IllegalStateException(pathState.name());
    }
    return offset;
  }

  // TODO move somewhere else or remove
  private void printPath(List<Daemon.Path> paths) {
    System.out.println("Paths found: " + paths.size());
    for (Daemon.Path path : paths) {
      System.out.println("Path:  exp=" + path.getExpiration() + "  mtu=" + path.getMtu());
      System.out.println("Path: interface = " + path.getInterface().getAddress().getAddress());
      int i = 0;
      for (Daemon.PathInterface pathIf : path.getInterfacesList()) {
        System.out.println(
                "    pathIf: "
                        + i
                        + ": "
                        + pathIf.getId()
                        + " "
                        + pathIf.getIsdAs()
                        + "  "
                        + ScionUtil.toStringIA(pathIf.getIsdAs()));
      }
      for (int hop : path.getInternalHopsList()) {
        System.out.println("    hop: " + i + ": " + hop);
      }
    }

    int selectedPathId = 0; // TODO allow configuration!
    Daemon.Path selectedPath = paths.get(selectedPathId);

    // first router
    String underlayAddressString = selectedPath.getInterface().getAddress().getAddress();
    InetAddress underlayAddress;
    int underlayPort;
    try {
      int splitIndex = underlayAddressString.indexOf(':');
      underlayAddress = InetAddress.getByName(underlayAddressString.substring(0, splitIndex));
      underlayPort = Integer.parseUnsignedInt(underlayAddressString.substring(splitIndex + 1));
    } catch (UnknownHostException e) {
      // TODO throw IOException?
      throw new RuntimeException(e);
    }
    System.out.println("IP-Underlay: " + underlayAddress + ":" + underlayPort);
  }

}