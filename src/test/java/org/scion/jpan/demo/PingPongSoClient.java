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
import java.util.Arrays;

import org.scion.jpan.*;
import org.scion.jpan.socket.DatagramSocket;
import org.scion.jpan.testutil.MockDNS;

/**
 * Example of client/server using SCION. <br>
 * - MOCK_TOPOLOGY and MOCK_TOPOLOGY_IPV4 use the JUnit mock network of this library. <br>
 * - SCION_PROTO works with a local topology from the scionproto golang implementation such as
 * "tiny". Note that the constants for "minimal" differ somewhat from the scionproto topology, see
 * <a href="https://github.com/scionproto/scion">...</a>.
 */
public class PingPongSoClient {

  public static boolean PRINT = true;
  public static DemoConstants.Network NETWORK = PingPongSoServer.NETWORK;

  private static String extractMessage(DatagramPacket buffer) {
    return new String(Arrays.copyOf(buffer.getData(), buffer.getLength()));
  }

  public static DatagramChannel startClient() throws IOException {
    DatagramChannel client = DatagramChannel.open().bind(null);
    client.configureBlocking(true);
    return client;
  }

  public static void sendMessage(DatagramSocket client, String msg, Path serverAddress)
      throws IOException {
    DatagramPacket buffer =
        new DatagramPacket(
            msg.getBytes(),
            0,
            msg.length(),
            serverAddress.getDestinationAddress(),
            serverAddress.getDestinationPort());
    client.send(buffer);
    println("Sent to server at: " + serverAddress + "  message: " + msg);
  }

  public static void receiveMessage(DatagramSocket socket) throws IOException {
    DatagramPacket buffer = new DatagramPacket(new byte[100], 100);
    socket.receive(buffer);
    String message = extractMessage(buffer);
    println("Received from server at: " + buffer.getSocketAddress() + "  message: " + message);
  }

  public static void main(String[] args) {
    try {
      run();
    } catch (IOException e) {
      println(e.getMessage());
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  private static void run() throws IOException {
    // Demo setup
    switch (NETWORK) {
      case MOCK_TOPOLOGY_IPV4:
        {
          DemoTopology.configureMock(true);
          InetSocketAddress addr = PingPongSoServer.getServerAddress();
          MockDNS.install("1-ff00:0:112", addr.getHostName(), addr.getAddress().getHostAddress());
          //MockDNS.install("1-ff00:0:112", "localhost", "127.0.0.1");
          //MockDNS.install("1-ff00:0:112", "127.0.0.1", "127.0.0.1");
          doClientStuff(DemoConstants.ia112);
          DemoTopology.shutDown();
          break;
        }
      case MOCK_TOPOLOGY:
        {
          DemoTopology.configureMock();
          MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
          doClientStuff(DemoConstants.ia112);
          DemoTopology.shutDown();
          break;
        }
      case SCION_PROTO:
        {
          System.setProperty(
              Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
              "topologies/minimal/ASff00_0_1111/topology.json");
          // System.setProperty(Constants.PROPERTY_DAEMON, DemoConstants.daemon1111_minimal);
          doClientStuff(DemoConstants.ia112);
          break;
        }
      default:
        throw new UnsupportedOperationException();
    }
  }

  private static void doClientStuff(long destinationIA) throws IOException {
    println("Client starting ...");
    try (DatagramSocket socket = new DatagramSocket(null)) {
      String msg = "Hello scion";
      println("Client getting server address " + socket.getLocalAddress() + " ...");
      InetSocketAddress serverAddress = PingPongSoServer.getServerAddress();
      println("Client got server address " + serverAddress);
      println(
          "Client getting path to "
              + ScionUtil.toStringIA(destinationIA)
              + ","
              + serverAddress
              + " ...");
      Path path = Scion.defaultService().getPaths(destinationIA, serverAddress).get(0);
      println("Client got path:  " + path);
      println("Client sending from " + socket.getLocalAddress() + " ...");
      sendMessage(socket, msg, path);

      println("Client waiting at " + socket.getLocalAddress() + " ...");
      receiveMessage(socket);
    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
