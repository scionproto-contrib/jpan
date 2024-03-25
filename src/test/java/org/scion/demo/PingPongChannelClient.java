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
import java.nio.ByteBuffer;
import org.scion.*;
import org.scion.testutil.MockDNS;

public class PingPongChannelClient {

  public static boolean PRINT = true;
  public static DemoConstants.Network NETWORK = PingPongChannelServer.NETWORK;

  private static String extractMessage(ByteBuffer buffer) {
    buffer.flip();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return new String(bytes);
  }

  public static DatagramChannel startClient() throws IOException {
    DatagramChannel client = DatagramChannel.open().bind(null);
    client.configureBlocking(true);
    return client;
  }

  public static void sendMessage(DatagramChannel client, String msg, Path serverAddress)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
    client.send(buffer, serverAddress);
    println("Sent to server at: " + serverAddress + "  message: " + msg);
  }

  public static void receiveMessage(DatagramChannel channel) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    Path remoteAddress = channel.receive(buffer);
    String message = extractMessage(buffer);
    println("Received from server at: " + remoteAddress + "  message: " + message);
  }

  public static void main(String[] args) throws IOException {
    // Demo setup
    switch (NETWORK) {
      case MOCK_TOPOLOGY_IPV4:
        {
          DemoTopology.configureMock(true);
          MockDNS.install("1-ff00:0:112", "localhost", "127.0.0.1");
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
      case TINY_PROTO:
        {
          DemoTopology.configureTiny110_112();
          MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
          doClientStuff(DemoConstants.ia112);
          DemoTopology.shutDown();
          break;
        }
      case MINIMAL_PROTO:
        {
          Scion.newServiceWithTopologyFile("topologies/minimal/ASff00_0_1111/topology.json");
          // Scion.newServiceWithDaemon(DemoConstants.daemon1111_minimal);
          doClientStuff(DemoConstants.ia112);
          break;
        }
      default:
        throw new UnsupportedOperationException();
    }
  }

  private static void doClientStuff(long destinationIA) throws IOException {
    try (DatagramChannel channel = startClient()) {
      String msg = "Hello scion";
      InetSocketAddress serverAddress = PingPongChannelServer.getServerAddress(NETWORK);
      Path path = Scion.defaultService().getPaths(destinationIA, serverAddress).get(0);
      sendMessage(channel, msg, path);

      println("Waiting at " + channel.getLocalAddress() + " ...");
      receiveMessage(channel);
    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
