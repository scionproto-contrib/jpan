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
import org.scion.testutil.MockDNS;

public class ScionPingPongChannelClient {

  public static boolean PRINT = true;

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

  public static void sendMessage(
      DatagramChannel client, String msg, InetSocketAddress serverAddress) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
    client.send(buffer, serverAddress);
    if (PRINT) {
      System.out.println("Sent to server at: " + serverAddress + "  message: " + msg);
    }
  }

  public static void receiveMessage(DatagramChannel channel) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    Path remoteAddress = channel.receive(buffer);
    String message = extractMessage(buffer);
    if (PRINT) {
      System.out.println("Received from server at: " + remoteAddress + "  message: " + message);
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    // True: connect to ScionPingPongChannelServer via Java mock topology
    // False: connect to any service via ScionProto "tiny" topology
    boolean useMockTopology = true;
    // Demo setup
    if (useMockTopology) {
      DemoTopology.configureMock();
      MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
      doClientStuff(44444);
      DemoTopology.shutDown();
    } else {
      DemoTopology.configureTiny110_112();
      MockDNS.install("1-ff00:0:112", "0:0:0:0:0:0:0:1", "::1");
      doClientStuff(8080);
      DemoTopology.shutDown();
    }
  }

  private static void doClientStuff(int port) throws IOException {
    DatagramChannel channel = startClient();
    String msg = "Hello scion";
    InetSocketAddress serverAddress = new InetSocketAddress("::1", port);
    // long isdAs = ScionUtil.parseIA("1-ff00:0:112");
    // ScionSocketAddress serverAddress = ScionSocketAddress.create(isdAs, "::1", 44444);

    sendMessage(channel, msg, serverAddress);

    if (PRINT) {
      System.out.println("Waiting ...");
    }
    receiveMessage(channel);

    channel.disconnect();
  }
}
