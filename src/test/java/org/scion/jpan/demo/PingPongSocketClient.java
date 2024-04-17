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

import java.io.IOException;
import java.net.*;
import org.scion.socket.DatagramSocket;
import org.scion.testutil.MockDNS;

public class PingPongSocketClient {

  public static boolean PRINT = true;

  public static void main(String[] args) throws IOException {
    DemoTopology.configureMock(); // Tiny111_112();
    MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
    run();
  }

  private static void run() throws IOException {
    // String serverHostname = "::1";
    String serverHostname = "0:0:0:0:0:0:0:1";

    InetAddress serverIP = InetAddress.getByName(serverHostname);
    InetSocketAddress serverAddress = new InetSocketAddress(serverIP, PingPongSocketServer.port);
    try (DatagramSocket socket = new DatagramSocket(null)) {
      String msg = "Hello there!";
      byte[] sendBuf = msg.getBytes();
      DatagramPacket request = new DatagramPacket(sendBuf, sendBuf.length, serverAddress);
      socket.send(request);
      println("Sent: " + msg);

      println("Receiving ... (" + socket.getLocalSocketAddress() + ")");
      byte[] buffer = new byte[512];
      DatagramPacket response = new DatagramPacket(buffer, buffer.length);
      socket.receive(response);

      String pong = new String(buffer, 0, response.getLength());
      println("Received: " + pong);
    } catch (SocketTimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
