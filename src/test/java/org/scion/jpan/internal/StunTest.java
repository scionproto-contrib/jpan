// Copyright 2024 ETH Zurich
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

package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.file.Path;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.testutil.JsonFileParser;
import org.scion.jpan.testutil.MockNetwork;

class StunTest {

  @Disabled
  @Test
  void testPublicServers() throws IOException {
    Path file = JsonFileParser.toResourcePath("stun-servers.txt");

    BufferedReader br = new BufferedReader(new FileReader(file.toFile()));
    String st;
    int nTotal = 0;
    int nUnknownHost = 0;
    final ByteUtil.MutLong nNull = new ByteUtil.MutLong(0);
    final ByteUtil.MutLong nTimeout = new ByteUtil.MutLong(0);
    final ByteUtil.MutLong nSuccess = new ByteUtil.MutLong(0);
    while ((st = br.readLine()) != null) {
      nTotal++;
      System.out.print("Trying: " + st + " ... ");
      InetSocketAddress addr;
      if (st.startsWith("[") || Character.isDigit(st.charAt(0))) {
        addr = IPHelper.toInetSocketAddress(st);
      } else {
        try {
          int pos = st.indexOf(":");
          if (pos > 0) {
            InetAddress inet = InetAddress.getByName(st.substring(0, pos));
            int port = Integer.parseInt(st.substring(pos + 1));
            addr = new InetSocketAddress(inet, port);
          } else {
            InetAddress inet = InetAddress.getByName(st);
            addr = new InetSocketAddress(inet, 3478);
          }
        } catch (UnknownHostException e) {
          nUnknownHost++;
          System.out.println("Unknown host");
          continue;
        }
      }
      System.out.print(addr + " ... ");
      test(addr, nTimeout, nNull, nSuccess);
    }
    System.out.println("Summary");
    System.out.println("   total:        " + nTotal);
    System.out.println("   unknown host: " + nUnknownHost);
    System.out.println("   timeout:      " + nTimeout.get());
    System.out.println("   null:         " + nNull.get());
    System.out.println("   success:      " + nSuccess.get());
  }

  private void test(
      InetSocketAddress server,
      ByteUtil.MutLong nTimeout,
      ByteUtil.MutLong nNull,
      ByteUtil.MutLong nSuccess)
      throws IOException {
    ByteBuffer out = ByteBuffer.allocate(1000);
    STUN.TransactionID id = STUN.writeRequest(out);
    out.flip();

    try (DatagramSocket channel = new DatagramSocket()) {
      channel.setSoTimeout(1000);

      DatagramPacket pOut = new DatagramPacket(out.array(), out.remaining(), server);
      channel.send(pOut);

      ByteBuffer in = ByteBuffer.allocate(1000);
      DatagramPacket pIn = new DatagramPacket(new byte[1000], 1000);
      try {
        channel.receive(pIn);
      } catch (SocketTimeoutException e) {
        System.out.println("Timeout");
        nTimeout.v++;
        return;
      }
      //      InetSocketAddress server2 = (InetSocketAddress) pIn.getSocketAddress();
      //      System.out.println("Received from: " + server2);
      for (int i = 0; i < pIn.getLength(); i++) {
        in.put(pIn.getData()[i]);
      }
      in.flip();

      boolean isSTUN = STUN.isStunResponse(in, id);
      if (isSTUN) {
        InetSocketAddress external = STUN.parseResponse(in, id);
        if (external == null) {
          nNull.v++;
          System.out.println("NULL");
          return;
        }
        System.out.println("Address: " + external);
        nSuccess.v++;
      }
    }
  }

  @Test
  void test() throws IOException {
    ByteBuffer out = ByteBuffer.allocate(1000);
    STUN.TransactionID id = STUN.writeRequest(out);
    out.flip();

    assertTrue(STUN.isStunPacket(out, id));
    STUN.parseResponse(out, id);
    out.flip();

    // InetAddress addr = InetAddress.getByName("stun.solnet.ch");
    // InetAddress addr = InetAddress.getByName("stun.ipfire.org");
    // InetAddress addr = InetAddress.getByName("relay.webwormhole.io");
    // InetAddress addr = InetAddress.getByName("stun.zoiper.com"); //  XOR
    // InetAddress addr = InetAddress.getByName("stun.12connect.com"); // Vovida 0.98
    // InetAddress addr = InetAddress.getByName("stun.1und1.de");
    // stun.commpeak.com // unknown SOFTWARE
    // stun.counterpath.com // vovida.org 0.98-CPC
    String addrStr;
    // addrStr = "stun.linphone.org";    // DIfferent IP: 226.50.80.11:64249, oRTP 0.99
    // stun.solcon.nl          // DIfferent IP: 226.50.80.11:33989
    // stun.usfamily.net        // Different IP: 154.251.231.174:43532

    addrStr = "stun.ekiga.net"; // Has 2nd MAPPED address with wrong port.
    // addrStr = "stun.ippi.fr";  // ERROR code
    // addrStr = "stun.mywatson.it"; // ERROR code
    InetAddress addr = InetAddress.getByName(addrStr);
    InetSocketAddress server = new InetSocketAddress(addr, 3478);
    try (DatagramChannel channel = DatagramChannel.open()) {

      int sent = channel.send(out, server);
      System.out.println("Sent bytes: " + sent);

      System.out.println("Waiting ...");
      ByteBuffer in = ByteBuffer.allocate(1000);
      InetSocketAddress server2 = (InetSocketAddress) channel.receive(in);
      System.out.println("Received from: " + server2);
      in.flip();
      System.out.print("byte[] raw = {");
      for (int i = 0; i < in.remaining(); i++) {
        System.out.print(in.get(i) + ", ");
      }
      System.out.println("};");

      // Answer:
      byte[] raw = {
        1, 1, 0, 68, 66, -92, 18, 33, -122, 63, -4, -36, -46, -105, -40, -78, 104, 12, -106, 9, 0,
        1, 0, 8, 0, 1, -50, 39, -127, -124, -26, 73, 0, 4, 0, 8, 0, 1, 13, -105, -44, 101, 4, 120,
        0, 5, 0, 8, 0, 1, 13, -106, -44, 101, 4, 8, 0, 32, 0, 8, 0, 1, -116, -125, -61, 32, -12,
        104, -128, 34, 0, 16, 86, 111, 118, 105, 100, 97, 46, 111, 114, 103, 32, 48, 46, 57, 55, 0,
      };

      boolean isSTUN = STUN.isStunResponse(in, id);
      System.out.println("Is stun: " + isSTUN);
      if (isSTUN) {
        InetSocketAddress external = STUN.parseResponse(in, id);
        System.out.println("Address: " + external);
      }
    }
  }

  @Test
  void testBorderRouter() throws IOException {
    try (DatagramChannel channel = DatagramChannel.open()) {
      MockNetwork.startTiny();

      // send
      InetSocketAddress br = MockNetwork.getBorderRouterAddress1();
      ByteBuffer out = ByteBuffer.allocate(1000);
      STUN.TransactionID id = STUN.writeRequest(out);
      out.flip();
      channel.send(out, br);

      // receive
      ByteBuffer in = ByteBuffer.allocate(1000);
      InetSocketAddress server = (InetSocketAddress) channel.receive(in);
      assertEquals(br, server);
      in.flip();

      // check
      boolean isSTUN = STUN.isStunPacket(in, id);
      assertTrue(isSTUN);

      InetSocketAddress external = STUN.parseResponse(in, id);
      // We compare only the port, the IP may differ ("any" vs localhost, etc...)
      assertEquals(((InetSocketAddress) channel.getLocalAddress()).getPort(), external.getPort());
    } finally {
      MockNetwork.stopTiny();
    }
  }
}
