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

  public static boolean PRINT = true;
  public static int port = 44444;

  public static void main(String[] args) throws UnknownHostException {
    InetAddress localAddress = InetAddress.getByName("::1");
    // InetAddress localAddress = InetAddress.getByName("127.0.0.1");
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
      DatagramPacket packet = new DatagramPacket(new byte[100], 100);
      println("Waiting for packet ... ");
      socket.receive(packet);
      String msg = new String(packet.getData(), packet.getOffset(), packet.getLength());
      println("Received (from " + packet.getSocketAddress() + "): " + msg);

      String msgAnswer = "Re: " + msg;
      packet.setData(msgAnswer.getBytes());

      socket.send(packet);
      println("Sent answer: " + msgAnswer);
    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
