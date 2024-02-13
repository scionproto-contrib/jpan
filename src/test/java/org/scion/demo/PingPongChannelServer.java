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
import org.scion.DatagramChannel;
import org.scion.Path;

public class PingPongChannelServer {

  public static boolean PRINT = true;
  private static final int SERVER_PORT = 44444;
  public static DemoConstants.Network NETWORK = DemoConstants.Network.MINIMAL_PROTO;

  public static InetSocketAddress getServerAddress(DemoConstants.Network network) {
    switch (network) {
      case MOCK_TOPOLOGY:
      case TINY_PROTO:
        return new InetSocketAddress("::1", SERVER_PORT);
      case MOCK_TOPOLOGY_IPV4:
      case MINIMAL_PROTO:
        return new InetSocketAddress("127.0.0.1", SERVER_PORT);
      default:
        throw new UnsupportedOperationException();
    }
  }

  private static DatagramChannel startServer() throws IOException {
    InetSocketAddress address = getServerAddress(NETWORK);
    DatagramChannel server = DatagramChannel.open().bind(address);
    println("Server started at: " + address);
    return server;
  }

  private static void sendMessage(DatagramChannel channel, String msg, Path path)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
    channel.send(buffer, path);
    println("Sent to client at: " + path + "  message: " + msg);
  }

  private static Path receiveMessage(DatagramChannel server) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    println("Waiting ...");

    Path remoteAddress = server.receive(buffer);
    String message = extractMessage(buffer);
    println("Received from client at: " + remoteAddress + "  message: " + message);

    return remoteAddress;
  }

  private static String extractMessage(ByteBuffer buffer) {
    buffer.flip();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return new String(bytes);
  }

  public static void main(String[] args) throws IOException {
    try (DatagramChannel channel = startServer()) {
      Path remoteAddress = receiveMessage(channel);
      sendMessage(channel, "Re: Hello scion", remoteAddress);
    }
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
