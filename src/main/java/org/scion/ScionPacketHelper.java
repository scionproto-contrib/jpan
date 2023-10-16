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

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.List;
import org.scion.internal.Constants;
import org.scion.internal.OverlayHeader;
import org.scion.internal.PathHeaderOneHopPath;
import org.scion.internal.PathHeaderScion;
import org.scion.internal.ScionEndToEndExtensionHeader;
import org.scion.internal.ScionHeader;
import org.scion.internal.ScionSCMPHeader;
import org.scion.proto.daemon.Daemon;

/**
 * We are extending DatagramSocket as recommended here:
 * https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/DatagramSocket.html#setDatagramSocketImplFactory(java.net.DatagramSocketImplFactory)
 */
class ScionPacketHelper implements Closeable {
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
  private final ScionHeader scionHeader = new ScionHeader();
  private final PathHeaderScion pathHeaderScion = new PathHeaderScion();
  private final PathHeaderOneHopPath pathHeaderOneHop = new PathHeaderOneHopPath();
  private final OverlayHeader overlayHeaderUdp = new OverlayHeader();
  private int underlayPort;
  private InetAddress underlayAddress;
  // TODO provide ports etc?  Allow separate instances for different sockets?
  // TODO create lazily to prevent network connections before we create any actual DatagramSocket?
  private ScionPathService pathService;
  // TODO remove?
  private long srcIA;
  private long dstIA;
  private final Object closeLock = new Object();
  private boolean isClosed = false;

  public InetAddress getSourceAddress() throws IOException {
    return scionHeader.getSrcHostAddress();
  }

//  public ScionPath getLastIncomingPath(InetSocketAddress remoteAddress) throws IOException {
//    if (remoteAddress != null && remoteAddress.equals(scionHeader.getSrcHostAddress()) {
//      return
//    }
//  }

  public enum PathState {
    NO_PATH,
    RCV_PATH,
    SEND_PATH
  }

  public ScionPacketHelper() {

  }

  public ScionSocketAddress getReceivedSrcAddress() throws IOException {
    // TODO this is extremely slow, find another solution! -> getHostName()
    return ScionSocketAddress.create(this, srcIA, scionHeader.getSrcHostName(), overlayHeaderUdp.getSrcPort());
  }

  public InetSocketAddress getReceivedDstAddress() throws IOException {
    return new InetSocketAddress(scionHeader.getDstHostAddress(), overlayHeaderUdp.getDstPort());
  }

  // TODO deprecate this?
  public InetSocketAddress getFirstHopAddress() {
    return new InetSocketAddress(underlayAddress, underlayPort);
  }

  public InetSocketAddress getFirstHopAddress(ScionPath path) {
    Daemon.Path internalPath = path.getPathInternal();
    String underlayAddressString = internalPath.getInterface().getAddress().getAddress();
    try {
      int splitIndex = underlayAddressString.indexOf(':');
      InetAddress underlayAddress = InetAddress.getByName(underlayAddressString.substring(0, splitIndex));
      int underlayPort = Integer.parseUnsignedInt(underlayAddressString.substring(splitIndex + 1));
      System.out.println("Getting IP-underlay=" + underlayAddress + "   " + underlayPort);
      return new InetSocketAddress(underlayAddress, underlayPort);
    } catch (UnknownHostException e) {
      // TODO throw IOException?
      throw new RuntimeException(e);
    }
  }

  public int getPayloadLength() {
    return scionHeader.getPayloadLength() - overlayHeaderUdp.length();
  }

  public ScionPath getDefaultPath(InetSocketAddress destinationAddress) {
    // TODO this is bad, this is a GETTER that changes state!
    if (srcIA == 0) {
      srcIA = getPathService().getLocalIsdAs();
    }
    if (dstIA == 0) {
      dstIA = pathService.getScionAddress(destinationAddress.getHostString()).getIsdAs();
    }
    return getPathService().getPath(srcIA, dstIA);
  }

  public ScionPath getDefaultPath(String hostName) {
    // TODO this is bad, this is a GETTER that changes state!
    if (srcIA == 0) {
      srcIA = getPathService().getLocalIsdAs();
    }
    if (dstIA == 0) {
      dstIA = pathService.getScionAddress(hostName).getIsdAs();
    }
    return getPathService().getPath(srcIA, dstIA);
  }

  @Deprecated // TODO This  is not how we should do it. Find IA automatically or require it via
  //                 constructor
  public void setDstIsdAs(String dstIA) {
    // TODO rename ParseIA to parseIA
    this.dstIA = ScionUtil.ParseIA(dstIA);
  }

  public int writeHeader(byte[] data, PathState pathState, byte[] srcAddress, int srcPort,
                         byte[] dstAddress, int dstPort, int payloadLength) throws IOException {
    // synchronized because we use `buffer`
    // TODO request new path after a while?

    // TODO use local field Datagram Packer?!
    int offset = 0;

    switch (pathState) {
      case NO_PATH:
      {
        if (srcIA == 0) {
          srcIA = getPathService().getLocalIsdAs();
        }
        if (srcIA == 0 || dstIA == 0) {
          throw new IllegalStateException("srcIA/dstIA not set!"); // TODO fix / remove
        }
        List<Daemon.Path> paths = getPathService().getPathList(srcIA, dstIA);
        if (paths.isEmpty()) {
          throw new IOException(
                  "No path found from " + ScionUtil.toStringIA(srcIA) + " to " + ScionUtil.toStringIA(dstIA));
        }
        Daemon.Path path = paths.get(0); // Just pick the first path for now. // TODO

        scionHeader.setSrcIA(srcIA);
        scionHeader.setDstIA(dstIA);
        scionHeader.setSrcHostAddress(srcAddress);
        scionHeader.setDstHostAddress(dstAddress);
        setUnderlayAddress(path);

        offset =
                scionHeader.write(
                        data,
                        offset,
                        payloadLength,
                        path.getRaw().size(),
                        Constants.PathTypes.SCION);
        offset = pathHeaderScion.writePath(data, offset, path);
        offset = overlayHeaderUdp.write(data, offset, payloadLength, srcPort, dstPort);
        break;
      }
      case RCV_PATH:
      {
        scionHeader.reverse();
        pathHeaderScion.reverse();
        overlayHeaderUdp.reverse();
        offset = writeScionHeader(data, offset, payloadLength);
        // TODO !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!??????????????//????????????????????????
        underlayPort = 31012;
        break;
      }
      case SEND_PATH:
      {
        offset = writeScionHeader(data, offset, payloadLength);
        break;
      }
      default:
        throw new IllegalStateException(pathState.name());
    }
    return offset;
  }

  private ScionPathService getPathService() {
    if (pathService == null) {
      pathService = ScionPathService.create();
    }
    return pathService;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(scionHeader).append("\n");
    if (scionHeader.pathType() == Constants.PathTypes.SCION) {
      sb.append(pathHeaderScion).append("\n");
    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
      sb.append(pathHeaderOneHop).append("\n");
    } else {
      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
    }
    return sb.toString();
  }

  public int readScionHeader(byte[] data) throws IOException {
    // TODO See which checks we have to perform from the list in the book p118 (BR ingress)
    int headerOffset = 0;
    int offset = scionHeader.read(data, headerOffset);
    if (scionHeader.getDT() != 0) {
      System.out.println(
              "PACKET DROPPED: service address="
                      + scionHeader.getDstHostAddress()
                      + "  DT="
                      + scionHeader.getDT());
      return -1;
    }

    if (scionHeader.pathType() == Constants.PathTypes.SCION) {
      offset = pathHeaderScion.read(data, offset);
    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
      offset = pathHeaderOneHop.read(data, offset);
      return -1;
    } else {
      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
    }

    // Pseudo header
    if (scionHeader.nextHeader() == Constants.HdrTypes.UDP) {
      // TODO ! How can we properly filter out unwanted packets???
      // These are probably answers to polling/keep-alive packets sent from the dispatcher, but the
      // dispatcher
      // can´t receive them due to pert forwarding to 40041 so the dispatcher keeps requesting them.
//      if (!scionHeader.getDstHostAddress().isLoopbackAddress()) {
//        System.out.println("PACKET DROPPED: dstHost=" + scionHeader.getDstHostAddress());
//        // return false;
//      }

      offset = overlayHeaderUdp.read(data, offset);

      // Create a copy for returning data
      byte[] copyHeader = new byte[offset];
      System.arraycopy(data, headerOffset, copyHeader, 0, offset - headerOffset);
      // TODO use copied header

    } else if (scionHeader.nextHeader() == Constants.HdrTypes.SCMP) {
      System.out.println("Packet: DROPPED: SCMP");
      return -1;
    } else if (scionHeader.nextHeader() == Constants.HdrTypes.END_TO_END) {
      System.out.println("Packet EndToEnd");
      ScionEndToEndExtensionHeader e2eHeader = new ScionEndToEndExtensionHeader();
      offset = e2eHeader.read(data, offset);
      if (e2eHeader.nextHdr() == Constants.HdrTypes.SCMP) {
        ScionSCMPHeader scmpHdr = new ScionSCMPHeader();
        offset = scmpHdr.read(data, offset);
        System.out.println("SCMP:");
        System.out.println("    type: " + scmpHdr.getType().getText());
        System.out.println("    code: " + scmpHdr.getCode());
      } else {
        System.out.println("Packet: DROPPED not implemented: " + scionHeader.nextHeader().name());
        return -1;
      }
      return -1;
    } else {
      System.out.println("Packet: DROPPED unknown: " + scionHeader.nextHeader().name());
      return -1;
    }
    return offset;
  }

  private int writeScionHeader(byte[] data, int offset2, int userPacketLength) {
    // TODO reset offset ?!?!?!?
    if (offset2 != 0) {
      throw new IllegalStateException("of=" + offset2);
    }
    // System.out.println("Sending: dst=" + userPacket.getAddress() + " / src=" +
    // socket.getLocalAddress());
    int offset =
            scionHeader.write(
                    data, offset2, userPacketLength, pathHeaderScion.length(), Constants.PathTypes.SCION);
    offset = pathHeaderScion.write(data, offset);
    offset = overlayHeaderUdp.write(data, offset, userPacketLength);
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
    System.out.println("IP-underlay=" + underlayAddress + "   " + underlayPort);
  }

  private void setUnderlayAddress(Daemon.Path path) {
    // first router
    String underlayAddressString = path.getInterface().getAddress().getAddress();
    // InetAddress underlayAddress;
    // int underlayPort;
    try {
      int splitIndex = underlayAddressString.indexOf(':');
      underlayAddress = InetAddress.getByName(underlayAddressString.substring(0, splitIndex));
      underlayPort = Integer.parseUnsignedInt(underlayAddressString.substring(splitIndex + 1));
    } catch (UnknownHostException e) {
      // TODO throw IOException?
      throw new RuntimeException(e);
    }
    System.out.println("Setting IP-underlay=" + underlayAddress + "   " + underlayPort);
  }

  @Override
  public void close() {
    // TODO remove all this and make PathService a singleton.
    synchronized (closeLock) {
      if (pathService != null) {
        try {
          pathService.close();
          pathService = null;
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  public boolean isClosed() {
    synchronized (closeLock) {
      return isClosed;
    }
  }
}