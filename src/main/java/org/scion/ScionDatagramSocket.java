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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class ScionDatagramSocket {
  /*
   * Design:
   * We use delegation rather than inheritance. Inheritance is more difficult to handle if future version of
   * java.net.DatagramSocket get additional methods that need special SCION handling. These methods would
   * behave incorrectly until adapted, and, once adapted, would not compile anymore with earlier Java releases.
   */

  private final DatagramSocket socket;

  public ScionDatagramSocket(int port) throws SocketException {
    this.socket = new DatagramSocket(port);
  }

  public void receive(DatagramPacket packet) throws IOException {
    //        System.out.println("receive - 1"); // TODO
    byte[] buf = new byte[65536];
    DatagramPacket incoming = new DatagramPacket(buf, buf.length);
    //        System.out.println("receive - 2"); // TODO
    socket.receive(incoming);
    System.out.println("received: len=" + incoming.getLength()); // TODO
    //        for (int i = 0; i < incoming.getLength(); i++) {
    //            System.out.print("  " + incoming.getData()[i]);
    //        }
    //        System.out.println();
    if (incoming.getLength() == 1) {
      System.out.println("Aborting");
      return;
    }
    //        System.out.println("receive - 3b : " + incoming.getLength()); // TODO
    readScionHeader(incoming, packet);
  }

  public void send(DatagramPacket packet) throws IOException {
    DatagramPacket outgoing = new DatagramPacket(new byte[1], 1);
    outgoing.setData(packet.getData());
    outgoing.setPort(packet.getPort());
    outgoing.setAddress(packet.getAddress());
    socket.send(outgoing);
  }

  public int getLocalPort() {
    return socket.getLocalPort();
  }

  public int getPort() {
    return socket.getPort();
  }

  private int readScionHeader(DatagramPacket p, DatagramPacket userPacket) {
    byte[] data = p.getData();
    ScionCommonHeader common = ScionCommonHeader.read(data, 0);
    System.out.println("Common header: " + common);
    int offset = common.length();
    AddressHeader address = AddressHeader.read(data, offset, common);
    System.out.println("Address header: " + address);
    offset += address.length();
    if (common.pathType() == 1) {
      PathHeaderScion pathHeader = PathHeaderScion.read(data, offset, common);
      offset += pathHeader.length();
      System.out.println("Path header: " + pathHeader);
    } else if (common.pathType() == 2) {
      PathHeaderOneHopPath pathHeader = PathHeaderOneHopPath.read(data, offset, common);
      offset += pathHeader.length();
      System.out.println("Path header: " + pathHeader);
    } else {
      throw new UnsupportedOperationException("Path type: " + common.pathType());
    }
    System.out.println(
        "Left: " + (p.getLength() - offset) + " vs " + (common.hdrLenBytes() - offset));
    // offset += ???;
    // readExtensionHeader(data, offset);


    // Pseudo header
    PseudoHeader udpHeader = PseudoHeader.read(data, offset);
    System.out.println(udpHeader);
    offset += udpHeader.length();


    // build packet
    //        System.out.println("receive - g"); // TODO
    userPacket.setData(p.getData(), offset, p.getLength() - offset);
    //        System.out.println("receive - 4"); // TODO
    userPacket.setPort(p.getPort());
    //        System.out.println("receive - 5"); // TODO
    userPacket.setAddress(p.getAddress());
    //        System.out.println("receive - 6"); // TODO
    // Not necessary: packet.setSocketAddress(incoming.getSocketAddress());


    return offset;
  }

  private void readExtensionHeader(byte[] data, int offset) {}
}
