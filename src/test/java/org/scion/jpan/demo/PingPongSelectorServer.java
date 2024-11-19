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
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

import org.scion.jpan.*;
import org.scion.jpan.testutil.MockNetwork;

public class PingPongSelectorServer {

  public static boolean PRINT = true;

  public static final String SERVER_HOST_NAME = "ping.pong.org";
  public static final InetSocketAddress SERVER_ADDRESS;
  public static final int SERVER_PORT = 44444;

  static {
    try {
      byte[] serverIP = InetAddress.getLoopbackAddress().getAddress();
      InetAddress address = InetAddress.getByAddress(SERVER_HOST_NAME, serverIP);
      SERVER_ADDRESS = new InetSocketAddress(address, SERVER_PORT);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String[] args) {
    try {
      service();
    } catch (SocketException ex) {
      System.out.println("Socket error: " + ex.getMessage());
    } catch (IOException ex) {
      System.out.println("I/O error: " + ex.getMessage());
    }
  }

  private static void service() throws IOException {
//    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
//      channel.bind(SERVER_ADDRESS);
//      ByteBuffer buffer = ByteBuffer.allocate(100);
//      println("Waiting for packet ... ");
//      ScionSocketAddress responseAddress = channel.receive(buffer);
//      Path path = responseAddress.getPath();
//      String msg = extractMessage(buffer);
//      String remoteAddress = path.getRemoteAddress() + ":" + path.getRemotePort();
//      String borderRouterInterfaces = ScionUtil.toStringPath(path.getRawPath());
//      println("Received (from " + remoteAddress + ") via " + borderRouterInterfaces + "): " + msg);
//
//      String msgAnswer = "Re: " + msg;
//      channel.send(ByteBuffer.wrap(msgAnswer.getBytes()), path);
//      println("Sent answer: " + msgAnswer);
//    }

    try (ScionDatagramChannel chn = ScionDatagramChannel.open().bind(SERVER_ADDRESS);
         Selector selector = ScionSelector.open()) {
      chn.configureBlocking(false);
      chn.register(selector, SelectionKey.OP_READ, null);
      ByteBuffer buffer = ByteBuffer.allocate(66000);
      println("Waiting for packet ... ");

      while (true) {
        if (selector.select() == 0) {
          // This must be an interrupt
          selector.close();
          return;
        }

        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        while (iter.hasNext()) {
          SelectionKey key = iter.next();
          if (key.isReadable()) {
            ScionDatagramChannel incoming = (ScionDatagramChannel) key.channel();
            ScionSocketAddress srcAddress = incoming.receive(buffer);
            if (srcAddress == null) {
              throw new IllegalStateException();
            }
            buffer.flip();

            Path path = srcAddress.getPath();
            String msg = extractMessage(buffer);
            String remoteAddress = path.getRemoteAddress() + ":" + path.getRemotePort();
            String borderRouterInterfaces = ScionUtil.toStringPath(path.getRawPath());
            println("Received (from " + remoteAddress + ") via " + borderRouterInterfaces + "): " + msg);

            String msgAnswer = "Re: " + msg;
            chn.send(ByteBuffer.wrap(msgAnswer.getBytes()), path);
            println("Sent answer: " + msgAnswer);
          }
          iter.remove();
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      println("Shutting down router");
    }
  }

  private static String extractMessage(ByteBuffer buffer) {
    buffer.flip();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return new String(bytes);
  }

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }
}
