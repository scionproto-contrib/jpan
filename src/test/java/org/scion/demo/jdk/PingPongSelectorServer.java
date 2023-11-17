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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

/**
 * Same as PingPongChannelServer but uses a Selector. It can be used with the PingPongChannelClient.
 */
public class PingPongSelectorServer {

  public static void sendMessage(DatagramChannel channel, String msg, SocketAddress serverAddress)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
    channel.send(buffer, serverAddress);
    System.out.println("Sent to client at: " + serverAddress + "  message: " + msg);
  }

  public static void runServer() throws IOException {
    InetSocketAddress address = new InetSocketAddress("localhost", 44444);
    try (DatagramChannel chnLocal = DatagramChannel.open().bind(address);
        Selector selector = Selector.open()) {
      chnLocal.configureBlocking(false);
      chnLocal.register(selector, SelectionKey.OP_READ);
      ByteBuffer buffer = ByteBuffer.allocate(66000);

      while (true) {
        System.out.println("Waiting ...");
        if (selector.select() == 0) {
          // This must be an interrupt
          selector.close();
          return;
        }

        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        while (iter.hasNext()) {
          SelectionKey key = iter.next();
          if (key.isReadable()) {
            DatagramChannel channel = (DatagramChannel) key.channel();
            SocketAddress remoteAddress = channel.receive(buffer);
            if (remoteAddress == null) {
              throw new IllegalStateException();
            }

            buffer.flip();

            String message = extractMessage(buffer);
            System.out.println(
                "Received from client at: " + remoteAddress + "  message: " + message);

            sendMessage(channel, "Re: Hello scion", remoteAddress);
            buffer.clear();
          }
          iter.remove();
        }
      }
    }
  }

  private static String extractMessage(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return new String(bytes);
  }

  public static void main(String[] args) throws IOException {
    runServer();
  }
}
