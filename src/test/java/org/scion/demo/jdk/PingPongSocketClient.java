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

package org.scion.demo.jdk;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public class PingPongSocketClient {

  public static void main(String[] args) {
    String hostname = "127.0.0.1";
    int port = 13579;

    try {
      InetAddress address = InetAddress.getByName(hostname);
      DatagramSocket socket = new DatagramSocket();

      while (true) {

        DatagramPacket request = new DatagramPacket(new byte[1], 1, address, port);
        socket.send(request);

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
    } catch (IOException e) {
      System.out.println("Client error: " + e.getMessage());
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
