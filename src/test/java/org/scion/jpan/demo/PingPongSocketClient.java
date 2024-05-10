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

import java.io.IOException;
import java.net.*;
import org.scion.jpan.ScionDatagramSocket;
import org.scion.jpan.testutil.MockDNS;

/**
 * Minimal ping pong client/server demo. The client sends a text message to a server and the server
 * responds with an extended message. Client and server are in different ASes connected by a mock
 * border router.
 *
 * <p>To better see what is happening you can set the logging level to INFO in
 * src/test/resources/simplelogger.properties
 */
public class PingPongSocketClient {

  public static boolean PRINT = true;

  public static void main(String[] args) throws IOException {
    // The following starts a mock daemon for a local AS "1-ff00:0:110" and a border router that
    // connects to "1-ff00:0:112"
    DemoTopology.configureMock(true);
    // This is used by the DatagramSocket internally to look up the ISD/AS code.
    MockDNS.install("1-ff00:0:112", PingPongSocketServer.SERVER_ADDRESS.getAddress());
    run();
    DemoTopology.shutDown();
  }

  private static void run() throws IOException {
    InetSocketAddress serverAddress = PingPongSocketServer.SERVER_ADDRESS;
    try (ScionDatagramSocket socket = new ScionDatagramSocket(null)) {
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
