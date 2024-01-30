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

package org.scion.demo;

import java.io.*;
import java.net.*;
import org.scion.DatagramSocket;

@Deprecated // This does not work.
public class PingPongSocketServer {
  private final DatagramSocket socket;

  public PingPongSocketServer(int port, InetAddress localAddress) throws SocketException {
    socket = new DatagramSocket(port, localAddress);
  }

  public static void main(String[] args) throws UnknownHostException {
    int port = 44444;

    InetAddress localAddress = InetAddress.getByName("::1");
    // InetAddress localAddress = InetAddress.getByName("127.0.0.1");
    // InetAddress localAddress = InetAddress.getByName("127.0.0.10");
    // System.out.println("IPv4: " + (localAddress instanceof Inet4Address));
    // InetAddress localAddress = InetAddress.getByName("fd00:f00d:cafe::7f00:c");
    try {
      PingPongSocketServer server = new PingPongSocketServer(port, localAddress);
      server.service();
    } catch (SocketException ex) {
      System.out.println("Socket error: " + ex.getMessage());
    } catch (IOException ex) {
      System.out.println("I/O error: " + ex.getMessage());
    }
  }

  private void service() throws IOException {
    while (true) {
      // TODO avoid byte[]? Or use byte[] internally?  --> May be to small!!!  -> Not transparently
      // plugable!
      //      -> users need to adapt array size. Without adaptation: requires copy.....
      //      -> Copy is alright, but high performance user may want a a way to avoid the copy....
      //      -> Make this configurable via OPTIONS?
      DatagramPacket request = new DatagramPacket(new byte[65536], 65536);
      System.out.println("--- USER - Waiting for packet ----------------------");
      socket.receive(request);
      String msg = new String(request.getData(), request.getOffset(), request.getLength());
      System.out.println("Received (from " + request.getSocketAddress() + "): " + msg);

      byte[] buffer = ("Re: " + msg).getBytes();

      InetAddress clientAddress = request.getAddress();
      int clientPort = request.getPort();

      // DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress,
      // clientPort);
      // IPv6 border router port???
      System.out.println("--- USER - Sending packet ----------------------");
      // TODO fix this, we should not specify the daemon port here!!
      DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress, 31012);
      socket.send(response);
    }
  }
}
