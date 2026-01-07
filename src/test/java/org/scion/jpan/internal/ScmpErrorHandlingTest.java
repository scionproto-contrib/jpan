// Copyright 2025 ETH Zurich
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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.testutil.MockDatagramChannel;
import org.scion.jpan.testutil.MockNetwork;

class ScmpErrorHandlingTest {

  @Test
  void testScmpCreator() {
    Path p = getPathTo112();
    assertThrows(
        IllegalArgumentException.class,
        () -> Scmp.EchoMessage.create(Scmp.TypeCode.TYPE_1_CODE_0, 1, 1, p));
    assertThrows(
        IllegalArgumentException.class,
        () -> Scmp.TracerouteMessage.create(Scmp.TypeCode.TYPE_1_CODE_0, 1, 1, p));
  }

  @Test
  void testError1() {
    assertInstanceOf(Scmp.Error1Message.class, testError(Scmp.TypeCode.TYPE_1_CODE_0));
  }

  @Test
  void testError2() {
    assertInstanceOf(Scmp.Error2Message.class, testError(Scmp.TypeCode.TYPE_2));
  }

  @Test
  void testError4() {
    assertInstanceOf(Scmp.Error4Message.class, testError(Scmp.TypeCode.TYPE_4_CODE_0));
  }

  @Test
  void testError5() {
    assertInstanceOf(Scmp.Error5Message.class, testError(Scmp.TypeCode.TYPE_5));
  }

  @Test
  void testError6() {
    assertInstanceOf(Scmp.Error6Message.class, testError(Scmp.TypeCode.TYPE_6));
  }

  private Scmp.ErrorMessage testError(Scmp.TypeCode typeCode) {
    MockNetwork.startTiny();
    try (ScionDatagramChannel channel = errorSender(typeCode)) {
      AtomicReference<Scmp.ErrorMessage> error = new AtomicReference<>();
      channel.setScmpErrorListener(error::set);
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      channel.send(ByteBuffer.allocate(0), getPathTo112());
      ByteBuffer receive = ByteBuffer.allocate(1000);
      channel.receive(receive);

      assertNotNull(error.get());
      assertEquals(typeCode, error.get().getTypeCode());
      return error.get();
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Disabled
  @Test
  void testUseBackupPathOnError() throws IOException {
    MockNetwork.startTiny();
    try (ScionDatagramChannel channel = errorSender(Scmp.TypeCode.TYPE_5)) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      channel.send(ByteBuffer.allocate(0), getPathTo112());
      ByteBuffer receive = ByteBuffer.allocate(1000);
      channel.receive(receive);
      Throwable t =
          assertThrows(
              IOException.class, () -> channel.send(ByteBuffer.allocate(0), getPathTo112()));
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_1_CODE_0.getText()), t.getMessage());
      assertTrue(listenerWasTriggered.get());
    } finally {
      MockNetwork.stopTiny();
    }
    fail();
  }

  @Disabled
  @Test
  void testNoBackupPath() {
    // Test what happens if no backup path is available.
    // -> Retry failed path?
    fail();
  }

  @Disabled
  @Test
  void testLinkErrorAvoidsAllAffectedPaths() {
    // Test that a link error removes all path that use that link
    fail();
  }

  @Disabled
  @Test
  void testExperimentalPacketHandling() {
    // Test that the channel/socket/responder/shim work fine with experimental packets, e.g. 200
    fail();
  }

  private Path getPathTo112() {
    InetAddress brIP = IPHelper.getByAddress(new int[] {127, 0, 0, 2});
    InetSocketAddress br = new InetSocketAddress(brIP, 12345);
    return PackageVisibilityHelper.createMockRequestPath(br);
  }

  private ScionDatagramChannel errorSender(Scmp.TypeCode errorCode) throws IOException {
    MockDatagramChannel errorChannel = MockDatagramChannel.open();
    ByteBuffer response = ByteBuffer.allocate(1000);
    errorChannel.setSendCallback(
        (request, socketAddress) -> {
          createError(errorCode, request, response);
          return 0;
        });
    errorChannel.setReceiveCallback(
        buffer -> {
          response.flip();
          if (response.remaining() == 0) {
            return null;
          }
          buffer.put(response);
          response.clear();
          return new InetSocketAddress(MockNetwork.getBorderRouterAddress1().getAddress(), 30041);
        });
    return ScionDatagramChannel.newBuilder().channel(errorChannel).open();
  }

  private void createError(Scmp.TypeCode errorCode, ByteBuffer orig, ByteBuffer response) {
    response.clear();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(orig);
    spi.reversePath();
    spi.getScmpHeader().setCode(errorCode);
    spi.writePacketSCMP(response);
  }
}
