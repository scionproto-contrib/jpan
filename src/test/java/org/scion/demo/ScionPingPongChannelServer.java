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

public class ScionPingPongChannelServer {

  public static boolean PRINT = true;

  public static DatagramChannel startServer() throws IOException {
    // InetSocketAddress address = new InetSocketAddress("localhost", 44444);
    InetSocketAddress address = new InetSocketAddress("::1", 44444);
    DatagramChannel server = DatagramChannel.open().bind(address);

    if (PRINT) {
      System.out.println("Server started at: " + address);
    }

    return server;
  }

  public static void sendMessage(
      DatagramChannel channel, String msg, InetSocketAddress serverAddress) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
    channel.send(buffer, serverAddress);
    if (PRINT) {
      System.out.println("Sent to client at: " + serverAddress + "  message: " + msg);
    }
  }

  public static InetSocketAddress receiveMessage(DatagramChannel server) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    if (PRINT) {
      System.out.println("Waiting ...");
    }
    InetSocketAddress remoteAddress = server.receive(buffer);
    String message = extractMessage(buffer);
    if (PRINT) {
      System.out.println("Received from client at: " + remoteAddress + "  message: " + message);
    }
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
    InetSocketAddress remoteAddress = receiveMessage(channel);
    sendMessage(channel, "Re: Hello scion", remoteAddress);
    channel.disconnect();
  }
}
