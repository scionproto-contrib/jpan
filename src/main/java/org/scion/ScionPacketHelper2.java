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

import org.scion.internal.ScionHeaderParser;
import org.scion.proto.daemon.Daemon;

class ScionPacketHelper2 {
  // TODO refactor to remove this code from public API
  //  - put logic into a static interface methods
  //  - put the state into a ScionSessionContext; move this class into the interface.
  //  - implement the interface in ScionDatagramChannel and ScionDatagramSocket
  //  - move Interface into "internal"
  //  OR: just make this helper-class non-public -> done.

  /*
   * Design:
   * We use delegation rather than inheritance. Inheritance is more difficult to handle if future version of
   * java.net.DatagramSocket get additional methods that need special SCION handling. These methods would
   * behave incorrectly until adapted, and, once adapted, would not compile anymore with earlier Java releases.
   */


  // TODO respect MTU; report MTU to user (?); test!!!
  // TODO use ByteBuffer to manage offset etc?
  private final byte[] buf = new byte[65535 - 28]; // -28 for 8 byte UDP + 20 byte IP header
//  private InetSocketAddress underlayAddress;
//  // TODO remove?
//  private long srcIA;
//  private long dstIA;
  private PathState pathState;
  // TODO make final
  private ScionSocketAddress owner;
  private ScionSocketAddress sourceAddress;

  public ScionAddress getSourceAddress() throws IOException {
    return sourceAddress.getScionAddress();
  }

  public static void getUserData(ByteBuffer buffer, ByteBuffer userBuffer) {
    ScionHeaderParser.readUserData(buffer, userBuffer);
  }

  public ScionSocketAddress getRemoteAddressAndPath(ByteBuffer buffer, InetSocketAddress firstHopAddress) {
    return ScionHeaderParser.readRemoteSocketAddress(buffer, firstHopAddress);
  }

  public enum PathState {
    NO_PATH,
    RCV_PATH,
    SEND_PATH
  }

  public ScionPacketHelper2(ScionSocketAddress owner) {
    this.owner = owner;
  }

//  public ScionSocketAddress getReceivedSrcAddress() {
//    if (owner == null) {
//      // TODO this is extremely slow, find another solution! -> getHostName()
//      owner =
//          ScionSocketAddress.create(
//              this, srcIA, scionHeader.getSrcHostName(), overlayHeaderUdp.getSrcPort());
//    }
//    return owner;
//  }
//
//  public InetSocketAddress getReceivedDstAddress() throws IOException {
//    return new InetSocketAddress(scionHeader.getDstHostAddress(), overlayHeaderUdp.getDstPort());
//  }
//
//  // TODO deprecate this?
//  public InetSocketAddress getFirstHopAddress() {
//    // TODO System.out.println(Thread.currentThread().getName() + " getting underlay: " + underlayAddress + "    " + this);
//    return underlayAddress;
//  }
//
//  public int getPayloadLength() {
//    return scionHeader.getPayloadLength() - overlayHeaderUdp.length();
//  }
//
//  public int writeHeader(byte[] data, InetSocketAddress srcAddress, ScionSocketAddress dstAddress,
//                         int payloadLength) throws IOException {
//
//    // synchronized because we use `buffer`
//    // TODO request new path after a while?
//
//    // TODO use local field Datagram Packer?!
//    int offset = 0;
//
//    switch (pathState) {
//      case NO_PATH:
//      {
//        if (srcIA == 0) {
//          srcIA = getPathService().getLocalIsdAs();
//        }
//        ScionPath scionPath = dstAddress.getPath();
//        Daemon.Path path;
//        if (scionPath != null) {
//          path = scionPath.getPathInternal();
//          dstIA = scionPath.getDestinationCode();
//        } else {
//          if (srcIA == 0 || dstIA == 0) {
//            throw new IllegalStateException("srcIA/dstIA not set!"); // TODO fix / remove
//          }
//          List<Daemon.Path> paths = getPathService().getPathList(srcIA, dstIA);
//          if (paths.isEmpty()) {
//            throw new IOException(
//                "No path found from "
//                    + ScionUtil.toStringIA(srcIA)
//                    + " to "
//                    + ScionUtil.toStringIA(dstIA));
//          }
//          path = paths.get(0); // Just pick the first path for now. // TODO
//        }
//
//        scionHeader.setSrcIA(srcIA);
//        scionHeader.setDstIA(dstIA);
//        scionHeader.setSrcHostAddress(srcAddress.getAddress().getAddress());
//        scionHeader.setDstHostAddress(dstAddress.getAddress().getAddress());
//        setUnderlayAddress(path);
//        int srcPort = srcAddress.getPort();
//        int dstPort = dstAddress.getPort();
//
//        offset =
//                scionHeader.write(
//                        data,
//                        offset,
//                        payloadLength,
//                        path.getRaw().size(),
//                        Constants.PathTypes.SCION);
//        offset = pathHeaderScion.writePath(data, offset, path);
//        offset = overlayHeaderUdp.write(data, offset, payloadLength, srcPort, dstPort);
//
//   // TODO     pathState = PathState.SEND_PATH;
//        break;
//      }
//      case RCV_PATH:
//      {
//        scionHeader.reverse();
//        pathHeaderScion.reverse();
//        overlayHeaderUdp.reverse();
//        offset = writeScionHeader(data, offset, payloadLength);
//        break;
//      }
//      case SEND_PATH:
//      {
//        offset = writeScionHeader(data, offset, payloadLength);
//        break;
//      }
//      default:
//        throw new IllegalStateException(pathState.name());
//    }
//    return offset;
//  }

  @Deprecated // TODO this should probably all be done in ScionAddress
  private ScionService getPathService() {
    return ScionService.defaultService();
  }

//  @Override
//  public String toString() {
//    StringBuilder sb = new StringBuilder();
//    sb.append(scionHeader).append("\n");
//    if (scionHeader.pathType() == Constants.PathTypes.SCION) {
//      sb.append(pathHeaderScion).append("\n");
//    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
//      sb.append(pathHeaderOneHop).append("\n");
//    } else {
//      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
//    }
//    return sb.toString();
//  }

//  public int readScionHeader(byte[] data) throws IOException {
//    // TODO See which checks we have to perform from the list in the book p118 (BR ingress)
//    int headerOffset = 0;
//    int offset = scionHeader.read(data, headerOffset);
//    if (scionHeader.getDT() != 0) {
//      System.out.println(
//              "PACKET DROPPED: service address="
//                      + scionHeader.getDstHostAddress()
//                      + "  DT="
//                      + scionHeader.getDT());
//      return -1;
//    }
//
//    if (scionHeader.pathType() == Constants.PathTypes.SCION) {
//      offset = pathHeaderScion.read(data, offset);
//    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
//      offset = pathHeaderOneHop.read(data, offset);
//      return -1;
//    } else {
//      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
//    }
//
//    // Pseudo header
//    if (scionHeader.nextHeader() == Constants.HdrTypes.UDP) {
//      // TODO ! How can we properly filter out unwanted packets???
//      // These are probably answers to polling/keep-alive packets sent from the dispatcher, but the
//      // dispatcher
//      // can´t receive them due to pert forwarding to 40041 so the dispatcher keeps requesting them.
////      if (!scionHeader.getDstHostAddress().isLoopbackAddress()) {
////        System.out.println("PACKET DROPPED: dstHost=" + scionHeader.getDstHostAddress());
////        // return false;
////      }
//
//      offset = overlayHeaderUdp.read(data, offset);
//
//      // Create a copy for returning data
//      byte[] copyHeader = new byte[offset];
//      System.arraycopy(data, headerOffset, copyHeader, 0, offset - headerOffset);
//      // TODO use copied header
//
//    } else if (scionHeader.nextHeader() == Constants.HdrTypes.SCMP) {
//      System.out.println("Packet: DROPPED: SCMP");
//      return -1;
//    } else if (scionHeader.nextHeader() == Constants.HdrTypes.END_TO_END) {
//      System.out.println("Packet EndToEnd");
//      ScionEndToEndExtensionHeader e2eHeader = new ScionEndToEndExtensionHeader();
//      offset = e2eHeader.read(data, offset);
//      if (e2eHeader.nextHdr() == Constants.HdrTypes.SCMP) {
//        ScionSCMPHeader scmpHdr = new ScionSCMPHeader();
//        offset = scmpHdr.read(data, offset);
//        System.out.println("SCMP:");
//        System.out.println("    type: " + scmpHdr.getType().getText());
//        System.out.println("    code: " + scmpHdr.getCode());
//      } else {
//        System.out.println("Packet: DROPPED not implemented: " + scionHeader.nextHeader().name());
//        return -1;
//      }
//      return -1;
//    } else {
//      System.out.println("Packet: DROPPED unknown: " + scionHeader.nextHeader().name());
//      return -1;
//    }
//    return offset;
//  }
//
//  private int writeScionHeader(byte[] data, int offset2, int userPacketLength) {
//    // TODO reset offset ?!?!?!?
//    if (offset2 != 0) {
//      throw new IllegalStateException("of=" + offset2);
//    }
//    int offset =
//            scionHeader.write(
//                    data, offset2, userPacketLength, pathHeaderScion.length(), Constants.PathTypes.SCION);
//    offset = pathHeaderScion.write(data, offset);
//    offset = overlayHeaderUdp.write(data, offset, userPacketLength);
//    return offset;
//  }


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

//  private void setUnderlayAddress(Daemon.Path path) {
//    // first router
//    String underlayAddressString = path.getInterface().getAddress().getAddress();
//    try {
//      int splitIndex = underlayAddressString.indexOf(':');
//      InetAddress addr = InetAddress.getByName(underlayAddressString.substring(0, splitIndex));
//      int port = Integer.parseUnsignedInt(underlayAddressString.substring(splitIndex + 1));
//      underlayAddress = new InetSocketAddress(addr, port);
//    } catch (UnknownHostException e) {
//      // TODO throw IOException?
//      throw new RuntimeException(e);
//    }
//  }
//
//  public void setUnderlayAddress(InetSocketAddress underlayAddress) {
//    this.underlayAddress = underlayAddress;
//  }
}