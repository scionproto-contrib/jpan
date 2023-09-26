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
public class ScionDatagramSocketOld implements Closeable {
  /*
   * Design:
   * We use delegation rather than inheritance. Inheritance is more difficult to handle if future version of
   * java.net.DatagramSocket get additional methods that need special SCION handling. These methods would
   * behave incorrectly until adapted, and, once adapted, would not compile anymore with earlier Java releases.
   */


  private final DatagramSocket socket;
  // TODO respect MTU; report MTU to user (?); test!!!
  // TODO use ByteBuffer to manage offset etc?
  private final byte[] buf = new byte[65535 - 28]; // -28 for 8 byte UDP + 20 byte IP header
  private final ScionHeader scionHeader = new ScionHeader();
  private final PathHeaderScion pathHeaderScion = new PathHeaderScion();
  private final PathHeaderOneHopPath pathHeaderOneHop = new PathHeaderOneHopPath();
  private final OverlayHeader overlayHeaderUdp = new OverlayHeader();
  private int underlayPort;
  private InetAddress underlayAddress;
  private PathState pathState = PathState.NO_PATH;
  // TODO provide ports etc?  Allow separate instances for different sockets?
  // TODO create lazily to prevent network connections before we create any actual DatagramSocket?
  private ScionPathService pathService;
  // TODO remove?
  private long srcIA;
  private long dstIA;
  private final Object closeLock = new Object();
  private boolean isClosed = false;

  private enum PathState {
    NO_PATH,
    RCV_PATH,
    SEND_PATH
  }

  public ScionDatagramSocketOld() throws SocketException {
    socket = new DatagramSocket();
    System.out.println(
            "Creating socket: " + socket.getLocalAddress() + " : " + socket.getLocalPort());
  }

  public ScionDatagramSocketOld(SocketAddress addr) throws SocketException {
    socket = new DatagramSocket(addr);
    System.out.println(
            "Creating socket with address: " + socket.getLocalAddress() + " : " + socket.getLocalPort());
  }

  public ScionDatagramSocketOld(int port) throws SocketException {
    this(port, (InetAddress)null);
  }

  public ScionDatagramSocketOld(int port, InetAddress addr) throws SocketException {
    this(new InetSocketAddress(addr, port));
  }

  @Deprecated // TODO This  is not how we should do it. Find IA automatically or require it via
  // constructor
  public void setDstIsdAs(String dstIA) {
    // TODO rename ParseIA to parseIA
    this.dstIA = ScionUtil.ParseIA(dstIA);
  }

  public synchronized void receive(DatagramPacket packet) throws IOException {
    // synchronized because we use `buffer`
    while (true) {
      // TODO reuse incoming packet
      DatagramPacket incoming = new DatagramPacket(buf, buf.length);
      System.out.println("waiting to receive: "); // TODO
      socket.receive(incoming);
      underlayPort = incoming.getPort();
      underlayAddress = incoming.getAddress();
      System.out.println("received: len=" + incoming.getLength()); // TODO
      if (readScionHeader(incoming, packet)) {
        break;
      }
    }
  }

  public synchronized void send(DatagramPacket packet) throws IOException {
    // synchronized because we use `buffer`
    // TODO request new path after a while?

    // TODO use local field Datagram Packer?!
    DatagramPacket outgoing = new DatagramPacket(buf, buf.length);
    int offset = 0;

    switch (pathState) {
      case NO_PATH:
      {
        // TODO request path from daemon
        if (pathService == null) {
          pathService = ScionPathService.create();
        }
        if (srcIA == 0) {
          srcIA = pathService.getLocalIsdAs();
        }
        System.out.println("Getting path from " + getSrcAddress() + " : " + socket.getLocalPort());
        System.out.println("               to " + packet.getAddress() + " : " + packet.getPort());
        if (srcIA == 0 || dstIA == 0) {
          throw new IllegalStateException("srcIA/dstIA not set!"); // TODO fix / remove
        }
        List<Daemon.Path> paths = pathService.getPathList(srcIA, dstIA);
        if (paths.isEmpty()) {
          throw new IOException(
                  "No path found from " + ScionUtil.toStringIA(srcIA) + " to " + ScionUtil.toStringIA(dstIA));
        }
        Daemon.Path path = paths.get(0); // Just pick the first path for now. // TODO

        scionHeader.setSrcIA(srcIA);
        scionHeader.setDstIA(dstIA);
        scionHeader.setSrcHostAddress(getSrcAddress());
        scionHeader.setDstHostAddress(packet.getAddress());
        setUnderlayAddress(path);

        offset =
                scionHeader.write(
                        outgoing.getData(),
                        offset,
                        packet.getLength(),
                        path.getRaw().size(),
                        Constants.PathTypes.SCION);
        offset = pathHeaderScion.writePath(outgoing.getData(), offset, path);
        offset = overlayHeaderUdp.write(outgoing.getData(), offset, packet.getLength(), socket.getLocalPort(), packet.getPort());
        pathState = PathState.SEND_PATH;
        break;
      }
      case RCV_PATH:
      {
        scionHeader.reverse();
        pathHeaderScion.reverse();
        overlayHeaderUdp.reverse();
        offset = writeScionHeader(outgoing, packet.getLength());
        pathState = PathState.SEND_PATH;
        // TODO !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!??????????????//????????????????????????
        underlayPort = 31012;
        break;
      }
      case SEND_PATH:
      {
        offset = writeScionHeader(outgoing, packet.getLength());
        break;
      }
      default:
        throw new IllegalStateException(pathState.name());
    }

    // TODO rename & split? writePayload() + sendPacket() ?
    sendScionPacket(offset, outgoing, packet);
    System.out.println(
            "Sending packet: "
                    + outgoing.getSocketAddress()
                    + " : "
                    + outgoing.getLength()
                    + "/"
                    + outgoing.getOffset()
                    + "/"
                    + outgoing.getData().length);

    socket.send(outgoing);
  }


  public void connect(InetAddress addr, int port) {
    // TODO set destination address
    throw new UnsupportedOperationException();
    // We do NOT call super.connect() here!
  }

  public void connect(SocketAddress addr) {
    // TODO set destination address
    throw new UnsupportedOperationException();
    // We do NOT call super.connect() here!
  }

  public void bind(SocketAddress addr) {
    // TODO set destination address
    System.err.println("FIXME:  ScionDatagramSocket.bind()");
    // We do NOT call super.bind() here!
  }



  private InetAddress getSrcAddress() throws UnknownHostException {
    InetAddress addr  = socket.getLocalAddress();
    if (addr instanceof Inet4Address) {
      byte[] bytes = addr.getAddress();
      for (int i = 0; i < bytes.length; i++) {
        if (bytes[i] != 0) {
          return addr;
        }
      }
      return InetAddress.getLocalHost();
    }
    return addr;
  }

  private void printHeaders() {
    System.out.println("Scion header: " + scionHeader);
    if (scionHeader.pathType() == Constants.PathTypes.SCION) {
      System.out.println("Path header: " + pathHeaderScion);
    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
      System.out.println("OneHop header: " + pathHeaderOneHop);
    } else {
      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
    }
  }

  private boolean readScionHeader(DatagramPacket p, DatagramPacket userPacket) throws IOException {
    // TODO See which checks we have to perform from the list in the book p118 (BR ingress)
    byte[] data = p.getData();
    int offset = scionHeader.read(data, 0);
    if (scionHeader.getDT() != 0) {
      System.out.println(
              "PACKET DROPPED: service address="
                      + scionHeader.getDstHostAddress()
                      + "  DT="
                      + scionHeader.getDT());
      return false;
    }
    if (p.getLength() > 1000) {
      System.out.println(
              "PACKET DROPPED: service address="
                      + scionHeader.getDstHostAddress()
                      + "  length="
                      + p.getLength());
      return false;
    }

    if (scionHeader.pathType() == Constants.PathTypes.SCION) {
      offset = pathHeaderScion.read(data, offset);
    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
      offset = pathHeaderOneHop.read(data, offset);
      return false;
    } else {
      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
    }

    // Pseudo header
    if (scionHeader.nextHeader() == Constants.HdrTypes.UDP) {
      // TODO ! How can we properly filter out unwanted packets???
      // These are probably answers to polling/keep-alive packets sent from the dispatcher, but the
      // dispatcher
      // can´t receive them due to pert forwarding to 40041 so the dispatcher keeps requesting them.
      if (!scionHeader.getDstHostAddress().isLoopbackAddress()) {
        System.out.println("PACKET DROPPED: dstHost=" + scionHeader.getDstHostAddress());
        // return false;
      }
      printHeaders();
      offset = overlayHeaderUdp.read(data, offset);
      System.out.println(overlayHeaderUdp);
    } else if (scionHeader.nextHeader() == Constants.HdrTypes.SCMP) {
      System.out.println("Packet: DROPPED: SCMP");
      return false;
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
        return false;
      }
      return false;
    } else {
      System.out.println("Packet: DROPPED unknown: " + scionHeader.nextHeader().name());
      return false;
    }

    // build packet
    int length = (p.getLength() - offset);
    System.arraycopy(p.getData(), offset, userPacket.getData(), userPacket.getOffset(), length);
    userPacket.setLength(length);
    userPacket.setPort(overlayHeaderUdp.getSrcPort());
    userPacket.setAddress(scionHeader.getSrcHostAddress(data));
    pathState = PathState.RCV_PATH;
    return true;
  }

  private int writeScionHeader(DatagramPacket p, int userPacketLength) {
    // TODO reset offset ?!?!?!?
    if (p.getOffset() != 0) {
      throw new IllegalStateException("of=" + p.getOffset());
    }
    int offset = p.getOffset();

    // System.out.println("Sending: dst=" + userPacket.getAddress() + " / src=" +
    // socket.getLocalAddress());
    offset =
            scionHeader.write(
                    p.getData(), offset, userPacketLength, pathHeaderScion.length(), Constants.PathTypes.SCION);
    offset = pathHeaderScion.write(p.getData(), offset);
    offset = overlayHeaderUdp.write(p.getData(), offset, userPacketLength);
    return offset;
  }

  private void sendScionPacket(int offset, DatagramPacket p, DatagramPacket userPacket) {
    // build packet
    System.arraycopy(
            userPacket.getData(), userPacket.getOffset(), p.getData(), offset, userPacket.getLength());
    p.setLength(offset + userPacket.getLength());
    //    System.out.println(
    //            "length: " + offset + " + " + userPacket.getLength() + "   vs  " +
    // p.getData().length + "  -> " + p.getLength());

    // First hop
    // TODO ?!?!?!?!?!
    p.setPort(underlayPort);
    p.setAddress(underlayAddress);
    pathState = PathState.RCV_PATH;
    System.out.println("Sending to underlay: " + underlayAddress + " : " + underlayPort);
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
    synchronized (closeLock) {
      socket.close();
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

  public SocketAddress getLocalSocketAddress() {
    return socket.getLocalSocketAddress();
  }

  public SocketAddress getRemoteSocketAddress() {
    return socket.getRemoteSocketAddress();
  }

  public InetAddress getLocalAddress() {
    return socket.getLocalAddress();
  }

  public int getLocalPort() {
    return socket.getLocalPort();
  }

  public int getPort() {
    return socket.getPort();
  }
}
