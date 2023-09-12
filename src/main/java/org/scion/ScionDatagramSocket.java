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

import org.scion.internal.Constants;
import org.scion.internal.PathHeaderOneHopPath;
import org.scion.internal.PathHeaderScion;
import org.scion.internal.PseudoHeader;
import org.scion.internal.ScionEndToEndExtensionHeader;
import org.scion.internal.ScionHeader;
import org.scion.internal.ScionSCMPHeader;
import org.scion.proto.daemon.Daemon;


public class ScionDatagramSocket {
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
  private final PseudoHeader pseudoHeaderUdp = new PseudoHeader();
  private int underlayPort;
  private InetAddress underlayAddress;
  private PathState pathState = PathState.NO_PATH;
  // TODO provide ports etc?  Allow separate instances for different sockets?
  // TODO create lazily to prevent network connections before we create any actual DatagramSocket?
  private DaemonClient pathService;
  // TODO remove?
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

          scionHeader.setSrcIA(srcIA);
          scionHeader.setDstIA(dstIA);
          scionHeader.setSrcHostAddress(socket.getLocalAddress());
          scionHeader.setDstHostAddress(packet.getAddress());

          pathHeaderScion.setPath(path);

          pathState = PathState.SEND_PATH;
          // break;
        }
      case RCV_PATH:
        {
          scionHeader.reverse();
          pathHeaderScion.reverse();
          pseudoHeaderUdp.reverse();
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

    System.out.println("Sending packet: " + outgoing.getSocketAddress() + " : " + outgoing.getLength() + "/" + outgoing.getOffset() + "/" + outgoing.getData().length);

    socket.send(outgoing);
  }

  public int getLocalPort() {
    return socket.getLocalPort();
  }

  public int getPort() {
    return socket.getPort();
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
      // These are probably answers to polling/keep-alive packets sent from the dispatcher, but the dispatcher
      // can´t receive them due to pert forwarding to 40041 so the dispatcher keeps requesting them.
      if (!scionHeader.getDstHostAddress().isLoopbackAddress()) {
        System.out.println("PACKET DROPPED: dstHost=" + scionHeader.getDstHostAddress());
        return false;
      }
      printHeaders();

      offset = pseudoHeaderUdp.read(data, offset);
      System.out.println(pseudoHeaderUdp);
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

    // TODO handle MAC in HopField?
    // TODO Handle checksum in PseudoHeader?

    // build packet
    int length = (p.getLength() - offset);
    System.arraycopy(p.getData(), offset, userPacket.getData(), userPacket.getOffset(), length);
    userPacket.setLength(length);
    userPacket.setPort(pseudoHeaderUdp.getSrcPort());
    userPacket.setAddress(scionHeader.getSrcHostAddress(data));
    pathState = PathState.RCV_PATH;
    return true;
  }

  private void writeScionHeader(DatagramPacket p, DatagramPacket userPacket) {
    // TODO reset offset ?!?!?!?
    if (p.getOffset() != 0) {
      throw new IllegalStateException("of=" + p.getOffset());
    }
    // System.out.println("Sending: dst=" + userPacket.getAddress() + " / src=" + socket.getLocalAddress());
    int offset = scionHeader.write(p.getData(), userPacket, pathHeaderScion);
    offset = pathHeaderScion.write(p.getData(), offset);
    offset = pseudoHeaderUdp.write(p.getData(), offset, userPacket.getLength());

    // build packet
    System.arraycopy(
        userPacket.getData(), userPacket.getOffset(), p.getData(), offset, userPacket.getLength());
    p.setLength(offset + userPacket.getLength());
//    System.out.println(
//            "length: " + offset + " + " + userPacket.getLength() + "   vs  " + p.getData().length + "  -> " + p.getLength());


    // First hop
    // TODO ?!?!?!?!?!
    // p.setPort(underlayPort);
    p.setPort(31012);  // TODO !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!??????????????//????????????????????????
    p.setAddress(underlayAddress);
    pathState = PathState.RCV_PATH;
    System.out.println("Sending to underlay: " + underlayAddress + " : " + underlayPort);
  }
}
