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
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.*;

public class PingPongChannelServer {
  public static DatagramChannel startServer() throws IOException {
    InetSocketAddress address = new InetSocketAddress("localhost", 44444);
    DatagramChannel server = DatagramChannel.open().bind(address);

    System.out.println("Server started at: " + server.getLocalAddress());

    return server;
  }

  public static void sendMessage(DatagramChannel channel, String msg, SocketAddress serverAddress)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
    channel.send(buffer, serverAddress);
    System.out.println("Sent to client at: " + serverAddress + "  message: " + msg);
  }

  public static SocketAddress receiveMessage(DatagramChannel server) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    System.out.println("Waiting ...");
    SocketAddress remoteAddress = server.receive(buffer);
    String message = extractMessage(buffer);

    System.out.println("Received from client at: " + remoteAddress + "  message: " + message);

    return remoteAddress;
  }

  private static String extractMessage(ByteBuffer buffer) {
    buffer.flip();

    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);

    return new String(bytes);
  }

  public static void main(String[] args) throws IOException {
    DatagramChannel channel = startServer();
    SocketAddress remoteAddress = receiveMessage(channel);
    sendMessage(channel, "Re: Hello scion", remoteAddress);
    channel.disconnect();
  }
}
