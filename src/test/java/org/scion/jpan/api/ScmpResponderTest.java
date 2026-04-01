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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.internal.Shim;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.ManagedThreadNews;
import org.scion.jpan.testutil.MockDatagramChannel;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockScmpHandler;

class ScmpResponderTest {
  private static final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

  @BeforeEach
  void beforeEach() {
    System.setProperty(Constants.PROPERTY_SHIM, "false");
    Shim.uninstall();
  }

  @AfterAll
  static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
    System.clearProperty(Constants.PROPERTY_SHIM);
  }

  @AfterEach
  void afterEach() {
    MockNetwork.stopTiny();
    if (!errors.isEmpty()) {
      for (String s : errors) {
        System.err.println("ERROR: " + s);
      }
      fail(errors.poll());
    }
  }

  private Path getPathTo112(InetAddress dstAddress) {
    ScionService service = Scion.defaultService();
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    return service.getPaths(dstIA, dstAddress, Constants.SCMP_PORT).get(0);
  }

  @Test
  void testEcho() throws IOException {
    testEcho(null);
  }

  @Test
  void testEcho_lowPort() throws IOException {
    testEcho(12345);
  }

  @Test
  void testEcho_highPort() throws IOException {
    // Test that a "high" port is handled correctly, i.e. that it is correctly encoded in 16bits
    testEcho(55555);
  }

  private void testEcho(Integer port) throws IOException {
    MockNetwork.startTiny();
    MockScmpHandler.stop(); // Shut down SCMP handler
    Path path = getPathTo112(InetAddress.getLoopbackAddress());
    // sender is in 110; responder is in 112
    ManagedThread responder = ManagedThread.newBuilder().build();
    try (ScmpSender sender = Scmp.newSenderBuilder().setLocalPort(port).build()) {
      sender.setScmpErrorHandler(scmpMessage -> !errors.add(scmpMessage.getTypeCode().getText()));
      sender.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      // start responder
      responder.submit(mtn -> scmpResponder(null, mtn));

      // send request
      for (int i = 0; i < 10; i++) {
        Scmp.EchoMessage msg = sender.sendEchoRequest(path, ByteBuffer.allocate(0));
        assertNotNull(msg);
        assertFalse(msg.isTimedOut(), "i=" + i);
        assertEquals(Scmp.TypeCode.TYPE_129, msg.getTypeCode());
      }
    } finally {
      responder.stopNow();
      MockNetwork.stopTiny();
    }
  }

  @Test
  void testEchoWithSHIM() throws IOException {
    // The SHIM includes a responder so we don't need to start an extra one.

    MockNetwork.startTiny();
    MockScmpHandler.stop(); // Shut down SCMP handler
    Path path = getPathTo112(InetAddress.getLoopbackAddress());
    // sender is in 110; responder/SHIM is in 112
    Shim.install();
    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      sender.setScmpErrorHandler(scmpMessage -> !errors.add(scmpMessage.getTypeCode().getText()));
      sender.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      // send request
      for (int i = 0; i < 10; i++) {
        Scmp.EchoMessage msg = sender.sendEchoRequest(path, ByteBuffer.allocate(0));
        assertNotNull(msg);
        assertFalse(msg.isTimedOut(), "i=" + i);
        assertEquals(Scmp.TypeCode.TYPE_129, msg.getTypeCode());
      }
    } finally {
      MockNetwork.stopTiny();
      Shim.uninstall();
    }
  }

  @Test
  void testEchoBlocked() throws IOException {
    // Checks that the SCMP messages are not answered if the echoListener returns false.
    MockNetwork.startTiny();
    MockScmpHandler.stop(); // Shut down SCMP handler
    Path path = getPathTo112(InetAddress.getLoopbackAddress());
    // sender is in 110; responder is in 112
    ManagedThread responder = ManagedThread.newBuilder().build();
    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      sender.setScmpErrorHandler(scmpMessage -> !errors.add(scmpMessage.getTypeCode().getText()));
      sender.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      // start responder
      AtomicInteger dropCount = new AtomicInteger();
      responder.submit(mtn -> scmpResponder(echoMsg -> dropCount.incrementAndGet() < -42, mtn));

      // send request
      sender.setTimeOut(100);
      for (int i = 0; i < 2; i++) {
        Scmp.EchoMessage result = sender.sendEchoRequest(path, ByteBuffer.allocate(0));
        assertTrue(result.isTimedOut());
        assertEquals(100 * 1_000_000, result.getNanoSeconds());
        assertEquals(i, result.getSequenceNumber());
      }
      assertEquals(2, dropCount.get());
    } finally {
      responder.stopNow();
      MockNetwork.stopTiny();
    }
  }

  private void scmpResponder(Predicate<Scmp.EchoMessage> predicate, ManagedThreadNews mtn)
      throws IOException {
    try (ScmpResponder responder = Scmp.newResponderBuilder().build()) {
      responder.setScmpErrorListener(
          scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      responder.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      responder.setScmpEchoListener(predicate);
      mtn.reportStarted();
      responder.start();
    }
  }

  @Test
  void testDoNotBroadcast() throws IOException {
    // Verify that we do not broadcast messages, i.e. we do not respond to a message that
    // has a broadcast address as source address.

    MockNetwork.startTiny();
    MockScmpHandler.stop(); // Shut down SCMP handler

    ScionPacketInspector spi = ScionPacketInspector.createEmpty();
    InetAddress broadCast = IPHelper.toInetAddress("255.255.255.255");
    spi.getScionHeader().setSrcHostAddress(broadCast.getAddress());
    spi.getScionHeader().setDstHostAddress(IPHelper.toByteArray("127.0.0.1"));
    spi.getScmpHeader().setCode(Scmp.TypeCode.TYPE_128);
    ByteBuffer packet = ByteBuffer.allocate(100);
    spi.writePacketSCMP(packet);
    packet.flip();

    ManagedThread responder = ManagedThread.newBuilder().build();
    AtomicBoolean shouldNotBeCalled = new AtomicBoolean();
    try (MockDatagramChannel mock = MockDatagramChannel.open()) {
      // Prepare mockchannel
      mock.setReceiveCallback(
          bb -> {
            bb.put(packet);
            return IPHelper.toInetSocketAddress("192.168.0.42:12345");
          });
      mock.setSendCallback((a, b) -> fail());

      // Start receiver
      responder.submit(
          mtn ->
              scmpResponder(
                  echoMsg -> {
                    shouldNotBeCalled.getAndSet(true);
                    // Abort sender on next select()
                    mock.setDefaultSelectCallback(() -> 0);
                    return true;
                  },
                  mtn,
                  mock));

      // Wait a bit
      responder.join(100);

      assertFalse(shouldNotBeCalled.get());
    } finally {
      responder.stopNow();
    }
  }

  private void scmpResponder(
      Predicate<Scmp.EchoMessage> predicate, ManagedThreadNews mtn, MockDatagramChannel mock)
      throws IOException {
    try (ScmpResponder responder = Scmp.newResponderBuilder().setDatagramChannel(mock).build()) {
      responder.setScmpErrorListener(
          scmpMessage -> errors.add(scmpMessage.getTypeCode().getText()));
      responder.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      responder.setScmpEchoListener(predicate);
      mtn.reportStarted();
      responder.start();
    }
  }
}
