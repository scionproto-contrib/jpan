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
import java.util.List;

import org.scion.internal.*;
import org.scion.proto.daemon.Daemon;

public class ScionDatagramSocket {
  /*
   * Design:
   * We use delegation rather than inheritance. Inheritance is more difficult to handle if future version of
   * java.net.DatagramSocket get additional methods that need special SCION handling. These methods would
   * behave incorrectly until adapted, and, once adapted, would not compile anymore with earlier Java releases.
   */

  private final DatagramSocket socket;
  // TODO use ByteBuffer to manage offset etc?
  private final byte[] buf = new byte[65535 - 28]; // -28 for 8 byte UDP + 20 byte IP header
  private final CommonHeader commonHeader = new CommonHeader();
  private final AddressHeader addressHeader = new AddressHeader(commonHeader);
  private final PathHeaderScion pathHeaderScion = new PathHeaderScion();
  private final PathHeaderOneHopPath pathHeaderOneHop = new PathHeaderOneHopPath();
  private int underlayPort;
  private InetAddress underlayAddress;
  private PathState pathState = PathState.NO_PATH;
  // TODO provide ports etc?  Allow separate instances for different sockets?
  // TODO create lazily to prevent network connections before we create any actual DatagramSocket?
  private DaemonClient pathService;
  private InetAddress localAddress;
  private int localPort;
  private long srcIA;
  private long dstIA;

  private enum PathState {
    NO_PATH,
    RCV_PATH,
    SEND_PATH
  }

  public ScionDatagramSocket() throws SocketException {
    this.socket = new DatagramSocket();
    System.out.println("Creating socket with src = " + socket.getLocalAddress() + " : " + socket.getLocalPort());
  }

  public ScionDatagramSocket(int port) throws SocketException {
    this.socket = new DatagramSocket(port);
    System.out.println(
        "Socket created: local="
            + socket.getLocalAddress()
            + " : "
            + socket.getLocalPort()
            + "   remote="
            + socket.getInetAddress()
            + " : "
            + socket.getPort());
  }

  @Deprecated // TODO This  is not how we should do it. Find IA automatically or require it via constructor
  public void setDstIsdAs(String dstIA) {
    // TODO rename ParseIA to parseIA
    this.dstIA = Util.ParseIA(dstIA);
  }
  public void bind(SocketAddress address) throws SocketException {
    this.socket.bind(address);
  }

  public synchronized void receive(DatagramPacket packet) throws IOException {
    // synchronized because we use `buffer`
    while (true) {
      // TODO reuse incoming packet
      DatagramPacket incoming = new DatagramPacket(buf, buf.length);
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
    switch (pathState) {
      case NO_PATH:
        {
          // TODO request path from daemon
          if (pathService == null) {
            pathService = DaemonClient.create();
          }
          if (srcIA == 0) {
            srcIA = pathService.getLocalIsdAs();
          }
          System.out.println("Getting path from " + localAddress + " : " + localPort);
          System.out.println("               to " + packet.getAddress() + " : " + packet.getPort());
          if (srcIA == 0 || dstIA == 0) {
            throw new IllegalStateException("srcIA/dstIA not set!"); // TODO fix / remove
          }
          List<Daemon.Path> path = pathService.getPath(srcIA, dstIA);

          addressHeader.setSrcIA(srcIA);
          addressHeader.setDstIA(dstIA);
//          addressHeader.setSrcHostAddress(socket.getLocalAddress(), socket.getLocalPort());
//          addressHeader.setDstHostAddress(packet.getSocketAddress());
//
//          pathHeaderScion.setPath(path);


          pathState = PathState.SEND_PATH;
          // break;
        }
      case RCV_PATH:
        {
          // TODO sendPath = receivePath.reverse()
          commonHeader.reverse();
          addressHeader.reverse();
          pathHeaderScion.reverse();
          pathState = PathState.SEND_PATH;
          break;
        }
      case SEND_PATH:
        {
          // Nothing to do
          break;
        }
      default:
        throw new IllegalStateException(pathState.name());
    }

    // TODO use local field Datagram Packer?!
    DatagramPacket outgoing = new DatagramPacket(buf, buf.length);
    writeScionHeader(outgoing, packet);

    System.out.println("Sending: " + outgoing.getSocketAddress() + " : " + outgoing.getLength() + "/" + outgoing.getOffset() + "/" + outgoing.getData().length);

    socket.send(outgoing);
  }

  public int getLocalPort() {
    return socket.getLocalPort();
  }

  public int getPort() {
    return socket.getPort();
  }

  private boolean readScionHeader(DatagramPacket p, DatagramPacket userPacket) throws IOException {
    // TODO See which checks we have to perform from the list in the book p118 (BR ingress)
    byte[] data = p.getData();
    //    for (int i = 0; i < p.getLength(); i++) {
    //      System.out.print(data[i] + ", ");
    //    }
    //    System.out.println();
    int offset = commonHeader.read(data, 0);
    System.out.println("Common header: " + commonHeader);
    offset = addressHeader.read(data, offset);
    if (commonHeader.getDT() != 0) {
      System.out.println(
          "Packet dropped: service address="
              + addressHeader.getDstHostAddress()
              + "  DT="
              + commonHeader.getDT());
      return false;
    }
    //    if (p.getLength() == 103) {
    //      if (!addressHeader.getDstHostAddress().isAnyLocalAddress()) {
    //        System.out.println("Packet dropped: dstHost=" + addressHeader.getDstHostAddress());
    //        return false;
    //      }
    //    }
    // TODO ! How can we properly filter out unwanted packets???
    if (!addressHeader.getDstHostAddress().isLoopbackAddress()) {
      System.out.println("Packet dropped: dstHost=" + addressHeader.getDstHostAddress());
      return false;
    }

    System.out.println("Address header: " + addressHeader);
    if (commonHeader.pathType() == 1) {
      offset = pathHeaderScion.read(data, offset);
      System.out.println("Path header: " + pathHeaderScion);
    } else if (commonHeader.pathType() == 2) {
      offset = pathHeaderOneHop.read(data, offset);
      System.out.println("OneHop header: " + pathHeaderOneHop);
      return false;
    } else {
      throw new UnsupportedOperationException("Path type: " + commonHeader.pathType());
    }
    System.out.println(
        "Payload: "
            + (p.getLength() - offset)
            + " (bytes left in header: "
            + (commonHeader.hdrLenBytes() - offset)
            + ")");
    // offset += ???;
    // readExtensionHeader(data, offset);

    // Pseudo header
    PseudoHeader udpHeader = PseudoHeader.read(data, offset);
    System.out.println(udpHeader);
    offset += udpHeader.length();

    // TODO handle MAC in HopField?
    // TODO Handle checksum in PseudoHeader?

    // build packet
    int length = (p.getLength() - offset);
    System.arraycopy(p.getData(), offset, userPacket.getData(), userPacket.getOffset(), length);
    userPacket.setLength(length);
    userPacket.setPort(udpHeader.getSrcPort());
    userPacket.setAddress(addressHeader.getSrcHostAddress(data));
    pathState = PathState.RCV_PATH;
    return true;
  }

  private void readExtensionHeader(byte[] data, int offset) {}

  private void writeScionHeader(DatagramPacket p, DatagramPacket userPacket) {
    // TODO reset offset ?!?!?!?
    if (p.getOffset() != 0) {
      throw new IllegalStateException("of=" + p.getOffset());
    }
    int offset = CommonHeader.write(p.getData(), userPacket, socket.getLocalAddress());
    System.out.println("Sending: dst=" + userPacket.getAddress() + " / src=" + socket.getLocalAddress());
    offset = AddressHeader.write(p.getData(), offset, commonHeader, addressHeader);
    offset = pathHeaderScion.write(p.getData(), offset);

    // build packet
    System.arraycopy(
        userPacket.getData(), userPacket.getOffset(), p.getData(), offset, userPacket.getLength());
    System.out.println(
        "length: " + offset + " + " + userPacket.getLength() + "   vs  " + p.getData().length);
    p.setLength(offset + userPacket.getLength());

    // First hop
    p.setPort(underlayPort);
    p.setAddress(underlayAddress);
    pathState = PathState.RCV_PATH;
    System.out.println("Sending to: " + underlayAddress + " : " + underlayPort);
    System.out.println("Could send to: " + socket.getInetAddress() + " : " + socket.getPort());
  }
}
