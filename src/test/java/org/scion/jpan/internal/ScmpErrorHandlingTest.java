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
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.testutil.ExamplePacket;
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
  void testReadError1() {
    Scmp.Message msg = readError(Scmp.TypeCode.TYPE_1_CODE_0, NoRouteToHostException.class);
    assertInstanceOf(Scmp.Error1Message.class, msg);
    assertTrue(msg.toString().contains("reportedBy="));
    assertTrue(msg.toString().contains("path="));
  }

  @Test
  void testReadError1_4() {
    Scmp.Message msg = readError(Scmp.TypeCode.TYPE_1_CODE_4, PortUnreachableException.class);
    assertInstanceOf(Scmp.Error1Message.class, msg);
    assertTrue(msg.toString().contains("reportedBy="));
    assertTrue(msg.toString().contains("path="));
  }

  @Test
  void testReadError2() {
    Scmp.Message msg = readError(Scmp.TypeCode.TYPE_2, ProtocolException.class);
    assertInstanceOf(Scmp.Error2Message.class, msg);
    assertTrue(msg.toString().contains("MTU="));
    assertEquals(1200, ((Scmp.Error2Message) msg).getMtu());
  }

  @Test
  void testReadError4() {
    Scmp.Message msg = readError(Scmp.TypeCode.TYPE_4_CODE_0, ProtocolException.class);
    assertInstanceOf(Scmp.Error4Message.class, msg);
    assertTrue(msg.toString().contains("pointer="));
    assertEquals(42, ((Scmp.Error4Message) msg).getPointer());
  }

  @Test
  void testReadError5() {
    Scmp.Message msg = readError(Scmp.TypeCode.TYPE_5, null);
    assertInstanceOf(Scmp.Error5Message.class, msg);
    assertTrue(msg.toString().contains("External Interface Down"));
    assertEquals(123, ((Scmp.Error5Message) msg).getIsdAs());
    assertEquals(85, ((Scmp.Error5Message) msg).getInterfaceId());
  }

  @Test
  void testReadError6() {
    Scmp.Message msg = readError(Scmp.TypeCode.TYPE_6, null);
    assertInstanceOf(Scmp.Error6Message.class, msg);
    assertTrue(msg.toString().contains("Internal Connectivity Down"));
    assertEquals(125, ((Scmp.Error6Message) msg).getIsdAs());
    assertEquals(83, ((Scmp.Error6Message) msg).getIngressId());
    assertEquals(38, ((Scmp.Error6Message) msg).getEgressId());
  }

  @Test
  void testReadErrorExperimental() {
    Scmp.Message msg = readError(Scmp.TypeCode.TYPE_101, null);
    assertInstanceOf(Scmp.ErrorMessage.class, msg);
    assertEquals(Scmp.TypeCode.TYPE_101, msg.getTypeCode());
  }

  @Test
  void testReadInfoExperimental() {
    assertNull(readError(Scmp.TypeCode.TYPE_201, null));
  }

  @Test
  void testReceiveError1() {
    Scmp.Message msg = receiveError(Scmp.TypeCode.TYPE_1_CODE_0, NoRouteToHostException.class);
    assertInstanceOf(Scmp.Error1Message.class, msg);
    assertTrue(msg.toString().contains("reportedBy="));
    assertTrue(msg.toString().contains("path="));
  }

  @Test
  void testReceiveError1_4() {
    Scmp.Message msg = receiveError(Scmp.TypeCode.TYPE_1_CODE_4, PortUnreachableException.class);
    assertInstanceOf(Scmp.Error1Message.class, msg);
    assertTrue(msg.toString().contains("reportedBy="));
    assertTrue(msg.toString().contains("path="));
  }

  @Test
  void testReceiveError2() {
    Scmp.Message msg = receiveError(Scmp.TypeCode.TYPE_2, ProtocolException.class);
    assertInstanceOf(Scmp.Error2Message.class, msg);
    assertTrue(msg.toString().contains("MTU="));
    assertEquals(1200, ((Scmp.Error2Message) msg).getMtu());
  }

  @Test
  void testReceiveError4() {
    Scmp.Message msg = receiveError(Scmp.TypeCode.TYPE_4_CODE_0, ProtocolException.class);
    assertInstanceOf(Scmp.Error4Message.class, msg);
    assertTrue(msg.toString().contains("pointer="));
    assertEquals(42, ((Scmp.Error4Message) msg).getPointer());
  }

  @Test
  void testReceiveError5() {
    Scmp.Message msg = receiveError(Scmp.TypeCode.TYPE_5, NoRouteToHostException.class);
    assertInstanceOf(Scmp.Error5Message.class, msg);
    assertTrue(msg.toString().contains("External Interface Down"));
    assertEquals(123, ((Scmp.Error5Message) msg).getIsdAs());
    assertEquals(85, ((Scmp.Error5Message) msg).getInterfaceId());
  }

  @Test
  void testReceiveError6() {
    Scmp.Message msg = receiveError(Scmp.TypeCode.TYPE_6, NoRouteToHostException.class);
    assertInstanceOf(Scmp.Error6Message.class, msg);
    assertTrue(msg.toString().contains("Internal Connectivity Down"));
    assertEquals(125, ((Scmp.Error6Message) msg).getIsdAs());
    assertEquals(83, ((Scmp.Error6Message) msg).getIngressId());
    assertEquals(38, ((Scmp.Error6Message) msg).getEgressId());
  }

  @Test
  void testReceiveErrorExperimental() {
    Scmp.Message msg = receiveError(Scmp.TypeCode.TYPE_101, null);
    assertInstanceOf(Scmp.ErrorMessage.class, msg);
    assertEquals(Scmp.TypeCode.TYPE_101, msg.getTypeCode());
  }

  @Test
  void testReceiveInfoExperimental() {
    assertNull(receiveError(Scmp.TypeCode.TYPE_201, null));
  }

  private Scmp.ErrorMessage readError(Scmp.TypeCode typeCode, Class<?> expectedException) {
    // test read() / write()
    return testError(
        typeCode,
        expectedException,
        channel -> {
          channel.connect(getPathTo112());
          channel.write(ByteBuffer.allocate(0));
          ByteBuffer receive = ByteBuffer.allocate(1000);
          channel.read(receive);
        });
  }

  private Scmp.ErrorMessage receiveError(Scmp.TypeCode typeCode, Class<?> expectedException) {
    // Test send() / receive()
    return testError(
        typeCode,
        expectedException,
        channel -> {
          channel.send(ByteBuffer.allocate(0), getPathTo112());
          channel.receive(ByteBuffer.allocate(1000));
        });
  }

  private interface ErrorTask {
    void consume(ScionDatagramChannel channel) throws IOException;
  }

  private Scmp.ErrorMessage testError(
      Scmp.TypeCode typeCode, Class<?> expectedException, ErrorTask task) {
    // test read() /write()
    MockNetwork.startTiny();
    AtomicReference<Scmp.ErrorMessage> error = new AtomicReference<>();
    try (ScionDatagramChannel channel = errorSender(typeCode)) {
      channel.setScmpErrorListener(error::set);
      task.consume(channel);
    } catch (IOException e) {
      if (e.getClass() != expectedException) {
        fail("Unexpected exception: " + e);
      }
    } finally {
      MockNetwork.stopTiny();
    }

    if (typeCode.isError()) {
      assertNotNull(error.get());
      assertEquals(typeCode, error.get().getTypeCode());
    } else {
      assertNull(error.get());
    }
    return error.get();
  }

  @Test
  void testUseBackupPathOnError() throws IOException {
    MockNetwork.startTiny();
    try (ScionDatagramChannel channel = errorSender(Scmp.TypeCode.TYPE_5)) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      long dstIA = ScionUtil.parseIA("1-ff00:0:112");
      InetSocketAddress dst = IPHelper.toInetSocketAddress("127.0.0.1:" + Constants.SCMP_PORT);
      Path path = Scion.defaultService().getPaths(dstIA, dst).get(0);

      // Try with send()
      channel.send(ByteBuffer.allocate(0), path);
      ByteBuffer receive = ByteBuffer.allocate(1000);
      Throwable t = assertThrows(IOException.class, () -> channel.receive(receive));
      assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_5.getText()), t.getMessage());

      // Try again with connected path
      channel.connect(path.getRemoteSocketAddress());
      assertEquals(path, channel.getConnectionPath());
      channel.write(ByteBuffer.allocate(0));
      channel.read(ByteBuffer.allocate(1000));
      // Path should have changed
      assertNotEquals(path, channel.getConnectionPath());

      assertTrue(listenerWasTriggered.get());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Test
  void testNoBackupPath() throws IOException {
    // Test what happens if no backup path is available.
    // We have two path, we let both fail
    MockNetwork.startTiny();
    try (ScionDatagramChannel channel = errorSender(Scmp.TypeCode.TYPE_5)) {
      AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
      channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

      long dstIA = ScionUtil.parseIA("1-ff00:0:112");
      InetSocketAddress dst = IPHelper.toInetSocketAddress("127.0.0.1:" + Constants.SCMP_PORT);
      Path path = Scion.defaultService().getPaths(dstIA, dst).get(0);

      // First try
      channel.connect(path.getRemoteSocketAddress());
      assertEquals(path, channel.getConnectionPath());
      channel.write(ByteBuffer.allocate(0));
      channel.read(ByteBuffer.allocate(1000));
      // Path should have changed
      assertNotEquals(path, channel.getConnectionPath());

      // Try again with connected path
      channel.write(ByteBuffer.allocate(0));
      channel.read(ByteBuffer.allocate(1000));
      // Path should have changed back to first path
      // Current behavior: if no path are available: try reusing faulty paths.
      assertEquals(path, channel.getConnectionPath());

      assertTrue(listenerWasTriggered.get());
    } finally {
      MockNetwork.stopTiny();
    }
  }

  @Disabled
  @Test
  void testLinkErrorAvoidsAllAffectedPaths() {
    // Test that a link error removes all path that use that link

    // TODO decide!
    //  - Use simplistic reportError() with some code duplication for error handling
    //  - Use ErrorHandler (move Default to ScmpErrorHandler.createDefaul())
    //    -> More overhead, much more extensible; probably overengineered.

    // When testing, do a little sleep to ensure different timestamps on Entry?
    fail();
  }

  private Path getPathTo112() {
    InetAddress firstHopIP = IPHelper.getByAddress(new int[] {127, 0, 0, 2});
    InetSocketAddress firstHop = new InetSocketAddress(firstHopIP, 12345);

    return PackageVisibilityHelper.createDummyPath(
        ScionUtil.parseIA("1-ff00:0:112"),
        new byte[] {127, 0, 0, 1},
        Constants.SCMP_PORT,
        ExamplePacket.PATH_RAW_TINY_110_112,
        firstHop);
  }

  private ScionDatagramChannel errorSender(Scmp.TypeCode errorCode) throws IOException {
    MockDatagramChannel errorChannel = MockDatagramChannel.open();
    ByteBuffer response = ByteBuffer.allocate(1000);
    errorChannel.setSendCallback(
        (request, socketAddress) -> {
          createError(errorCode, request, response);
          return request.limit(); // ignores offset for now
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
    switch (errorCode.type()) {
      case ERROR_2:
        // MTU
        spi.getScmpHeader().setDataShort(0, 1200);
        break;
      case ERROR_4:
        // pointer
        spi.getScmpHeader().setDataShort(0, 42);
        break;
      case ERROR_5:
        spi.getScmpHeader().setDataLong(123, 85, 0);
        break;
      case ERROR_6:
        spi.getScmpHeader().setDataLong(125, 83, 38);
        break;
      default:
        // nothing
    }
    spi.writePacketSCMP(response);
  }
}
