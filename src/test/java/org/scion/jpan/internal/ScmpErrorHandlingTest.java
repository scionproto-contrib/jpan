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
import java.nio.channels.DatagramChannel;
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
  void testReceiveError1() {
    assertInstanceOf(Scmp.Error1Message.class, testReceiveError(Scmp.TypeCode.TYPE_1_CODE_0));
  }

  @Test
  void testReceiveError2() {
    assertInstanceOf(Scmp.Error2Message.class, testReceiveError(Scmp.TypeCode.TYPE_2));
  }

  @Test
  void testReceiveError4() {
    assertInstanceOf(Scmp.Error4Message.class, testReceiveError(Scmp.TypeCode.TYPE_4_CODE_0));
  }

  @Test
  void testReceiveError5() {
    assertInstanceOf(Scmp.Error5Message.class, testReceiveError(Scmp.TypeCode.TYPE_5));
  }

  @Test
  void testReceiveError6() {
    assertInstanceOf(Scmp.Error6Message.class, testReceiveError(Scmp.TypeCode.TYPE_6));
  }

  private Scmp.ErrorMessage testReceiveError(Scmp.TypeCode typeCode) {
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

  /**
   * SCMP Error 1 codes:
   * 0 - No route to destination
   * 1 - Communication administratively denied
   * 2 - Beyond scope of source address
   * 3 - Address unreachable
   * 4 - Port unreachable
   * 5 - Source address failed ingress/egress policy
   * 6 - Reject route to destination
   *
   * TODO it would be realy nice if the channel/socket kept the last packet and resend it.
   *      However, this is not useful, how many packets would it need to keep?
   *      Which ones would need to be resent?
   *      Better report an error and indicate that retrying may just work!
   *      If we just drop the error (and only update the path), then the application may think the
   *      UDP packet was just lost and may resent it on it's own! -> Good solution.
   *      --> Is there an exception that indicates that retrying is recommended? No, but
   *          if retry is recommended, we just don't throw and instead simulate UDP packet loss.
   *      --> Create mapping:
   *      - Not path found -> (NoRouteToHost) -> No retry
   *      - 3: (NoRouteToHost) -> no retry
   *      - 4: PortUnreachable -> no retry
   *
   *
   * Error 1: All no retry, throw NoRouteToHost or PortUnreachable
   * Error 2: PacketTooBig -> throw ProtocolException?
   * Error 4: ParameterProblem -> throw ProtocolException?
   * Error 5: External Interface Down: Do not throw, just report path as faulty and line up next path
   * Error 6: Internal Connectivity Down: Do not throw, just report path as faulty and line up next path
   *
   * Errors 5 and 6 could throw NoRouteToHost if they run out of paths....
   */
  @Test
  void testError1_NoRouteToHostException() {
    // Error codes 0..3 and 5..6
    // TODO assertThrows(PortUnreachableException.class, );
    fail();
  }

  @Test
  void testError1_PortUnreachable() throws IOException, InterruptedException {
    // Error code 4
    // TODO assertThrows(PortUnreachableException.class, );


    DatagramChannel ds = DatagramChannel.open();
    ByteBuffer bb = ByteBuffer.allocate(1000);
    InetAddress ip = IPHelper.getByAddress(new int[]{192, 168,0, 0});
    InetSocketAddress isa = new InetSocketAddress(ip, 12345);
    ds.send(bb, isa);
    Thread.sleep(1000);
    ds.send(bb, isa);
    ds.receive(bb);

    ds.connect(isa);
    ds.write(bb);
    Thread.sleep(1000);
    ds.write(bb);
    ds.read(bb);

    fail();
  }

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
