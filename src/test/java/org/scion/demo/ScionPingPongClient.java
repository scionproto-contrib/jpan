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
import org.scion.ScionSocketAddress;
import org.scion.testutil.MockDNS;
import org.scion.testutil.MockDaemon;

@Deprecated // This does not work.
public class ScionPingPongClient {

  public static void main(String[] args) throws IOException {
    DemoTopology.configureMock(); // Tiny111_112();
    MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
    ScionPingPongClient client = new ScionPingPongClient();
    client.run();
  }

  private void run() throws IOException {
    //String serverHostname = "::1";
    String serverHostname = "0:0:0:0:0:0:0:1";
    int serverPort = 44444;

    try {
      // InetAddress serverAddress2 = InetAddress.getByName(serverHostname);
      ScionSocketAddress serverAddress = ScionSocketAddress.create("1-ff00:0:112", serverHostname, serverPort);
      DatagramSocket socket = new DatagramSocket(null);

      while (true) {
        String msg = "Hello there!";
        byte[] sendBuf = msg.getBytes();
        DatagramPacket request =
            new DatagramPacket(sendBuf, sendBuf.length, serverAddress);
        socket.send(request);
        System.out.println("Sent!");

        System.out.println("Receiving ... (" + socket.getLocalSocketAddress() + ")");
        byte[] buffer = new byte[512];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        socket.receive(response);

        String pong = new String(buffer, 0, response.getLength());

        System.out.println(pong);

        Thread.sleep(1000);
      }

    } catch (SocketTimeoutException e) {
      System.out.println("Timeout error: " + e.getMessage());
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
