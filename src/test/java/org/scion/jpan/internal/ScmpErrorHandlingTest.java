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
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockDatagramChannel;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.MockNetwork2;

class ScmpErrorHandlingTest {

  @Test
  void testScmpCreator() {
    InetAddress firstHopIP = IPHelper.getByAddress(new int[] {127, 0, 0, 2});
    InetSocketAddress firstHop = new InetSocketAddress(firstHopIP, 12345);
    Path p =
        PackageVisibilityHelper.createDummyPath(
            ScionUtil.parseIA("1-ff00:0:112"),
            new byte[] {127, 0, 0, 1},
            Constants.SCMP_PORT,
            ExamplePacket.PATH_RAW_TINY_110_112,
            firstHop);

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
    assertEquals(ScionUtil.parseIA("1-ff00:0:111"), ((Scmp.Error5Message) msg).getIsdAs());
    assertEquals(41, ((Scmp.Error5Message) msg).getInterfaceId());
  }

  @Test
  void testReadError6() {
    Scmp.Message msg = readError(Scmp.TypeCode.TYPE_6, null);
    assertInstanceOf(Scmp.Error6Message.class, msg);
    assertTrue(msg.toString().contains("Internal Connectivity Down"));
    assertEquals(ScionUtil.parseIA("1-ff00:0:110"), ((Scmp.Error6Message) msg).getIsdAs());
    assertEquals(1, ((Scmp.Error6Message) msg).getIngressId());
    assertEquals(2, ((Scmp.Error6Message) msg).getEgressId());
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
    assertEquals(ScionUtil.parseIA("1-ff00:0:111"), ((Scmp.Error5Message) msg).getIsdAs());
    assertEquals(41, ((Scmp.Error5Message) msg).getInterfaceId());
  }

  @Test
  void testReceiveError6() {
    Scmp.Message msg = receiveError(Scmp.TypeCode.TYPE_6, NoRouteToHostException.class);
    assertInstanceOf(Scmp.Error6Message.class, msg);
    assertTrue(msg.toString().contains("Internal Connectivity Down"));
    assertEquals(ScionUtil.parseIA("1-ff00:0:110"), ((Scmp.Error6Message) msg).getIsdAs());
    assertEquals(1, ((Scmp.Error6Message) msg).getIngressId());
    assertEquals(2, ((Scmp.Error6Message) msg).getEgressId());
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
    AtomicReference<Scmp.ErrorMessage> error = new AtomicReference<>();
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4, "ASff00_0_111")) {
      try (ScionDatagramChannel channel = errorSender(typeCode, getPathTo112())) {
        channel.setScmpErrorListener(error::set);
        task.consume(channel);
      } catch (IOException e) {
        if (e.getClass() != expectedException) {
          fail("Unexpected exception: " + e);
        }
      }
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
  void send_useThrowsExceptionOnError() throws IOException {
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4, "ASff00_0_111")) {
      Path path = getPathTo112();
      try (ScionDatagramChannel channel = errorSender(Scmp.TypeCode.TYPE_5, path)) {
        AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
        channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));

        // Try with send() -> throws error
        channel.send(ByteBuffer.allocate(0), path);
        ByteBuffer receive = ByteBuffer.allocate(1000);
        Throwable t = assertThrows(IOException.class, () -> channel.receive(receive));
        assertTrue(t.getMessage().contains(Scmp.TypeCode.TYPE_5.getText()), t.getMessage());

        assertTrue(listenerWasTriggered.get());
      }
    }
  }

  @Test
  void write_useBackupPathOnError5() throws IOException {
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4, "ASff00_0_111")) {
      Path path = getPathTo112();
      try (ScionDatagramChannel channel = errorSender(Scmp.TypeCode.TYPE_5, path)) {
        AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
        channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));

        // Try again with connected path
        channel.connect(path.getRemoteSocketAddress());
        assertEquals(path, channel.getConnectionPath());
        channel.write(ByteBuffer.allocate(0));
        channel.read(ByteBuffer.allocate(1000));
        // Path should have changed
        assertNotEquals(path, channel.getConnectionPath());

        assertTrue(listenerWasTriggered.get());
      }
    }
  }

  @Test
  void write_useBackupPathOnError6() throws IOException {
    // We need a full network here so we have a full ISD between src and dst
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4, "ASff00_0_111")) {
      Path path = getPathTo112();
      try (ScionDatagramChannel channel = errorSender(Scmp.TypeCode.TYPE_6, path)) {
        AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
        channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));

        // Try again with connected path
        channel.connect(path.getRemoteSocketAddress());
        assertEquals(path, channel.getConnectionPath());
        channel.write(ByteBuffer.allocate(0));
        channel.read(ByteBuffer.allocate(1000));
        // Path should have changed
        assertNotEquals(path, channel.getConnectionPath());

        assertTrue(listenerWasTriggered.get());
      }
    }
  }

  @Test
  void write_noBackupPath() throws IOException {
    // Test what happens if no backup path is available.
    // We have two path, we let both fail
    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4, "ASff00_0_111")) {
      try (ScionDatagramChannel channel = errorSender(Scmp.TypeCode.TYPE_5, getPathTo112())) {
        AtomicBoolean listenerWasTriggered = new AtomicBoolean(false);
        channel.setScmpErrorListener(scmpMessage -> listenerWasTriggered.set(true));
        channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);

        Path path = getPathTo112();

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
      }
    }
  }

  private Path getPathTo112() {
    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    InetSocketAddress dst = IPHelper.toInetSocketAddress("127.0.0.1:" + Constants.SCMP_PORT);
    return Scion.defaultService().getPaths(dstIA, dst).get(0);
  }

  //  private ScionDatagramChannel errorSender(Scmp.TypeCode errorCode) throws IOException {
  //    return errorSender(errorCode, null);
  //  }

  private ScionDatagramChannel errorSender(Scmp.TypeCode errorCode, Path errorPath)
      throws IOException {
    MockDatagramChannel errorChannel = MockDatagramChannel.open();
    ByteBuffer response = ByteBuffer.allocate(1000);
    errorChannel.setSendCallback(
        (request, socketAddress) -> {
          createError(errorCode, request, response, errorPath);
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
          if (errorPath != null) {
            // This is simply to make it work with MockNetwork2
            return errorPath.getFirstHopAddress();
          }
          return new InetSocketAddress(MockNetwork.getBorderRouterAddress1().getAddress(), 30041);
        });
    return ScionDatagramChannel.newBuilder().channel(errorChannel).open();
  }

  private void createError(
      Scmp.TypeCode errorCode, ByteBuffer orig, ByteBuffer response, Path errorPath) {
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
        if (errorPath != null) {
          PathMetadata meta = errorPath.getMetadata();
          PathMetadata.PathInterface pIf = meta.getInterfacesList().get(0);
          spi.getScmpHeader().setDataLong(pIf.getIsdAs(), pIf.getId(), 0);
        } else {
          spi.getScmpHeader().setDataLong(123, 85, 0);
        }
        break;
      case ERROR_6:
        if (errorPath != null) {
          PathMetadata meta = errorPath.getMetadata();
          // Failed ISD/AS = 110
          PathMetadata.PathInterface pIfIn = meta.getInterfacesList().get(1);
          PathMetadata.PathInterface pIfEg = meta.getInterfacesList().get(2);
          assertEquals(pIfIn.getIsdAs(), pIfEg.getIsdAs());
          spi.getScmpHeader().setDataLong(pIfIn.getIsdAs(), pIfIn.getId(), pIfEg.getId());
        } else {
          spi.getScmpHeader().setDataLong(125, 83, 38);
        }
        break;
      default:
        // nothing
    }
    spi.writePacketSCMP(response);
  }
}
