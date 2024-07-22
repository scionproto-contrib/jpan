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

package org.scion.jpan.demo;

import java.io.*;
import java.net.*;
import org.scion.jpan.ScionDatagramSocket;

public class PingPongSocketServer {

  public static boolean PRINT = true;

  public static final String SERVER_HOST_NAME = "ping.pong.org";
  public static final InetSocketAddress SERVER_ADDRESS;
  public static final int SERVER_PORT = 44444;

  static {
    try {
      byte[] serverIP = InetAddress.getLoopbackAddress().getAddress();
      InetAddress address = InetAddress.getByAddress(SERVER_HOST_NAME, serverIP);
      SERVER_ADDRESS = new InetSocketAddress(address, SERVER_PORT);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String[] args) {
    try {
      service();
    } catch (SocketException ex) {
      System.out.println("Socket error: " + ex.getMessage());
    } catch (IOException ex) {
      System.out.println("I/O error: " + ex.getMessage());
    }
  }

  private static void service() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket(SERVER_ADDRESS)) {
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
