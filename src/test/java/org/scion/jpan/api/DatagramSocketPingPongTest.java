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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.scion.jpan.testutil.PingPongHelperBase.MSG;

import java.io.IOException;
import java.net.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.RequestPath;
import org.scion.jpan.ScionService;
import org.scion.jpan.socket.DatagramSocket;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.PingPongSocketHelper;

class DatagramSocketPingPongTest {

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void test() {
    PingPongSocketHelper.Server serverFn = this::server;
    PingPongSocketHelper.Client clientFn = this::client;
    PingPongSocketHelper pph = new PingPongSocketHelper(1, 10, 10);
    pph.runPingPong(serverFn, clientFn, false);
    assertEquals(2 * 10 * 10, MockNetwork.getAndResetForwardCount());
  }

  private void client(DatagramSocket socket, RequestPath requestPath, int id) throws IOException {
    byte[] sendBuf = MSG.getBytes();
    InetAddress addr = requestPath.getDestinationAddress();
    int port = requestPath.getDestinationPort();
    DatagramPacket request = new DatagramPacket(sendBuf, sendBuf.length, addr, port);
    socket.send(request);

    // System.out.println("CLIENT: Receiving ... (" + socket.getLocalSocketAddress() + ")");
    byte[] buffer = new byte[512];
    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
    socket.receive(response);

    String pong = new String(buffer, 0, response.getLength());
    assertEquals(MSG, pong);
  }

  private void server(DatagramSocket socket) throws IOException {
    DatagramPacket request = new DatagramPacket(new byte[200], 200);
    // System.out.println("SERVER: --- USER - Waiting for packet ---------------------- ");
    socket.receive(request);

    String msg = new String(request.getData(), request.getOffset(), request.getLength());
    assertEquals(MSG, msg);

    byte[] buffer = msg.getBytes();
    InetAddress clientAddress = request.getAddress();
    int clientPort = request.getPort();

    // System.out.println(
    //    "SERVER: --- USER - Sending packet -------- " + clientAddress + " : " + clientPort);
    DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress, clientPort);
    socket.send(response);
  }
}
