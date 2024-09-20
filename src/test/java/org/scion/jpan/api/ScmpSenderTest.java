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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.MockDatagramChannel;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockScmpHandler;

public class ScmpSenderTest {
  private static final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @AfterEach
  public void afterEach() {
    if (!errors.isEmpty()) {
      for (String s : errors) {
        System.err.println("ERROR: " + s);
      }
      fail(errors.poll());
    }
  }

  @Test
  void sendEcho() throws IOException {
    testEcho(this::getPathTo112);
  }

  @Test
  void sendEcho_localAS_BR() throws IOException {
    testEcho(this::getPathToLocalAS_BR);
    assertEquals(1, MockNetwork.getAndResetForwardCount()); // 1!
    assertEquals(1, MockScmpHandler.getAndResetAnswerTotal());
  }

  @Test
  void sendEcho_localAS_BR_30041() throws IOException {
    testEcho(this::getPathToLocalAS_BR_30041);
    assertEquals(0, MockNetwork.getAndResetForwardCount()); // 0!
    assertEquals(1, MockScmpHandler.getAndResetAnswerTotal());
  }

  private void testEcho(Supplier<Path> pathSupplier) throws IOException {
    MockNetwork.startTiny();
    MockNetwork.answerNextScmpEchos(1);
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      channel.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      byte[] data = new byte[] {1, 2, 3, 4, 5};
      Path path = pathSupplier.get();
      Scmp.EchoMessage result = channel.sendEchoRequest(path, ByteBuffer.wrap(data));
      assertEquals(0, result.getSequenceNumber());
      assertEquals(Scmp.TypeCode.TYPE_129, result.getTypeCode());
      assertTrue(result.getNanoSeconds() > 0);
      assertTrue(result.getNanoSeconds() < 100_000_000); // 10 ms
      assertArrayEquals(data, result.getData());
      assertFalse(result.isTimedOut());
      Path returnPath = result.getPath();
      assertEquals(path.getRemoteAddress(), returnPath.getRemoteAddress());
      assertEquals(Constants.SCMP_PORT, returnPath.getRemotePort());
      assertEquals(path.getRemoteIsdAs(), returnPath.getRemoteIsdAs());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendEcho_timeout() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      channel.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      channel.setTimeOut(100);
      MockNetwork.dropNextPackets(1);
      Scmp.EchoMessage result1 = channel.sendEchoRequest(path, ByteBuffer.allocate(0));
      assertTrue(result1.isTimedOut());
      assertEquals(100 * 1_000_000, result1.getNanoSeconds());
      assertEquals(0, result1.getSequenceNumber());

      // try again
      MockNetwork.answerNextScmpEchos(1);
      Scmp.EchoMessage result2 = channel.sendEchoRequest(path, ByteBuffer.allocate(0));
      assertEquals(Scmp.TypeCode.TYPE_129, result2.getTypeCode());
      assertFalse(result2.isTimedOut());
      assertTrue(result2.getNanoSeconds() > 0);
      assertTrue(result2.getNanoSeconds() < 10_000_000); // 10 ms
      assertEquals(1, result2.getSequenceNumber());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendEcho_IOException() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    try (ScmpSender channel = exceptionSender()) {
      channel.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // TODO MockNetwork.stopTiny();
      // Exception because network is down.
      ScmpSenderAsync.PRINT = 21;
      Throwable t =
          assertThrows(
              IOException.class, () -> channel.sendEchoRequest(path, ByteBuffer.allocate(0)));
      System.err.println("SYNC EC:  " + t.getMessage()); // TODO
      t.printStackTrace(); // TODO
    } finally {
      ScmpSenderAsync.PRINT = 0;
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendEcho_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // Router will return SCMP error
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.TypeCode.TYPE_1_CODE_0);
      Throwable t =
          assertThrows(
              IOException.class,
              () -> channel.sendEchoRequest(getPathTo112(), ByteBuffer.allocate(0)));
      assertTrue(listenerWasTriggered.get());
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_1_CODE_0.getText()));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTraceroute() throws IOException {
    testTraceroute(this::getPathTo112, 2);
  }

  @Test
  void sendTraceroute_localAS_BR() throws IOException {
    testTraceroute(this::getPathToLocalAS_BR, 0);
  }

  private void testTraceroute(Supplier<Path> path, int nHops) throws IOException {
    MockNetwork.startTiny();
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      channel.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      Collection<Scmp.TracerouteMessage> results = channel.sendTracerouteRequest(path.get());

      int n = 0;
      for (Scmp.TracerouteMessage result : results) {
        assertEquals(n++, result.getSequenceNumber());
        assertEquals(Scmp.TypeCode.TYPE_131, result.getTypeCode());
        assertTrue(result.getNanoSeconds() > 0);
        assertTrue(result.getNanoSeconds() < 10_000_000); // 10 ms
        assertFalse(result.isTimedOut());
        if (n == 1) {
          assertEquals(ScionUtil.parseIA("1-ff00:0:112"), result.getIsdAs());
          assertEquals(42, result.getIfID());
        }
        if (n == 2) {
          assertEquals(ScionUtil.parseIA("1-ff00:0:110"), result.getIsdAs());
          assertEquals(42, result.getIfID());
        }
      }

      assertEquals(nHops, results.size());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTraceroute_timeout() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      channel.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      assertEquals(1000, channel.getTimeOut());
      channel.setTimeOut(100);
      assertEquals(100, channel.getTimeOut());
      MockNetwork.dropNextPackets(2);
      Collection<Scmp.TracerouteMessage> results1 = channel.sendTracerouteRequest(path);
      assertEquals(2, results1.size());
      for (Scmp.TracerouteMessage result : results1) {
        assertTrue(result.isTimedOut());
        assertEquals(Scmp.TypeCode.TYPE_130, result.getTypeCode());
        assertEquals(100 * 1_000_000, result.getNanoSeconds());
      }

      // retry
      Collection<Scmp.TracerouteMessage> results2 = channel.sendTracerouteRequest(path);
      assertEquals(2, results2.size());
      for (Scmp.TracerouteMessage result : results2) {
        assertFalse(result.isTimedOut());
        assertEquals(Scmp.TypeCode.TYPE_131, result.getTypeCode());
      }
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTraceroute_IOException() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    try (ScmpSender channel = exceptionSender()) {
      channel.setScmpErrorListener(scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // TODO  MockNetwork.stopTiny();
      // IOException because network is down
      Throwable t = assertThrows(IOException.class, () -> channel.sendTracerouteRequest(path));
      System.err.println("SYNC TR:  " + t.getMessage()); // TODO
      t.printStackTrace(); // TODO
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void sendTraceroute_SCMP_error() throws IOException {
    MockNetwork.startTiny();
    Path path = getPathTo112();
    try (ScmpSender channel = Scmp.newSenderBuilder().build()) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      // Router will return SCMP error
      MockNetwork.returnScmpErrorOnNextPacket(Scmp.TypeCode.TYPE_1_CODE_0);
      Throwable t = assertThrows(IOException.class, () -> channel.sendTracerouteRequest(path));
      assertTrue(listenerWasTriggered.get());
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_1_CODE_0.getText()));
    } finally {
      MockNetwork.stopTiny();
    }
  }

  private Path getPathTo112() {
    try {
      InetAddress zero = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
      return getPathTo112(zero);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private Path getPathTo112(InetAddress dstAddress) {
    ScionService service = Scion.defaultService();
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    return service.getPaths(dstIA, dstAddress, Constants.SCMP_PORT).get(0);
  }

  private Path getPathToLocalAS_BR() {
    // Border router address
    return getPathToLocalAS(MockNetwork.BORDER_ROUTER_IPV4, MockNetwork.BORDER_ROUTER_PORT1);
  }

  private Path getPathToLocalAS_BR_30041() {
    // Border router address
    return getPathToLocalAS(MockNetwork.BORDER_ROUTER_IPV4, Constants.SCMP_PORT);
  }

  private Path getPathToLocalAS(String addressStr, int port) {
    ScionService service = Scion.defaultService();
    long dstIA = service.getLocalIsdAs();
    try {
      // Service address
      InetAddress addr = InetAddress.getByName(addressStr);
      List<Path> paths = service.getPaths(dstIA, new InetSocketAddress(addr, port));
      return paths.get(0);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  private ScmpSender exceptionSender() throws IOException {
    MockDatagramChannel errorChannel = MockDatagramChannel.open();
    errorChannel.setSendCallback(
        (byteBuffer, socketAddress) -> {
          return 0;
        });
    errorChannel.setThrowOnConnect(true);
    // This selector throws an Exception when activated.
    MockDatagramChannel.MockSelector selector = MockDatagramChannel.MockSelector.open();
    return Scmp.newSenderBuilder().setDatagramChannel(errorChannel).setSelector(selector).build();
  }
}
