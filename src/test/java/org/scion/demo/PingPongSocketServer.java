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
import org.scion.socket.DatagramSocket;

public class PingPongSocketServer {

  public static int port = 44444;

  public static void main(String[] args) throws UnknownHostException {
    InetAddress localAddress = InetAddress.getByName("::1");
    // InetAddress localAddress = InetAddress.getByName("127.0.0.1");
    // InetAddress localAddress = InetAddress.getByName("127.0.0.10");
    // InetAddress localAddress = InetAddress.getByName("fd00:f00d:cafe::7f00:c");
    try {
      service(port, localAddress);
    } catch (SocketException ex) {
      System.out.println("Socket error: " + ex.getMessage());
    } catch (IOException ex) {
      System.out.println("I/O error: " + ex.getMessage());
    }
  }

  private static void service(int port, InetAddress localAddress) throws IOException {
    try (DatagramSocket socket = new DatagramSocket(port, localAddress)) {
      while (true) {
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
        // TODO fix this, we are ignoring the port here.....
        DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress, 3101);
        socket.send(response);
      }
    }
  }
}
