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
import java.util.Arrays;
import org.scion.DatagramChannel;
import org.scion.ResponsePath;
import org.scion.ScionUtil;
import org.scion.Scmp;

/** A demo server that responds to SCMP ECHO and TRACEROUTE requests. */
public class ScmpServerDemo {

  public static final int PORT = 55555;
  public static final String hostName = "::1";

  public static boolean PRINT = true;
  private DatagramChannel channel;

  public void reflectEcho(Scmp.ScmpEcho msg) {
    try {
      ResponsePath path = (ResponsePath) msg.getPath();
      if (PRINT) {
        System.out.println(
            "Received ECHO from client: "
                + ScionUtil.toStringIA(path.getDestinationIsdAs())
                + " "
                + Arrays.toString(path.getDestinationAddress())
                + " "
                + path.getDestinationPort());
      }
      ByteBuffer data = ByteBuffer.wrap(msg.getData());
      channel.sendEchoRequest(path, msg.getSequenceNumber(), data);
      if (PRINT) {
        System.out.println("Sent ECHO to client");
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void reflectTraceroute(Scmp.ScmpTraceroute msg) {
    try {
      ResponsePath path = (ResponsePath) msg.getPath();
      if (PRINT) {
        System.out.println(
            "Received TRACEROUTE from client: "
                + ScionUtil.toStringIA(path.getDestinationIsdAs())
                + " "
                + Arrays.toString(path.getDestinationAddress())
                + " "
                + path.getDestinationPort());
      }
      channel.sendTracerouteRequest(path, msg.getSequenceNumber());
      if (PRINT) {
        System.out.println("Sent TRACEROUTE to client");
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String[] args) throws IOException {
    new ScmpServerDemo().run();
  }

  private void run() throws IOException {
    InetSocketAddress address = new InetSocketAddress(hostName, PORT);
    try (DatagramChannel channel = DatagramChannel.open().bind(address)) {
      this.channel = channel;
      channel.setEchoListener(this::reflectEcho);
      channel.setTracerouteListener(this::reflectTraceroute);
      if (PRINT) {
        System.out.println("Server started at: " + address);
      }

      channel.receive(null);

      channel.disconnect();
    }
  }
}
