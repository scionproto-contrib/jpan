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

import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.socket.DatagramSocket;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockDaemon;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.PingPongSocketHelper;
import org.scion.jpan.testutil.Util;

class DatagramSocketApiTest {

  private static final Inet4Address ipV4Any;
  private static final Inet6Address ipV6Any;
  private static final int dummyPort = 44444;
  private static final InetAddress dummyIPv4;
  private static final InetSocketAddress dummyAddress;
  private static final DatagramPacket dummyPacket;

  static {
    try {
      ipV4Any = (Inet4Address) InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
      ipV6Any =
          (Inet6Address)
              InetAddress.getByAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
      dummyIPv4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
      dummyAddress = new InetSocketAddress(dummyIPv4, dummyPort);
      dummyPacket = new DatagramPacket(new byte[100], 100, dummyAddress);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeEach
  public void beforeEach() throws IOException {
    MockDaemon.createAndStartDefault();
  }

  @AfterEach
  public void afterEach() throws IOException {
    MockDaemon.closeDefault();
    MockDNS.clear();
  }

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void create_unbound() throws IOException {
    try (DatagramSocket socket = new DatagramSocket(null)) {
      assertFalse(socket.isBound());
    }
  }

  @Test
  void create_bound() throws IOException {
    try (DatagramSocket socket = new DatagramSocket(dummyAddress)) {
      assertTrue(socket.isBound());
      InetSocketAddress local = (InetSocketAddress) socket.getLocalSocketAddress();
      assertEquals(dummyAddress, local);
    }
  }

  @Test
  void getLocalAddress() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
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
    InetSocketAddress address = new InetSocketAddress("localhost", dummyPort);
    try (DatagramSocket socket = new DatagramSocket(address)) {
      assertTrue(socket.isBound());
      assertEquals(address, socket.getLocalSocketAddress());
    }
  }

  @Test
  void getLocalAddress_withExplicitBind() throws IOException {
    InetSocketAddress address = new InetSocketAddress("localhost", dummyPort);
    try (DatagramSocket socket = new DatagramSocket(null)) {
      assertFalse(socket.isBound());
      socket.bind(address);
      assertTrue(socket.isBound());
      assertEquals(address, socket.getLocalSocketAddress());
    }
  }

  @Test
  void getLocalAddress_withoutBind() throws IOException {
    try (DatagramSocket socket = new DatagramSocket(null)) {
      assertFalse(socket.isBound());
      assertNull(socket.getLocalAddress());
    }
  }

  @Test
  void getLocalAddress_notLocalhost() throws IOException {
    ScionService pathService = Scion.defaultService();
    // TXT entry: "scion=64-2:0:9,129.132.230.98"
    ScionAddress sAddr = pathService.getScionAddress("ethz.ch");
    InetSocketAddress firstHop = new InetSocketAddress("1.1.1.1", dummyPort);

    RequestPath path =
        PackageVisibilityHelper.createDummyPath(
            sAddr.getIsdAs(),
            sAddr.getInetAddress().getAddress(),
            dummyPort,
            new byte[100],
            firstHop);

    try (DatagramSocket socket = new DatagramSocket()) {
      // This constructor binds() to the local ANY address.
      assertTrue(socket.getLocalAddress().isAnyLocalAddress());
      socket.connect(path);
      // Assert that this doesn't change
      assertTrue(socket.getLocalAddress().isAnyLocalAddress());
    }
  }

  @Test
  void send_requiresAddressWithScionTxt() {
    InetSocketAddress addr = new InetSocketAddress("1.1.1.1", 30255);
    DatagramPacket packet = new DatagramPacket(new byte[100], 100, addr);
    try (DatagramSocket socket = new DatagramSocket()) {
      Exception ex = assertThrows(IOException.class, () -> socket.send(packet));
      assertTrue(ex.getMessage().contains("No DNS TXT entry \"scion\" found"), ex.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void send_requiresAddressWithScionCorrectTxt() {
    String TXT = "\"XXXscion=1-ff00:0:110,127.0.0.55\"";
    System.setProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, "127.0.0.55=" + TXT);
    InetSocketAddress addr = new InetSocketAddress("127.0.0.55", 30255);
    DatagramPacket packet = new DatagramPacket(new byte[100], 100, addr);
    try (DatagramSocket socket = new DatagramSocket()) {
      Exception ex = assertThrows(IOException.class, () -> socket.send(packet));
      assertTrue(ex.getMessage().contains("Invalid TXT entry"), ex.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
    }
  }

  @Test
  void isOpen() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      assertFalse(socket.isClosed());
      socket.close();
      assertTrue(socket.isClosed());
    }
  }

  @Test
  void receive_timeout() throws IOException, InterruptedException {
    int timeOutMs = 50;
    MockDNS.install("1-ff00:0:112", "localhost", "127.0.0.1");
    InetSocketAddress address = new InetSocketAddress("127.0.0.1", 12345);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicLong timeMs = new AtomicLong();
    AtomicReference<Exception> exception = new AtomicReference<>();
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(timeOutMs);
      socket.connect(address);
      // Running a separate thread prevents this from halting infinitely.
      Thread t =
          new Thread(
              () -> {
                latch.countDown();
                long t1 = System.nanoTime();
                try {
                  socket.receive(dummyPacket);
                } catch (Exception e) {
                  exception.set(e);
                }
                long t2 = System.nanoTime();
                timeMs.set((t2 - t1) / 1_000_000);
              });
      t.start();
      latch.await();
      t.join(3 * timeOutMs);
      t.interrupt();
      assertInstanceOf(SocketTimeoutException.class, exception.get(), exception.get().getMessage());
      // Verify that it waited for at least "timeout"
      assertTrue(timeMs.get() >= timeOutMs, timeMs.get() + " >= " + timeOutMs);
      // Verify that it waited less than te JUnit test timeout
      assertTrue(timeMs.get() < 1.5 * timeOutMs, timeMs.get() + " < 1.5* " + timeOutMs);
    }
  }

  @Test
  void connect_fail() throws SocketException {
    try (DatagramSocket socket = new DatagramSocket()) {
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
  void isConnected_InetSocket() throws IOException {
    //    MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
    //    InetSocketAddress address = new InetSocketAddress("::1", 12345);
    // We have to use IPv4 because IPv6 fails on GitHubs Ubuntu CI images.
    MockDNS.install("1-ff00:0:112", "localhost", "127.0.0.1");
    InetSocketAddress address = new InetSocketAddress("127.0.0.1", 12345);
    InetSocketAddress address2 = new InetSocketAddress("127.0.0.2", 22345);
    try (DatagramSocket socket = new DatagramSocket()) {
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
    RequestPath path = PackageVisibilityHelper.createDummyPath();
    InetAddress ip = path.getRemoteAddress();
    InetSocketAddress address = new InetSocketAddress(ip, path.getRemotePort());
    try (DatagramSocket socket = new DatagramSocket()) {
      assertFalse(socket.isConnected());
      assertNull(socket.getRemoteSocketAddress());
      socket.connect(path);
      assertTrue(socket.isConnected());
      assertEquals(address, socket.getRemoteSocketAddress());

      // try connecting again
      // Should be AlreadyConnectedException, but Temurin throws IllegalStateException
      assertThrows(IllegalStateException.class, () -> socket.connect(path));
      assertTrue(socket.isConnected());

      // disconnect
      socket.disconnect();
      assertFalse(socket.isConnected());
      assertNull(socket.getRemoteSocketAddress());
      socket.disconnect();
      assertFalse(socket.isConnected());

      // Connect again
      socket.connect(path);
      assertTrue(socket.isConnected());
      socket.close();
      assertFalse(socket.isConnected());
    }
  }

  @Test
  void bind() throws IOException {
    //    try (java.net.DatagramSocket socket = new java.net.DatagramSocket(null)) {
    //      assertNull(socket.getLocalSocketAddress());
    //      socket.bind(null);
    //      InetSocketAddress address2 = (InetSocketAddress) socket.getLocalSocketAddress();
    //      assertTrue(address2.getPort() > 0);
    //    }
    try (DatagramSocket socket = new DatagramSocket(null)) {
      assertNull(socket.getLocalSocketAddress());
      socket.bind(null);
      InetSocketAddress address2 = (InetSocketAddress) socket.getLocalSocketAddress();
      assertTrue(address2.getPort() > 0);
    }
  }

  @Test
  void bind_fails() throws IOException {
    //    try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
    //      Exception ex = assertThrows(SocketException.class, () -> socket.bind(null));
    //      assertTrue(ex.getMessage().contains("already bound"));
    //    }
    try (DatagramSocket socket = new DatagramSocket()) {
      Exception ex = assertThrows(SocketException.class, () -> socket.bind(null));
      assertTrue(ex.getMessage().contains("already bound"));
    }
  }

  @Test
  void getService_default() throws IOException {
    ScionService service1 = Scion.defaultService();
    ScionService service2 = Scion.newServiceWithDaemon(MockDaemon.DEFAULT_ADDRESS_STR);
    try (DatagramSocket socket = new DatagramSocket()) {
      // The initial socket should NOT have a service.
      // A server side socket may never need a service so we shouldn't create it.
      assertNull(socket.getService());

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
    ScionService service2 = Scion.newServiceWithDaemon(MockDaemon.DEFAULT_ADDRESS_STR);
    try (DatagramSocket socket = DatagramSocket.create(service2)) {
      assertEquals(service2, socket.getService());
      assertNotEquals(service1, socket.getService());
    }
    service2.close();
  }

  @Test
  void getPathPolicy() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      assertEquals(PathPolicy.DEFAULT, socket.getPathPolicy());
      assertEquals(PathPolicy.MIN_HOPS, socket.getPathPolicy());
      socket.setPathPolicy(PathPolicy.MAX_BANDWIDTH);
      assertEquals(PathPolicy.MAX_BANDWIDTH, socket.getPathPolicy());
      // TODO test that path policy is actually used
    }
  }

  @Test
  void send_bufferTooLarge() {
    try (DatagramSocket socket = new DatagramSocket()) {
      int size = socket.getSendBufferSize() + 1; // Too large, yay!
      DatagramPacket packet = new DatagramPacket(new byte[size], size, dummyAddress);
      Exception ex = assertThrows(IOException.class, () -> socket.send(packet));
      String msg = ex.getMessage();
      // Linux vs Windows(?)
      assertTrue(msg.contains("too long") || msg.contains("larger than"), ex.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void send_wrongPort() throws IOException {
    // We do not allow changing the port on returned packets.
    // Technically it would be possible to do that, but it requires changing the Path object,
    // introducing problems with concurrent use of Path objects.
    // (in the case of Sockets, Paths are somewhat protected from concurrent usage, but that
    // can be circumvented by using socket.getChannel() or socket.getCachedPath().)
    int size = 10;
    try (DatagramSocket server = new DatagramSocket(MockNetwork.getTinyServerAddress())) {
      SocketAddress serverAddress = server.getLocalSocketAddress();

      try (DatagramSocket client = new DatagramSocket()) {
        DatagramPacket packet = new DatagramPacket(new byte[size], size, serverAddress);
        client.send(packet);
      }

      DatagramPacket packet = new DatagramPacket(new byte[size], size, serverAddress);
      SocketAddress clientAddress = packet.getSocketAddress();
      server.receive(packet);

      // Modify packet - port
      packet.setPort(packet.getPort() + 1);
      server.send(packet); // Any exception because ...

      assertFalse(server.isConnected());
      server.connect(clientAddress);
      // Once connected, the packet address has to match the connected address.
      Throwable ex = assertThrows(IllegalArgumentException.class, () -> server.send(packet));
      assertTrue(ex.getMessage().contains("Packet address does not match connected address"));
    }
  }

  @Test
  void send_wrongAddress() throws IOException {
    int size = 10;
    try (DatagramSocket server = new DatagramSocket(MockNetwork.getTinyServerAddress())) {
      SocketAddress serverAddress = server.getLocalSocketAddress();

      try (DatagramSocket client = new DatagramSocket()) {
        DatagramPacket packet = new DatagramPacket(new byte[size], size, serverAddress);
        client.send(packet);
      }

      DatagramPacket packet = new DatagramPacket(new byte[size], size, serverAddress);
      server.receive(packet);
      SocketAddress clientAddress = packet.getSocketAddress();

      // Address is null
      packet.setSocketAddress(clientAddress);
      packet.setAddress(null);
      assertThrows(IllegalArgumentException.class, () -> server.send(packet));

      // control: works
      packet.setSocketAddress(clientAddress);
      server.send(packet);
    }
  }

  @Test
  void receive_IllegalBlockingMode() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
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
    InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 1);
    DatagramPacket packet = new DatagramPacket(new byte[100], 100, addr);
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.close();
      Throwable t = assertThrows(SocketException.class, () -> socket.receive(packet));
      assertTrue(t.getMessage().contains("closed"));
    }
  }

  @Test
  void send_NullAddress_Exception() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.disconnect();
      // null address
      DatagramPacket packet1 = new DatagramPacket(new byte[100], 100);
      assertThrows(IllegalArgumentException.class, () -> socket.send(packet1));
    }
  }

  @Test
  void send_AddressMismatch_Exception() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.disconnect();
      socket.connect(dummyAddress);

      // port mismatch
      InetSocketAddress addr1 = new InetSocketAddress("127.0.0.1", 2);
      DatagramPacket packet1 = new DatagramPacket(new byte[100], 100, addr1);
      assertThrows(IllegalArgumentException.class, () -> socket.send(packet1));

      // IP mismatch
      InetSocketAddress addr2 = new InetSocketAddress("127.0.0.2", 1);
      DatagramPacket packet2 = new DatagramPacket(new byte[100], 100, addr2);
      assertThrows(IllegalArgumentException.class, () -> socket.send(packet2));
    }
  }

  @Test
  void send_AddressResolve_hostName() throws IOException {
    // TODO
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.disconnect();
      socket.connect(dummyAddress);

      // port mismatch
      InetSocketAddress addr1 = new InetSocketAddress("127.0.0.1", 2);
      DatagramPacket packet1 = new DatagramPacket(new byte[100], 100, addr1);
      assertThrows(IllegalArgumentException.class, () -> socket.send(packet1));

      // IP mismatch
      InetSocketAddress addr2 = new InetSocketAddress("127.0.0.2", 1);
      DatagramPacket packet2 = new DatagramPacket(new byte[100], 100, addr2);
      assertThrows(IllegalArgumentException.class, () -> socket.send(packet2));
    }
  }

  @Disabled
  @Test
  void send_AddressResolve_reverseLookupIPv4() throws IOException {
    // TODO
  }

  @Disabled
  @Test
  void send_AddressResolve_reverseLookupIPv6() throws IOException {
    // TODO
  }

  @Disabled
  @Test
  void send_AddressResolve_no_resolve_necessary() throws IOException {
    // TODO
  }

  @Disabled
  @Test
  void send_AddressResolve_resolve_failure() throws IOException {
    // TODO
  }

  @Test
  void send_IllegalBlockingMode() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
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
  void send_NonResolvableAddressFails() throws IOException {
    InetSocketAddress addr = new InetSocketAddress("127.127.0.1", 1);
    DatagramPacket packet = new DatagramPacket(new byte[100], 100, addr);
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.disconnect();
      assertThrows(IOException.class, () -> socket.send(packet));
    }
  }

  @Test
  void send_ChannelClosedFails() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.close();
      Throwable t = assertThrows(SocketException.class, () -> socket.send(dummyPacket));
      assertTrue(t.getMessage().contains("closed"));
    }
  }

  @Test
  void send_connected_expiredRequestPath() throws IOException {
    // Expected behavior: expired paths should be replaced transparently.
    testExpired(
        (socket, expiredPath) -> {
          String msg = PingPongSocketHelper.MSG;
          DatagramPacket packet =
              new DatagramPacket(msg.getBytes(), msg.length(), toAddress(expiredPath));
          try {
            socket.send(packet);
            RequestPath newPath = socket.getConnectionPath();
            assertTrue(newPath.getExpiration() > expiredPath.getExpiration());
            assertTrue(Instant.now().getEpochSecond() < newPath.getExpiration());
            // assertNull(channel.getCurrentPath());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private void testExpired(BiConsumer<DatagramSocket, RequestPath> sendMethod) throws IOException {
    MockDaemon.closeDefault(); // We don't need the daemon here
    PingPongSocketHelper.Server serverFn = PingPongSocketHelper::defaultServer;
    PingPongSocketHelper.Client clientFn =
        (channel, basePath, id) -> {
          // Build a path that is already expired
          RequestPath expiredPath = createExpiredPath(basePath);
          sendMethod.accept(channel, expiredPath);

          // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
          DatagramPacket packet = new DatagramPacket(new byte[100], 100);
          channel.receive(packet);

          ByteBuffer response = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
          String pong = Charset.defaultCharset().decode(response).toString();
          assertEquals(PingPongSocketHelper.MSG, pong);
        };
    PingPongSocketHelper pph = new PingPongSocketHelper(1, 10, 5);
    pph.runPingPong(serverFn, clientFn);
  }

  private RequestPath createExpiredPath(Path basePath) throws UnknownHostException {
    long now = Instant.now().getEpochSecond();
    Timestamp timestamp = Timestamp.newBuilder().setSeconds(now - 10).build();
    Daemon.Path.Builder builder = Daemon.Path.newBuilder().setExpiration(timestamp);
    RequestPath expiredPath =
        PackageVisibilityHelper.createRequestPath110_112(
            builder,
            basePath.getRemoteIsdAs(),
            basePath.getRemoteAddress(),
            basePath.getRemotePort(),
            basePath.getFirstHopAddress());
    assertTrue(Instant.now().getEpochSecond() > expiredPath.getExpiration());
    return expiredPath;
  }

  private static InetSocketAddress toAddress(Path path) {
    return new InetSocketAddress(path.getRemoteAddress(), path.getRemotePort());
  }

  @Test
  void getConnectionPath() throws IOException {
    RequestPath path = ExamplePacket.PATH;
    DatagramPacket packet = new DatagramPacket(new byte[50], 50, toAddress(path));
    try (DatagramSocket channel = new DatagramSocket()) {
      assertNull(channel.getConnectionPath());
      // send should NOT set a path
      channel.send(packet);
      assertNull(channel.getConnectionPath());

      // connect should set a path
      channel.connect(path);
      assertNotNull(channel.getConnectionPath());
      channel.disconnect();
      assertNull(channel.getConnectionPath());

      // send should NOT set a path
      if (Util.getJavaMajorVersion() >= 14) {
        // This fails because of disconnect(), see https://bugs.openjdk.org/browse/JDK-8231880
        channel.send(packet);
        assertNull(channel.getConnectionPath());
      }
    }
  }

  @Test
  void setOption_SCION() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      assertFalse(socket.getOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE));
      DatagramSocket ds = socket.setOption(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE, true);
      assertEquals(socket, ds);

      int margin = socket.getOption(ScionSocketOptions.SN_PATH_EXPIRY_MARGIN);
      socket.setOption(ScionSocketOptions.SN_PATH_EXPIRY_MARGIN, margin + 1000);
      assertEquals(margin + 1000, socket.getOption(ScionSocketOptions.SN_PATH_EXPIRY_MARGIN));

      socket.close();
      assertThrows(
          ClosedChannelException.class,
          () -> socket.getOption(ScionSocketOptions.SN_PATH_EXPIRY_MARGIN));
      assertThrows(
          ClosedChannelException.class,
          () -> socket.setOption(ScionSocketOptions.SN_PATH_EXPIRY_MARGIN, 11));
    }
  }

  @Test
  void supportedOptions() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      Set<SocketOption<?>> options = socket.supportedOptions();
      assertTrue(options.contains(ScionSocketOptions.SN_PATH_EXPIRY_MARGIN));
      assertTrue(options.contains(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE));

      assertTrue(options.contains(StandardSocketOptions.SO_RCVBUF));
      assertTrue(options.contains(StandardSocketOptions.SO_SNDBUF));
      assertTrue(options.contains(StandardSocketOptions.SO_REUSEADDR));
      assertTrue(options.contains(StandardSocketOptions.IP_TOS));

      assertEquals(6, options.size());
    }
  }

  @Test
  void setOption_Standard() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      DatagramSocket ds = socket.setOption(StandardSocketOptions.SO_RCVBUF, 10000);
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
  void testPathCache() throws IOException {
    int size = 10;
    try (DatagramSocket server = new DatagramSocket(MockNetwork.getTinyServerAddress())) {
      assertFalse(server.isConnected()); // connected sockets do not have a cache
      SocketAddress serverAddress = server.getLocalSocketAddress();
      InetSocketAddress clientAddress1;
      InetSocketAddress clientAddress2;

      // 1st client
      try (DatagramSocket client =
          new DatagramSocket(11111, InetAddress.getByAddress(new byte[] {127, 0, 0, 11}))) {
        client.connect(serverAddress);
        assertEquals(server.getLocalSocketAddress(), toAddress(client.getConnectionPath()));
        clientAddress1 = (InetSocketAddress) client.getLocalSocketAddress();
        DatagramPacket packet1 = new DatagramPacket(new byte[size], size, serverAddress);
        client.send(packet1);
      }

      DatagramPacket packet1 = new DatagramPacket(new byte[size], size, serverAddress);
      server.receive(packet1);
      assertEquals(clientAddress1, packet1.getSocketAddress());

      Path path1 = server.getCachedPath((InetSocketAddress) packet1.getSocketAddress());
      assertEquals(clientAddress1, toAddress(path1));

      // 2nd client
      try (DatagramSocket client =
          new DatagramSocket(22222, InetAddress.getByAddress(new byte[] {127, 0, 0, 22}))) {
        client.connect(serverAddress);
        assertEquals(server.getLocalSocketAddress(), toAddress(client.getConnectionPath()));
        clientAddress2 = (InetSocketAddress) client.getLocalSocketAddress();
        DatagramPacket packet2 = new DatagramPacket(new byte[size], size, serverAddress);
        client.send(packet2);
      }

      assertNotEquals(clientAddress1, clientAddress2);
      assertTrue(server.getPathCacheCapacity() > 1);

      DatagramPacket packet2 = new DatagramPacket(new byte[size], size, serverAddress);
      server.receive(packet2);
      assertEquals(clientAddress2, packet2.getSocketAddress());

      // path 1 should still be there
      path1 = server.getCachedPath((InetSocketAddress) packet1.getSocketAddress());
      assertEquals(clientAddress1, toAddress(path1));
      // path 2 should also be there
      Path path2 = server.getCachedPath((InetSocketAddress) packet2.getSocketAddress());
      assertEquals(clientAddress2, toAddress(path2));

      // reduce capacity
      server.setPathCacheCapacity(1);
      assertEquals(1, server.getPathCacheCapacity());

      // path 1 should be gone now.
      assertNull(server.getCachedPath((InetSocketAddress) packet1.getSocketAddress()));
      // path 2 should be there
      path2 = server.getCachedPath((InetSocketAddress) packet2.getSocketAddress());
      assertEquals(clientAddress2, toAddress(path2));
    }
  }

  @Test
  void testBug_doubleSendCausesNPE() throws IOException {
    try (DatagramSocket server = new DatagramSocket(dummyPort)) {
      assertFalse(server.isConnected());
      try (DatagramSocket client = new DatagramSocket()) {
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
}
