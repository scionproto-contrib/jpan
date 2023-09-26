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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.demo.ScionPingPongServer;
import org.scion.proto.daemon.Daemon;
import org.scion.testutil.MockDaemon;
import org.scion.testutil.MockNetwork;

public class PingPongSocketTest {

  private static final InetSocketAddress MOCK_DAEMON_ADDRESS =
      new InetSocketAddress("127.0.0.15", 30255);
  private static final InetSocketAddress MOCK_BR1_ADDRESS =
          new InetSocketAddress("127.0.0.1", 30333);

  @Test
  public void getPath() throws IOException, InterruptedException {
    MockDaemon daemon = MockDaemon.create(MOCK_DAEMON_ADDRESS, MOCK_BR1_ADDRESS).start();
    SocketAddress br1Dst = new InetSocketAddress("127.0.0.1", 12200);
    SocketAddress br2Dst = new InetSocketAddress("127.0.0.1", 12201);
    MockNetwork.startTiny(MOCK_BR1_ADDRESS.getPort(), 30444, br1Dst, br2Dst);

    InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", 22233);
    Thread server = new Thread(() -> server(serverAddress));
    server.start();
    Thread client = new Thread(() -> client(serverAddress));
    client.start();

    client.join();
    server.join();

    MockNetwork.stopTiny();
    daemon.close();



//    // String daemonAddr = "127.0.0.12:30255"; // from 110-topo
//    String daemonAddr = ScionUtil.toHostAddrPort(MOCK_DAEMON_ADDRESS);
//    List<Daemon.Path> paths;
//    long srcIA = ScionUtil.ParseIA("1-ff00:0:110");
//    long dstIA = ScionUtil.ParseIA("1-ff00:0:112");
//    try (ScionPathService client = ScionPathService.create(daemonAddr)) {
//      paths = client.getPathList(srcIA, dstIA);
//    } catch (IOException e) {
//      throw new RuntimeException(e);
//    }

    // Expected:
    //    Paths found: 1
    //    Path: first hop = 127.0.0.10:31004
    //    0: 2 561850441793808
    //    0: 1 561850441793810

//    assertEquals(1, paths.size());
//    Daemon.Path path0 = paths.get(0);
//    assertEquals("127.0.0.10:31004", path0.getInterface().getAddress().getAddress());

  }

  private void client(SocketAddress serverAddress) {
    try {
      client2(serverAddress);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void client2(SocketAddress serverAddress) throws IOException {
    try {
      ScionDatagramSocket socket = new ScionDatagramSocket(null);
      socket.setDstIsdAs("1-ff00:0:112");

      while (true) {

        String msg = "Hello there!";
        byte[] sendBuf = msg.getBytes();
        DatagramPacket request =
            new DatagramPacket(sendBuf, sendBuf.length, serverAddress);
        // TODO fix
        //  socket.connect(serverAddress, serverPort);
        socket.send(request);
        System.out.println("CLIENT: Sent!");

        System.out.println("CLIENT: Receiving ... (" + socket.getLocalSocketAddress() + ")");
        byte[] buffer = new byte[512];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        socket.receive(response);

        String pong = new String(buffer, 0, response.getLength());

        System.out.println(pong);

        Thread.sleep(1000);
      }

    } catch (SocketTimeoutException e) {
      System.out.println("CLIENT: Timeout error: " + e.getMessage());
      throw new RuntimeException(e);
      //        } catch (IOException e) {
      //            System.out.println("Client error: " + e.getMessage());
      //            throw new RuntimeException(e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void server(InetSocketAddress localAddress) {
    try {
      ScionDatagramSocket socket = new ScionDatagramSocket(localAddress);
      service(socket);
    } catch (SocketException ex) {
      System.out.println("CLIENT: Socket error: " + ex.getMessage());
    } catch (IOException ex) {
      System.out.println("CLIENT: I/O error: " + ex.getMessage());
    }
  }

  private void service(ScionDatagramSocket socket) throws IOException {
    while (true) {
      // TODO avoid byte[]? Or use byte[] internally?  --> May be to small!!!  -> Not transparently
      // plugable!
      //      -> users need to adapt array size. Without adaptation: requires copy.....
      //      -> Copy is alright, but high performance user may want a a way to avoid the copy....
      //      -> Make this configurable via OPTIONS?
      DatagramPacket request = new DatagramPacket(new byte[65536], 65536);
      System.out.println("SERVER: --- USER - Waiting for packet ----------------------");
      socket.receive(request);
      //            for (int i = 0; i < request.getLength(); i++) {
      //
      // System.out.print(Integer.toHexString(Byte.toUnsignedInt(request.getData()[request.getOffset() + i])) + ", ");
      //            }
      //            System.out.println();
      String msg = new String(request.getData(), request.getOffset(), request.getLength());
      System.out.println("SERVER: Received (from " + request.getSocketAddress() + "): " + msg);

      byte[] buffer = ("Re: " + msg).getBytes();

      InetAddress clientAddress = request.getAddress();
      int clientPort = request.getPort();

      // DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress,
      // clientPort);
      // IPv6 border router port???
      System.out.println("SERVER: --- USER - Sending packet ----------------------");
      // TODO fix this, we should not specify the daemon port here!!
      DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress, 31012);
      socket.send(response);
    }
  }
}
