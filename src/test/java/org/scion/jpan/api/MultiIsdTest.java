// Copyright 2026 ETH Zurich
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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.internal.PathProvider;
import org.scion.jpan.testutil.*;

class MultiIsdTest {

  /**
   * This test tries to verify that a client can be in multiple AS at the same time.
   * We test this by having the client in AS 111 and 112, with a server in 110.
   * <p>
   * For P-ISDs, the AS numbers should be the same, only the ISD number should differ.
   * However, the new endhost API allows AS as well as ISD to differ.
   *
   */
  private static final long AS_111 = ScionUtil.parseIA("1-ff00:0:111");
  private static final long AS_112 = ScionUtil.parseIA("1-ff00:0:112");
  private static final long AS_110 = ScionUtil.parseIA("1-ff00:0:110");

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  @Test
  void sendReceive_scionDatagramChannel() throws IOException {
    Client test =
        (pathBySourceAs) -> {
          try (ScionDatagramChannel client = ScionDatagramChannel.open()) {
            client.send(ByteBuffer.wrap("from-111".getBytes()), pathBySourceAs.get(AS_111));
            assertEquals("from-111", receiveString(client));
            client.send(ByteBuffer.wrap("from-112".getBytes()), pathBySourceAs.get(AS_112));
            assertEquals("from-112", receiveString(client));
          }
        };
    test(test);
  }

  @Test
  void readWrite_scionDatagramChannel_pathProvider() throws IOException {
    Client test =
        (pathBySourceAs) -> {
          Path p111 = pathBySourceAs.get(AS_111);
          Path p112 = pathBySourceAs.get(AS_112);
          PathProvider pp = PathProviderRotator.create(Arrays.asList(p111, p112));
          try (ScionDatagramChannel client =
              ScionDatagramChannel.newBuilder().provider(pp).open()) {
            client.connect(pathBySourceAs.get(AS_111));
            ByteBuffer bb111 = ByteBuffer.wrap("from-111".getBytes());
            bb111.flip();
            client.write(bb111);
            System.err.println("CL ----------------- 12 --- " + client.getLocalAddress());
            assertEquals("from-111", readString(client));

            long ifId = p111.getMetadata().getInterfaces().get(0).getId();
            pp.reportError(Scmp.Error5Message.create(p111, p111.getRemoteIsdAs(), ifId));

            client.connect(pathBySourceAs.get(AS_112));
            ByteBuffer bb112 = ByteBuffer.wrap("from-112".getBytes());
            bb112.flip();
            client.write(bb112);
            System.err.println("CL ----------------- 16 --- ");
            assertEquals("from-112", readString(client));
          }
        };
    test(test);
  }

  @Test
  void readWrite_scionDatagramChannel_connect_disconnect() throws IOException {
    Client test =
        (pathBySourceAs) -> {
          try (ScionDatagramChannel client = ScionDatagramChannel.open()) {
            client.connect(pathBySourceAs.get(AS_111));
            client.write(ByteBuffer.wrap("from-111".getBytes()));
            assertEquals("from-111", readString(client));
            client.disconnect();
            client.connect(pathBySourceAs.get(AS_112));
            client.write(ByteBuffer.wrap("from-112".getBytes()));
            assertEquals("from-112", readString(client));
          }
        };
    test(test);
  }

  @Test
  void sendReceive_scionDatagramSocket_pathProvider() throws IOException {
    Client test =
        (pathBySourceAs) -> {
          Path p111 = pathBySourceAs.get(AS_111);
          Path p112 = pathBySourceAs.get(AS_112);
          PathProvider pp = PathProviderRotator.create(Arrays.asList(p111, p112));
          try (ScionDatagramSocket client = ScionDatagramSocket.newBuilder().provider(pp).open()) {
            client.connect(p111);
            String msg1 = "from-111";
            InetSocketAddress a1 = pathBySourceAs.get(AS_111).getRemoteSocketAddress();
            DatagramPacket p1 = new DatagramPacket(msg1.getBytes(), msg1.length(), a1);
            client.send(p1);
            assertEquals("from-111", readString(client));

            long ifId = p111.getMetadata().getInterfaces().get(0).getId();
            pp.reportError(Scmp.Error5Message.create(p111, p111.getRemoteIsdAs(), ifId));

            String msg2 = "from-112";
            InetSocketAddress a2 = pathBySourceAs.get(AS_112).getRemoteSocketAddress();
            DatagramPacket p2 = new DatagramPacket(msg2.getBytes(), msg2.length(), a2);
            client.send(p2);
            assertEquals("from-112", readString(client));
          } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
          }
        };
    test(test);
  }

  @Test
  void sendReceive_scionDatagramSocket_connect_disconnect() throws IOException {
    Client test =
        (pathBySourceAs) -> {
          try (ScionDatagramSocket client = new ScionDatagramSocket()) {
            client.connect(pathBySourceAs.get(AS_111));
            String msg1 = "from-111";
            InetSocketAddress a1 = pathBySourceAs.get(AS_111).getRemoteSocketAddress();
            DatagramPacket p1 = new DatagramPacket(msg1.getBytes(), msg1.length(), a1);
            client.send(p1);
            assertEquals("from-111", readString(client));
            client.disconnect();
            client.connect(pathBySourceAs.get(AS_112));
            String msg2 = "from-112";
            InetSocketAddress a2 = pathBySourceAs.get(AS_111).getRemoteSocketAddress();
            DatagramPacket p2 = new DatagramPacket(msg2.getBytes(), msg2.length(), a2);
            client.send(p2);
            assertEquals("from-112", readString(client));
          } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
          }
        };
    test(test);
  }

  private interface Client {
    void call(Map<Long, Path> pathBySourceAs) throws IOException;
  }

  void test(Client clientFn) throws IOException {
    ManagedThread serverThread = ManagedThread.newBuilder().build();
    try (MockNetwork2 nw =
        MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_111", "ASff00_0_112")) {
      nw.startBorderRouters();

      final InetSocketAddress serverAddress =
          new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      final Set<Long> receivedSourceAses = new HashSet<>();
      final Set<String> receivedPayloads = new HashSet<>();

      serverThread.submit(mtn -> server(mtn, serverAddress, receivedSourceAses, receivedPayloads));
      ScionService service = Scion.defaultService();
      List<Path> paths = service.getPaths(AS_110, serverAddress);
      assertEquals(2, paths.size());

      Map<Long, Path> pathBySourceAs =
          paths.stream().collect(Collectors.toMap(Path::getLocalIsdAs, Function.identity()));
      assertTrue(pathBySourceAs.containsKey(AS_111));
      assertTrue(pathBySourceAs.containsKey(AS_112));

      // Run client
      clientFn.call(pathBySourceAs);
      serverThread.join();

      assertEquals(new HashSet<>(Arrays.asList(AS_111, AS_112)), receivedSourceAses);
      assertEquals(new HashSet<>(Arrays.asList("from-111", "from-112")), receivedPayloads);

      // Test that exactly two BRs forwarded two packets each.
      int numberOfRoutersWithTwoForwards = 0;
      for (MockBorderRouter br : nw.getBorderRouters()) {
        if (br.getForwardCount() == 2) {
          numberOfRoutersWithTwoForwards++;
        } else {
          assertEquals(0, br.getForwardCount());
        }
      }
      assertEquals(2, numberOfRoutersWithTwoForwards);
    } catch (Exception e) {
      e.printStackTrace();
      fail(); // TODO remove?
    } finally {
      serverThread.stopNow();
    }
  }

  private String readString(ScionDatagramChannel channel) throws IOException {
    ByteBuffer receiveBuffer = ByteBuffer.allocate(100);
    channel.read(receiveBuffer);
    receiveBuffer.flip();
    byte[] bytes = new byte[receiveBuffer.remaining()];
    receiveBuffer.get(bytes);
    return new String(bytes);
  }

  private String receiveString(ScionDatagramChannel channel) throws IOException {
    ByteBuffer receiveBuffer = ByteBuffer.allocate(100);
    channel.receive(receiveBuffer);
    receiveBuffer.flip();
    byte[] bytes = new byte[receiveBuffer.remaining()];
    receiveBuffer.get(bytes);
    return new String(bytes);
  }

  private String readString(ScionDatagramSocket socket) throws IOException {
    DatagramPacket p = new DatagramPacket(new byte[500], 500);
    socket.receive(p);
    return new String(p.getData(), 0, p.getLength());
  }

  private void server(
      ManagedThreadNews mtn,
      InetSocketAddress serverAddress,
      Set<Long> receivedSourceAses,
      Set<String> receivedPayloads) {
    ScionService service110 =
        Scion.newServiceWithTopologyFile("topologies/tiny4/ASff00_0_110/topology.json");
    try (ScionDatagramChannel server = ScionDatagramChannel.open(service110)) {
      server.bind(serverAddress);
      mtn.reportStarted();
      for (int i = 0; i < 2; i++) {
        ByteBuffer recvBuffer = ByteBuffer.allocate(100);
        ScionSocketAddress remoteAddress = server.receive(recvBuffer);
        recvBuffer.flip();
        byte[] bytes = new byte[recvBuffer.remaining()];
        recvBuffer.get(bytes);
        receivedPayloads.add(new String(bytes));
        receivedSourceAses.add(remoteAddress.getIsdAs());
        recvBuffer.rewind();
        server.send(recvBuffer, remoteAddress);
      }
    } catch (Exception e) {
      mtn.reportException(e);
    }
  }
}
