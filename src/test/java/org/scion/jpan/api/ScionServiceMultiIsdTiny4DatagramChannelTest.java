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
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.MockNetwork2;

class ScionServiceMultiIsdTiny4DatagramChannelTest {

  private static final long AS_111 = ScionUtil.parseIA("1-ff00:0:111");
  private static final long AS_112 = ScionUtil.parseIA("1-ff00:0:112");
  private static final long AS_110 = ScionUtil.parseIA("1-ff00:0:110");

  @AfterEach
  void afterEach() {
    Scion.closeDefault();
  }

  @Test
  void sendReceive_pathServiceHandlesMultipleLocalSourceAses_tiny4() throws IOException {

    // TODO verufy this test works, e.g. the server receives paths from two different ASes.

    ManagedThread serverThread = ManagedThread.newBuilder().build();
    try (MockNetwork2 nw =
            MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_111", "ASff00_0_112");
        ScionDatagramChannel client = ScionDatagramChannel.open()) {
      nw.startBorderRouters();

      final InetSocketAddress serverAddress =
          new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
      final Set<Long> receivedSourceAses = new HashSet<>();
      final Set<String> receivedPayloads = new HashSet<>();

      serverThread.submit(
          news -> {
            ScionService service110 =
                Scion.newServiceWithTopologyFile("topologies/tiny4/ASff00_0_110/topology.json");
            try (ScionDatagramChannel server = ScionDatagramChannel.open(service110)) {
              server.bind(serverAddress);
              news.reportStarted();
              for (int i = 0; i < 2; i++) {
                ByteBuffer recvBuffer = ByteBuffer.allocate(64);
                ScionSocketAddress remoteAddress = server.receive(recvBuffer);
                recvBuffer.flip();
                byte[] bytes = new byte[recvBuffer.remaining()];
                recvBuffer.get(bytes);
                receivedPayloads.add(new String(bytes, StandardCharsets.UTF_8));
                receivedSourceAses.add(remoteAddress.getIsdAs());
              }
            } catch (Exception e) {
              news.reportException(e);
            }
          });

      ScionService service = Scion.defaultService();
      List<Path> paths = service.getPaths(AS_110, serverAddress);
      assertEquals(2, paths.size());

      Map<Long, Path> pathBySourceAs =
          paths.stream().collect(Collectors.toMap(Path::getLocalIsdAs, Function.identity()));
      assertTrue(pathBySourceAs.containsKey(AS_111));
      assertTrue(pathBySourceAs.containsKey(AS_112));

      client.send(
          ByteBuffer.wrap("from-111".getBytes(StandardCharsets.UTF_8)), pathBySourceAs.get(AS_111));
      client.send(
          ByteBuffer.wrap("from-112".getBytes(StandardCharsets.UTF_8)), pathBySourceAs.get(AS_112));
      serverThread.join();

      assertEquals(new HashSet<>(Arrays.asList(AS_111, AS_112)), receivedSourceAses);
      assertEquals(new HashSet<>(Arrays.asList("from-111", "from-112")), receivedPayloads);
    } finally {
      serverThread.stopNow();
    }
  }
}
