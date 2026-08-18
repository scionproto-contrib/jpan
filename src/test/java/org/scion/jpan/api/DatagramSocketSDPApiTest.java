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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.ScionDatagramSocket;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.selectors.PathSelector;
import org.scion.jpan.selectors.PathSelectorFactory;
import org.scion.jpan.selectors.PathSelectorFixed;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.MockDaemon;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.PingPongSocketHelper;

/**
 * Test usage of ScionDatagramChannel with ScionDatagramPacket and with pure ScionSocketAddress
 * access.
 */
class DatagramSocketSDPApiTest {

  private static final Inet4Address ipV4Any;
  private static final Inet6Address ipV6Any;
  private static final int DUMMY_PORT = 44444;
  private static final InetAddress dummyIPv4;
  private static final ScionSocketAddress dummyAddress;
  private static final Path dummyPath;
  private static final ScionDatagramPacket dummyPacket;

  static {
    try {
      ipV4Any = (Inet4Address) InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
      ipV6Any =
          (Inet6Address)
              InetAddress.getByAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
      dummyIPv4 = IPHelper.toInetAddress("dummyHostV4", "127.0.0.1");
      InetSocketAddress inetAddress = new InetSocketAddress(dummyIPv4, DUMMY_PORT);
      dummyPath = PackageVisibilityHelper.createDummyPath(inetAddress);
      dummyAddress = dummyPath.getRemoteSocketAddress();
      dummyPacket = new ScionDatagramPacket(new byte[100], 100, dummyPath);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeEach
  void beforeEach() {
    MockDaemon.createAndStartDefault();
  }

  @AfterEach
  void afterEach() {
    MockDaemon.closeDefault();
  }

  @AfterAll
  static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void create_unbound() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket(null)) {
      assertFalse(socket.isBound());
    }
  }

  @Test
  void create_bound() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket(dummyAddress)) {
      assertTrue(socket.isBound());
      InetSocketAddress local = (InetSocketAddress) socket.getLocalSocketAddress();
      assertEquals(dummyAddress, local);
    }
  }

  @Test
  void getLocalAddress() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      // 0.0.0.0 or 0:0:0:0:0:0:0:0
      assertTrue(socket.getLocalAddress().isAnyLocalAddress());
      assertTrue(socket.getLocalPort() > 0);
      InetSocketAddress local = (InetSocketAddress) socket.getLocalSocketAddress();
      if (local.getAddress() instanceof Inet4Address) {
        assertEquals(ipV4Any, local.getAddress());
      } else {
        assertEquals(ipV6Any, local.getAddress());
      }
      assertEquals(socket.getLocalPort(), local.getPort());
    }
  }

  @Test
  void getLocalAddress_withImplicitBind() throws IOException {
    InetSocketAddress address = new InetSocketAddress("localhost", DUMMY_PORT);
    try (ScionDatagramSocket socket = new ScionDatagramSocket(address)) {
      assertTrue(socket.isBound());
      assertEquals(address, socket.getLocalSocketAddress());
    }
  }

  @Test
  void getLocalAddress_withExplicitBind() throws IOException {
    InetSocketAddress address = new InetSocketAddress("localhost", DUMMY_PORT);
    try (ScionDatagramSocket socket = new ScionDatagramSocket(null)) {
      assertFalse(socket.isBound());
      socket.bind(address);
      assertTrue(socket.isBound());
      assertEquals(address, socket.getLocalSocketAddress());
    }
  }

  @Test
  void getLocalAddress_withoutBind() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket(null)) {
      assertFalse(socket.isBound());
      assertNull(socket.getLocalAddress());
    }
  }

  @Test
  void isOpen() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      assertFalse(socket.isClosed());
      socket.close();
      assertTrue(socket.isClosed());
    }
  }

  @Test
  void receive_timeout() throws IOException {
    int timeOutMs = 50;
    InetSocketAddress inetAddress = new InetSocketAddress("127.0.0.1", 12345);
    ScionSocketAddress address = PackageVisibilityHelper.toSSA("1-ff00:0:112", inetAddress);
    AtomicLong timeMs = new AtomicLong();
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      socket.setSoTimeout(timeOutMs);
      socket.connect(address);
      // Running a separate thread prevents this from halting infinitely.
      ManagedThread t =
          ManagedThread.newBuilder().expectThrows(SocketTimeoutException.class).build();
      t.submit(
          mtn -> {
            mtn.reportStarted();
            long t1 = System.nanoTime();
            try {
              socket.receive(dummyPacket);
            } catch (Exception e) {
              mtn.reportException(e);
            }
            long t2 = System.nanoTime();
            timeMs.set((t2 - t1) / 1_000_000);
          });
      t.join(3 * timeOutMs);
      assertInstanceOf(
          SocketTimeoutException.class, t.getException(), t.getException().getMessage());
      // Verify that it waited for at least "timeout".
      // We use 0.9 because Windows otherwise may somehow report sometimes 48ms for 50ms timeout.
      assertTrue(timeMs.get() >= timeOutMs * 0.9, timeMs.get() + " >= " + timeOutMs);
      // Verify that it waited less than te JUnit test timeout
      assertTrue(timeMs.get() < 1.5 * timeOutMs, timeMs.get() + " < 1.5* " + timeOutMs);
    }
  }

  @Test
  void connect_fail() throws SocketException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      // Bad port
      assertThrows(IllegalArgumentException.class, () -> socket.connect(dummyIPv4, -1));

      // Null IP
      assertThrows(IllegalArgumentException.class, () -> socket.connect(null, 12345));

      // Null socket address
      assertThrows(IllegalArgumentException.class, () -> socket.connect((SocketAddress) null));

      // Wrong SocketAddress type
      SocketAddress badAddress =
          new SocketAddress() {
            @Override
            public int hashCode() {
              return super.hashCode();
            }
          };
      assertThrows(IllegalArgumentException.class, () -> socket.connect(badAddress));
    }
  }

  @Test
  void isConnected_InetSocketV4() throws IOException {
    InetSocketAddress address =
        new InetSocketAddress(IPHelper.toInetAddress("test-v4", "127.0.0.1"), 12345);
    InetSocketAddress address2 =
        new InetSocketAddress(IPHelper.toInetAddress("test-v4-2", "127.0.0.2"), 22345);
    ScionSocketAddress ssAddress = PackageVisibilityHelper.toSSA("1-ff00:0:112", address);
    ScionSocketAddress ssAddress2 = PackageVisibilityHelper.toSSA("1-ff00:0:112", address2);
    isConnected_InetSocket(ssAddress, ssAddress2);
  }

  @Test
  void isConnected_InetSocketV6() throws IOException {
    InetSocketAddress address =
        new InetSocketAddress(IPHelper.toInetAddress("test-v6", "::1"), 12345);
    InetSocketAddress address2 =
        new InetSocketAddress(IPHelper.toInetAddress("test-v6-2", "::2"), 22345);
    ScionSocketAddress ssAddress = PackageVisibilityHelper.toSSA("1-ff00:0:112", address);
    ScionSocketAddress ssAddress2 = PackageVisibilityHelper.toSSA("1-ff00:0:112", address2);
    isConnected_InetSocket(ssAddress, ssAddress2);
  }

  private void isConnected_InetSocket(ScionSocketAddress address, ScionSocketAddress address2)
      throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      assertFalse(socket.isConnected());
      assertNull(socket.getRemoteSocketAddress());
      socket.connect(address);
      assertTrue(socket.isConnected());
      assertEquals(address, socket.getRemoteSocketAddress());

      // try connecting again - unlike channels, this does not throw any Exception but simply
      // reconnects to the new address.
      socket.connect(address2);
      assertEquals(address2, socket.getRemoteSocketAddress());
      assertTrue(socket.isConnected());

      // disconnect
      socket.disconnect();
      assertFalse(socket.isConnected());
      assertNull(socket.getRemoteSocketAddress());
      socket.disconnect();
      assertFalse(socket.isConnected());

      // Connect again
      socket.connect(address);
      assertTrue(socket.isConnected());
      assertEquals(address, socket.getRemoteSocketAddress());
      socket.close();
      assertFalse(socket.isConnected());
    }
  }

  @Test
  void isConnected_Path() throws IOException {
    Path path = PackageVisibilityHelper.createDummyPath();
    ScionSocketAddress address = path.getRemoteSocketAddress();
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      assertFalse(socket.isConnected());
      assertNull(socket.getRemoteSocketAddress());
      socket.connect(path.getRemoteSocketAddress());
      assertTrue(socket.isConnected());
      assertEquals(address, socket.getRemoteSocketAddress());

      // try connecting again - unlike channels, this does not throw any Exception but simply
      // reconnects to the new address.
      Path path2 = path.copy(address.getAddress(), address.getPort() + 5);
      socket.connect(path2.getRemoteSocketAddress());
      assertTrue(socket.isConnected());

      // disconnect
      socket.disconnect();
      assertFalse(socket.isConnected());
      assertNull(socket.getRemoteSocketAddress());
      socket.disconnect();
      assertFalse(socket.isConnected());

      // Connect again
      socket.connect(path.getRemoteSocketAddress());
      assertTrue(socket.isConnected());
      socket.close();
      assertFalse(socket.isConnected());
    }
  }

  @Test
  void bind() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket(null)) {
      assertNull(socket.getLocalSocketAddress());
      socket.bind(null);
      InetSocketAddress address2 = (InetSocketAddress) socket.getLocalSocketAddress();
      assertTrue(address2.getPort() > 0);
    }
  }

  @Test
  void bind_fails() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      Exception ex = assertThrows(SocketException.class, () -> socket.bind(null));
      assertTrue(ex.getMessage().contains("already bound"));
    }
  }

  @Test
  void getService_default() throws IOException {
    ScionService service1 = Scion.defaultService();
    ScionService service2 = Scion.newServiceWithDaemon(MockDaemon.getAddressStr());
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      assertEquals(service1, socket.getService());

      // trigger service initialization in channel
      socket.send(dummyPacket);
      assertNotEquals(service2, socket.getService());
      assertEquals(service1, socket.getService());
    }
    service2.close();
  }

  @Test
  void getService_non_default() throws IOException {
    ScionService service1 = Scion.defaultService();
    ScionService service2 = Scion.newServiceWithDaemon(MockDaemon.getAddressStr());
    try (ScionDatagramSocket socket = ScionDatagramSocket.newBuilder().service(service2).open()) {
      assertEquals(service2, socket.getService());
      assertNotEquals(service1, socket.getService());
    }
    service2.close();
  }

  @Test
  void getService_non_default_null() throws IOException {
    try (ScionDatagramSocket socket = ScionDatagramSocket.newBuilder().service(null).open()) {
      assertNull(socket.getService());
    }
  }

  @Test
  void getPathPolicy() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      assertNotNull(socket.getPathSelector());

      socket.connect(dummyAddress);
      assertNotNull(socket.getPathSelector());

      socket.disconnect();
      assertNotNull(socket.getPathSelector());

      socket.connect(dummyAddress);
      assertNotNull(socket.getPathSelector());
      socket.close();
      assertNotNull(socket.getPathSelector());
    }
  }

  @Test
  void send_bufferTooLarge() {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      int size = socket.getSendBufferSize() + 1; // Too large, yay!
      ScionDatagramPacket packet = new ScionDatagramPacket(new byte[size], size, dummyPath);
      Exception ex = assertThrows(IOException.class, () -> socket.send(packet));
      String msg = ex.getMessage();
      // Linux vs Windows(?)
      assertTrue(msg.contains("too long") || msg.contains("larger than"), ex.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void send_wrongPort_connected() throws IOException {
    int size = 10;
    InetSocketAddress serverAddress = MockNetwork.getTinyServerAddress();
    Path serverPath =
        Scion.defaultService().getPaths(ScionUtil.parseIA("1-ff00:0:110"), serverAddress).get(0);
    try (ScionDatagramSocket server = new ScionDatagramSocket(serverAddress)) {
      try (ScionDatagramSocket client = new ScionDatagramSocket()) {
        ScionDatagramPacket packet = new ScionDatagramPacket(new byte[size], size, serverPath);
        client.connect(serverPath.getRemoteSocketAddress());
        // Modify packet - port
        Path path2 = serverPath.copy(serverAddress.getAddress(), serverAddress.getPort() + 1);
        packet.setPath(path2);
        // Once connected, the packet address has to match the connected address.
        Throwable ex = assertThrows(IllegalArgumentException.class, () -> client.send(packet));
        assertTrue(ex.getMessage().contains("Packet address does not match connected address"));
      }
    }
  }

  @Test
  void receive_IllegalBlockingMode() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      // This test is cheating a bit. As it is currently implemented, the
      // IllegalBlockingModeException is thrown by configureBlocking(), not by send().
      assertThrows(
          IllegalBlockingModeException.class,
          () -> {
            socket.getScionChannel().configureBlocking(true);
            socket.receive(dummyPacket);
          });
    }
  }

  @Test
  void receive_ChannelClosedFails() throws IOException {
    ScionDatagramPacket packet = new ScionDatagramPacket(new byte[100], 100, dummyPath);
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      socket.close();
      Throwable t = assertThrows(SocketException.class, () -> socket.receive(packet));
      assertTrue(t.getMessage().contains("closed"));
    }
  }

  @Test
  void send_NullAddress_Exception() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      socket.disconnect();
      // null address
      ScionDatagramPacket packet1 = new ScionDatagramPacket(new byte[100], 100);
      assertThrows(IllegalArgumentException.class, () -> socket.send(packet1));
    }
  }

  @Test
  void send_AddressMismatch_Exception() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      socket.disconnect();
      socket.connect(dummyAddress);

      // port mismatch
      InetSocketAddress addr1 = new InetSocketAddress("127.0.0.1", 2);
      Path path1 = dummyPath.copy(addr1.getAddress(), addr1.getPort());
      ScionDatagramPacket packet1 = new ScionDatagramPacket(new byte[100], 100, path1);
      assertThrows(IllegalArgumentException.class, () -> socket.send(packet1));

      // IP mismatch
      InetSocketAddress addr2 = new InetSocketAddress("127.0.0.2", 1);
      Path path2 = dummyPath.copy(addr2.getAddress(), addr2.getPort());
      ScionDatagramPacket packet2 = new ScionDatagramPacket(new byte[100], 100, path2);
      assertThrows(IllegalArgumentException.class, () -> socket.send(packet2));
    }
  }

  @Test
  void send_IllegalBlockingMode() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      assertThrows(
          IllegalBlockingModeException.class,
          () -> {
            // This test is cheating a bit. As it is currently implemented, the
            // IllegalBlockingModeException is thrown by configureBlocking(),
            // if is not possible to have a channel that would throw it during send().
            socket.getScionChannel().configureBlocking(true);
            socket.send(dummyPacket);
          });
    }
  }

  @Test
  void send_ChannelClosedFails() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      socket.close();
      Throwable t = assertThrows(SocketException.class, () -> socket.send(dummyPacket));
      assertTrue(t.getMessage().contains("closed"));
    }
  }

  @Test
  void send_connected_expiredRequestPath() {
    // Expected behavior: expired paths should be replaced transparently.
    testExpired(
        (socket, expiredPath) -> {
          String msg = PingPongSocketHelper.MSG;

          ScionDatagramPacket packet =
              new ScionDatagramPacket(msg.getBytes(), msg.length(), expiredPath);
          try {
            socket.send(packet);
            Path newPath = socket.getConnectionPath();
            assertTrue(
                newPath.getMetadata().getExpiration() > expiredPath.getMetadata().getExpiration());
            assertTrue(Instant.now().getEpochSecond() < newPath.getMetadata().getExpiration());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private void testExpired(BiConsumer<ScionDatagramSocket, Path> sendMethod) {
    MockDaemon.closeDefault(); // We don't need the daemon here
    PingPongSocketHelper.Server serverFn = PingPongSocketHelper::defaultServer;
    PingPongSocketHelper.Client clientFn =
        (channel, basePath, id) -> {
          // Build a path that is already expired
          Path expiredPath = createExpiredPath(basePath);
          sendMethod.accept(channel, expiredPath);

          ScionDatagramPacket packet = new ScionDatagramPacket(new byte[100], 100);
          channel.receive(packet);

          ByteBuffer response = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
          String pong = Charset.defaultCharset().decode(response).toString();
          assertEquals(PingPongSocketHelper.MSG, pong);
        };
    PingPongSocketHelper pph = PingPongSocketHelper.newBuilder(1, 10, 5).build();
    pph.runPingPong(serverFn, clientFn);
  }

  private Path createExpiredPath(Path basePath) {
    long now = Instant.now().getEpochSecond();
    PathMetadata.Builder builder = PathMetadata.newBuilder().setExpiration(now - 10);
    Path expiredPath =
        PackageVisibilityHelper.createRequestPath110_112(
            builder,
            basePath.getRemoteAddress(),
            basePath.getRemotePort(),
            basePath.getFirstHopAddress());
    assertTrue(Instant.now().getEpochSecond() > expiredPath.getMetadata().getExpiration());
    return expiredPath;
  }

  @Test
  void getConnectionPath() throws IOException {
    // Build fails on MacOS on internal channel.connect("::1") so we use "127.0.0.1"
    Path path = ExamplePacket.PATH_IPV4;
    ScionDatagramPacket packet = new ScionDatagramPacket(new byte[50], 50, path);
    try (ScionDatagramSocket channel = new ScionDatagramSocket()) {
      assertNull(channel.getConnectionPath());
      // send should NOT set a path
      channel.send(packet);
      assertNull(channel.getConnectionPath());

      // connect should set a path
      channel.connect(path.getRemoteSocketAddress());
      assertNotNull(channel.getConnectionPath());
      channel.disconnect();
      assertNull(channel.getConnectionPath());
    }
  }

  @Test
  void setOption_SCION() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      assertFalse(socket.getOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE));
      ScionDatagramSocket ds =
          socket.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      assertEquals(socket, ds);

      socket.close();
      assertThrows(
          ClosedChannelException.class,
          () -> socket.getOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE));
      assertThrows(
          ClosedChannelException.class,
          () -> socket.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true));
    }
  }

  @Test
  void supportedOptions() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      Set<SocketOption<?>> options = socket.supportedOptions();
      assertTrue(options.contains(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE));

      assertTrue(options.contains(StandardSocketOptions.SO_RCVBUF));
      assertTrue(options.contains(StandardSocketOptions.SO_SNDBUF));
      assertTrue(options.contains(StandardSocketOptions.SO_REUSEADDR));
      assertTrue(options.contains(StandardSocketOptions.IP_TOS));

      assertEquals(6, options.size());
    }
  }

  @Test
  void setOption_Standard() throws IOException {
    try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
      ScionDatagramSocket ds = socket.setOption(StandardSocketOptions.SO_RCVBUF, 10000);
      assertEquals(socket, ds);
      assertEquals(10000, socket.getOption(StandardSocketOptions.SO_RCVBUF));

      socket.setOption(StandardSocketOptions.SO_SNDBUF, 10000);
      assertEquals(10000, socket.getOption(StandardSocketOptions.SO_SNDBUF));

      assertThrows(
          UnsupportedOperationException.class,
          () -> socket.getOption(StandardSocketOptions.SO_BROADCAST));
      socket.close();
      assertThrows(
          ClosedChannelException.class, () -> socket.getOption(StandardSocketOptions.SO_RCVBUF));
      assertThrows(
          ClosedChannelException.class,
          () -> socket.setOption(StandardSocketOptions.SO_RCVBUF, 10000));
    }
  }

  @Test
  void testBug_doubleSendCausesNPE() throws IOException {
    try (ScionDatagramSocket server = new ScionDatagramSocket(DUMMY_PORT)) {
      assertFalse(server.isConnected());
      try (ScionDatagramSocket client = new ScionDatagramSocket()) {
        assertFalse(client.isConnected());
        assertNull(client.getConnectionPath());
        assertNull(client.getRemoteSocketAddress());
        client.send(dummyPacket);
        assertFalse(client.isConnected());
        // The second send() used to fail with NPE
        client.send(dummyPacket);
        assertFalse(client.isConnected());
      }
    }
  }

  @Test
  void newBuilder_pathProvider() throws IOException {
    PathPolicy policy = new PathPolicy.MaxBandwith();
    PathSelectorFactory ppNoOp = PathSelectorFixed.Factory.create(policy);
    PathSelector ps = ppNoOp.createPathSelector(Scion.defaultService());
    try (ScionDatagramSocket server =
        ScionDatagramSocket.newBuilder()
            .bind(DUMMY_PORT)
            .pathSelectorForConnect(ps)
            .pathSelectorsForSend(ppNoOp)
            .open()) {
      assertFalse(server.isConnected());
      assertSame(ppNoOp, server.getPathSelectorFactory());
      assertSame(ps, server.getPathSelector());
      server.connect(dummyAddress);
      assertSame(policy, server.getPathSelector().getPathPolicy());
    }
  }
}
