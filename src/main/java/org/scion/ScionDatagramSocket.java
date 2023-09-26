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
import org.scion.internal.ScionHeader;
import org.scion.proto.daemon.Daemon;

/**
 * We are extending DatagramSocket as recommended here:
 * https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/DatagramSocket.html#setDatagramSocketImplFactory(java.net.DatagramSocketImplFactory)
 */
public class ScionDatagramSocket implements Closeable {
  /*
   * Design:
   * We use delegation rather than inheritance. Inheritance is more difficult to handle if future version of
   * java.net.DatagramSocket get additional methods that need special SCION handling. These methods would
   * behave incorrectly until adapted, and, once adapted, would not compile anymore with earlier Java releases.
   */


  private final DatagramSocket socket;
  private final ScionPacketHelper helper = new ScionPacketHelper();
  private InetSocketAddress localAddress = null;

  // TODO respect MTU; report MTU to user (?); test!!!
  // TODO use ByteBuffer to manage offset etc?
  private final byte[] bytes = new byte[65535 - 28]; // -28 for 8 byte UDP + 20 byte IP header
  private final ScionHeader scionHeader = new ScionHeader();
  private final PathHeaderScion pathHeaderScion = new PathHeaderScion();
  private final PathHeaderOneHopPath pathHeaderOneHop = new PathHeaderOneHopPath();
  private final OverlayHeader overlayHeaderUdp = new OverlayHeader();
  private int underlayPort;
  private InetAddress underlayAddress;
  private ScionPacketHelper.PathState pathState = ScionPacketHelper.PathState.NO_PATH;
  // TODO provide ports etc?  Allow separate instances for different sockets?
  // TODO create lazily to prevent network connections before we create any actual DatagramSocket?
  private ScionPathService pathService;
  // TODO remove?
  private final Object closeLock = new Object();
  private boolean isClosed = false;


  public ScionDatagramSocket() throws SocketException {
    socket = new DatagramSocket();
    System.out.println(
            "Creating socket: " + socket.getLocalAddress() + " : " + socket.getLocalPort());
  }

  public ScionDatagramSocket(SocketAddress addr) throws SocketException {
    socket = new DatagramSocket(addr);
    System.out.println(
            "Creating socket with address: " + socket.getLocalAddress() + " : " + socket.getLocalPort());
  }

  public ScionDatagramSocket(int port) throws SocketException {
    this(port, (InetAddress)null);
  }

  public ScionDatagramSocket(int port, InetAddress addr) throws SocketException {
    this(new InetSocketAddress(addr, port));
  }

  @Deprecated // TODO This  is not how we should do it. Find IA automatically or require it via
  // constructor
  public void setDstIsdAs(String dstIA) {
    helper.setDstIsdAs(dstIA);
  }

  public synchronized void receive(DatagramPacket packet) throws IOException {
    // synchronized because we use `buffer`
    while (true) {
      // TODO reuse incoming packet
    // TODO rename to bytes to buffer
      DatagramPacket incoming = new DatagramPacket(bytes, bytes.length);
      System.out.println("waiting to receive: "); // TODO
      socket.receive(incoming);
      underlayPort = incoming.getPort();
      underlayAddress = incoming.getAddress();


      int headerLength = helper.readScionHeader(bytes);
      if (headerLength < 0) {
        // Ignore packet
        continue;
      }
      System.arraycopy(bytes, headerLength, packet.getData(), 0, helper.getPayloadLength());

      // build packet
      packet.setLength(scionHeader.getPayloadLength());
      packet.setPort(overlayHeaderUdp.getSrcPort());
      packet.setAddress(scionHeader.getSrcHostAddress(bytes));

      pathState = ScionPacketHelper.PathState.RCV_PATH;
      break;
    }
  }

  public synchronized void send(DatagramPacket userPacket) throws IOException {
    ScionPath path = null;
    InetSocketAddress dstAddress = (InetSocketAddress) userPacket.getSocketAddress();

    System.out.println(
            "remote1 = " + dstAddress + "    remote2 = " + helper.getSourceAddress());
    if (dstAddress.getAddress().equals(helper.getSourceAddress())) {
      // We are just sending back to last IP. We can use the reversed path. No need to lookup a
      // path.
      // path = helper.getLastIncomingPath(destinationAddress);
      if (pathState != ScionPacketHelper.PathState.RCV_PATH) {
        throw new IllegalStateException(
                "state=" + pathState); // TODO remove this check and possibly the path state alltogether
      }
    } else {
      // find a path
      path = helper.getDefaultPath(dstAddress);
      // routerAddress = null;
      underlayAddress = null;
      underlayPort = -1;
    }
    send(userPacket, path);
  }

  public synchronized void send(DatagramPacket userPacket, ScionPath path) throws IOException {
    // synchronized because we use `buffer`
    // TODO request new path after a while?

    // TODO use local field Datagram Packer?!
    DatagramPacket outgoing = new DatagramPacket(bytes, bytes.length);
    byte[] dataOut = outgoing.getData();

    switch (pathState) {
      case NO_PATH:
      {
        break;
      }
      case RCV_PATH:
      {
        // TODO !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!??????????????//????????????????????????
        underlayPort = 31012;
        break;
      }
      case SEND_PATH:
      {
        break;
      }
      default:
        throw new IllegalStateException(pathState.name());
    }

    // get local IP
    if (!socket.isConnected() && localAddress == null) {
      InetSocketAddress borderRouterAddr = helper.getFirstHopAddress(path);
      socket.connect(borderRouterAddr);
      localAddress = new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
    }

    InetSocketAddress dstAddress = (InetSocketAddress) userPacket.getSocketAddress();

    // New stuff...
    int payloadLength = userPacket.getLength();
    int headerLength =
            helper.writeHeader(dataOut, pathState, localAddress, dstAddress, payloadLength);

    System.arraycopy(userPacket.getData(), 0, dataOut, headerLength, payloadLength);
    InetSocketAddress firstHopAddress = null;
    if (pathState == ScionPacketHelper.PathState.RCV_PATH) {
      //firstHopAddress = routerAddress;
      outgoing.setAddress(underlayAddress);
      outgoing.setPort(underlayPort);
    } else {
      firstHopAddress = helper.getFirstHopAddress();
      outgoing.setAddress(firstHopAddress.getAddress());
      outgoing.setPort(firstHopAddress.getPort());
      // TODO routerAddress = firstHopAddress?
    }
    outgoing.setLength(headerLength + payloadLength);

    socket.send(outgoing);

    pathState = ScionPacketHelper.PathState.SEND_PATH;


//    // TODO rename & split? writePayload() + sendPacket() ?
//    sendScionPacket(offset, outgoing, packet);
//    System.out.println(
//            "Sending packet: "
//                    + outgoing.getSocketAddress()
//                    + " : "
//                    + outgoing.getLength()
//                    + "/"
//                    + outgoing.getOffset()
//                    + "/"
//                    + outgoing.getData().length);
//
//    socket.send(outgoing);
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
