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

import org.scion.internal.*;

import java.io.IOException;
import java.net.*;

public class ScionDatagramSocket {
  /*
   * Design:
   * We use delegation rather than inheritance. Inheritance is more difficult to handle if future version of
   * java.net.DatagramSocket get additional methods that need special SCION handling. These methods would
   * behave incorrectly until adapted, and, once adapted, would not compile anymore with earlier Java releases.
   */

  private final DatagramSocket socket;
  private final byte[] buf = new byte[65535 - 28];  // -28 for 8 byte UDP + 20 byte IP header
  private final ScionCommonHeader commonHeader = new ScionCommonHeader();
  private final AddressHeader addressHeader = new AddressHeader(commonHeader);
  private final PathHeaderScion pathHeaderScion = new PathHeaderScion(commonHeader);
  private final PathHeaderOneHopPath pathHeaderOneHop = new PathHeaderOneHopPath();


  public ScionDatagramSocket() throws SocketException {
    this.socket = new DatagramSocket();
  }

  public ScionDatagramSocket(int port) throws SocketException {
    this.socket = new DatagramSocket(port);
  }

  public void bind(SocketAddress address) throws SocketException {
    this.socket.bind(address);
  }

  public synchronized void receive(DatagramPacket packet) throws IOException {
    // synchronized because we use `buffer`
    DatagramPacket incoming = new DatagramPacket(buf, buf.length);
    socket.receive(incoming);
    System.out.println("received: len=" + incoming.getLength()); // TODO
    readScionHeader(incoming, packet);
  }

  public synchronized void send(DatagramPacket packet) throws IOException {
    // synchronized because we use `buffer`
    // TODO use local field Datagram Packer?!
    DatagramPacket outgoing = new DatagramPacket(buf, buf.length);
    writeScionHeader(outgoing, packet);

    // TODO!!!  socket.send(outgoing);
  }

  public int getLocalPort() {
    return socket.getLocalPort();
  }

  public int getPort() {
    return socket.getPort();
  }

  private void writeScionHeader(DatagramPacket p, DatagramPacket userPacket) {
    // TODO reset offset ?!?!?!?
    int offset = p.getOffset();
    if (offset != 0) {
      throw new IllegalStateException("of=" + offset);
    }
    offset += ScionCommonHeader.write(p.getData(), offset, userPacket, socket.getLocalAddress());
    offset += AddressHeader.write(p.getData(), p.getOffset(), commonHeader, addressHeader);
    offset += PathHeaderScion.write(p.getData(), p.getOffset(), commonHeader, addressHeader, pathHeaderScion);


//    outgoing.setData(packet.getData());
//    outgoing.setPort(packet.getPort());
//    outgoing.setAddress(packet.getAddress());

  }

  private void readScionHeader(DatagramPacket p, DatagramPacket userPacket) {
    byte[] data = p.getData();
    commonHeader.read(data, 0);
    System.out.println("Common header: " + commonHeader);
    int offset = commonHeader.length();
    addressHeader.read(data, offset);
    System.out.println("Address header: " + addressHeader);
    offset += addressHeader.length();
    if (commonHeader.pathType() == 1) {
      pathHeaderScion.read(data, offset);
      offset += pathHeaderScion.length();
      System.out.println("Path header: " + pathHeaderScion);
    } else if (commonHeader.pathType() == 2) {
      offset = pathHeaderOneHop.read(data, offset);
      System.out.println("Path header: " + pathHeaderOneHop);
    } else {
      throw new UnsupportedOperationException("Path type: " + commonHeader.pathType());
    }
    System.out.println(
        "Payload: " + (p.getLength() - offset) + " (bytes left in header: " + (commonHeader.hdrLenBytes() - offset) + ")");
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
  }

  private void readExtensionHeader(byte[] data, int offset) {}
}
