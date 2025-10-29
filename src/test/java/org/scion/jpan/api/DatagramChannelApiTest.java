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
import java.nio.channels.NotYetConnectedException;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.internal.ExternalIpDiscovery;
import org.scion.jpan.internal.Util;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockDaemon;
import org.scion.jpan.testutil.MockDatagramChannel;
import org.scion.jpan.testutil.MockNetwork;
import org.scion.jpan.testutil.PingPongChannelHelper;

class DatagramChannelApiTest {

  private static final int DUMMY_PORT = 44444;
  private static final InetAddress dummyIPv4;
  private static final InetSocketAddress dummyAddress;

  static {
    try {
      dummyIPv4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
      dummyAddress = new InetSocketAddress(dummyIPv4, DUMMY_PORT);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeEach
  void beforeEach() {
    MockNetwork.startTiny();
  }

  @AfterEach
  void afterEach() {
    MockNetwork.stopTiny();
    MockDNS.clear();
  }

  @AfterAll
  static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void getLocalAddress_withBind() throws IOException {
    InetSocketAddress addr = new InetSocketAddress("localhost", DUMMY_PORT);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open().bind(addr)) {
      assertEquals(addr, channel.getLocalAddress());
    }
  }

  @Test
  void getLocalAddress_withBindNull() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open().bind(null)) {
      InetSocketAddress local = channel.getLocalAddress();
      assertTrue(local.getAddress().isAnyLocalAddress());
    }
  }

  @Test
  void getLocalAddress_withoutBind() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertNull(channel.getLocalAddress());
    }
  }

  @Test
  void getLocalAddress_withConnect() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(dummyAddress);
      InetSocketAddress local = channel.getLocalAddress();
      assertNotNull(local);
      assertFalse(local.getAddress().isAnyLocalAddress());
    }
  }

  @Test
  void getLocalAddress_withSendAddress() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.send(ByteBuffer.allocate(100), dummyAddress);
      InetSocketAddress local = channel.getLocalAddress();
      assertTrue(local.getAddress().isAnyLocalAddress());
    }
  }

  @Test
  void getLocalAddress_withSendRequestPath() throws IOException {
    Path path = PackageVisibilityHelper.createMockRequestPath(null);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.send(ByteBuffer.allocate(100), path);
      InetSocketAddress local = channel.getLocalAddress();
      assertTrue(local.getAddress().isAnyLocalAddress());
    }
  }

  @Test
  void getLocalAddress_withSendResponsePath() throws IOException {
    ByteBuffer rawPacket = ByteBuffer.wrap(ExamplePacket.PACKET_BYTES_SERVER_E2E_PONG);
    ResponsePath response = PackageVisibilityHelper.getResponsePath(rawPacket, dummyAddress);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.send(ByteBuffer.allocate(100), response);
      InetSocketAddress local = channel.getLocalAddress();
      assertTrue(local.getAddress().isAnyLocalAddress());
      // double check that we used a responsePath
      assertNull(channel.getConnectionPath());
    }
  }

  @Test
  void getLocalAddress_withReceive() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.configureBlocking(false);
      channel.receive(ByteBuffer.allocate(100));
      InetSocketAddress local = channel.getLocalAddress();
      assertTrue(local.getAddress().isAnyLocalAddress());
    }
  }

  @Test
  void send_RequiresInetSocketAddress() throws IOException {
    ByteBuffer bb = ByteBuffer.allocate(100);
    Exception exception;
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      SocketAddress addr =
          new SocketAddress() {
            @Override
            public int hashCode() {
              return super.hashCode();
            }
          };
      exception = assertThrows(IllegalArgumentException.class, () -> channel.send(bb, addr));
    }

    String expectedMessage = "must be of type InetSocketAddress";
    String actualMessage = exception.getMessage();
    assertTrue(actualMessage.contains(expectedMessage));
  }

  @Test
  void send_requiresAddressWithScionTxt() {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    InetSocketAddress addr = new InetSocketAddress("1.1.1.1", 30255);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      Exception ex = assertThrows(IOException.class, () -> channel.send(buffer, addr));
      assertTrue(ex.getMessage().contains("No DNS TXT entry \"scion\" found"), ex.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void send_requiresAddressWithScionCorrectTxt() {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    String dnsTXT = "\"XXXscion=1-ff00:0:110,127.0.0.55\"";
    System.setProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, "127.0.0.55=" + dnsTXT);
    InetSocketAddress addr = new InetSocketAddress("127.0.0.55", 30255);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      Exception ex = assertThrows(IOException.class, () -> channel.send(buffer, addr));
      assertTrue(ex.getMessage().contains("Invalid TXT entry"), ex.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
    }
  }

  @Test
  void isOpen() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertTrue(channel.isOpen());
      channel.close();
      assertFalse(channel.isOpen());
    }
  }

  @Test
  void isBlocking_default() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertTrue(channel.isBlocking());
    }
  }

  @Test
  void isBlocking_true_read() throws IOException {
    testBlocking(true, channel -> channel.read(ByteBuffer.allocate(100)));
  }

  @Test
  void isBlocking_false_read() throws IOException {
    testBlocking(false, channel -> channel.read(ByteBuffer.allocate(100)));
  }

  @Test
  void isBlocking_true_receiver() throws IOException {
    testBlocking(true, channel -> channel.receive(ByteBuffer.allocate(100)));
  }

  @Test
  void isBlocking_false_receive() throws IOException {
    testBlocking(false, channel -> channel.receive(ByteBuffer.allocate(100)));
  }

  interface ChannelConsumer {
    void accept(ScionDatagramChannel channel) throws InterruptedException, IOException;
  }

  private void testBlocking(boolean isBlocking, ChannelConsumer fn) throws IOException {
    MockDNS.install("1-ff00:0:112", "localhost", "127.0.0.1");
    InetSocketAddress address = new InetSocketAddress("127.0.0.1", 12345);
    AtomicBoolean wasBlocking = new AtomicBoolean(true);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(address);
      channel.configureBlocking(isBlocking);
      assertEquals(isBlocking, channel.isBlocking());
      ManagedThread t = ManagedThread.newBuilder().build();
      t.submit(
          mtn -> {
            try {
              mtn.reportStarted();
              fn.accept(channel);
              // Should only be reached with non-blocking channel
              wasBlocking.getAndSet(false);
            } catch (InterruptedException | IOException e) {
              // ignore
            }
          });
      t.join(30);
      assertEquals(isBlocking, wasBlocking.get());
    }
  }

  @Test
  void isConnected_InetSocket() throws IOException {
    //    MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
    //    InetSocketAddress address = new InetSocketAddress("::1", 12345);
    // We have to use IPv4 because IPv6 fails on GitHubs Ubuntu CI images.
    MockDNS.install("1-ff00:0:112", "localhost", "127.0.0.1");
    InetSocketAddress address = new InetSocketAddress("127.0.0.1", 12345);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertFalse(channel.isConnected());
      assertNull(channel.getRemoteAddress());
      channel.connect(address);
      assertTrue(channel.isConnected());
      assertEquals(address, channel.getRemoteAddress());

      // try connecting again
      // Should be AlreadyConnectedException but Temurin throws IllegalStateException
      assertThrows(IllegalStateException.class, () -> channel.connect(address));
      assertTrue(channel.isConnected());

      // disconnect
      channel.disconnect();
      assertFalse(channel.isConnected());
      assertNull(channel.getRemoteAddress());
      channel.disconnect();
      assertFalse(channel.isConnected());

      // Connect again
      channel.connect(address);
      assertTrue(channel.isConnected());
      assertEquals(address, channel.getRemoteAddress());
      channel.close();
      assertFalse(channel.isConnected());
    }
  }

  @Test
  void isConnected_Path() throws IOException {
    Path path = PackageVisibilityHelper.createDummyPath();
    InetAddress ip = path.getRemoteAddress();
    InetSocketAddress address = new InetSocketAddress(ip, path.getRemotePort());
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertFalse(channel.isConnected());
      assertNull(channel.getRemoteAddress());
      channel.connect(path);
      assertTrue(channel.isConnected());
      assertEquals(address, channel.getRemoteAddress());

      // try connecting again
      // Should be AlreadyConnectedException, but Temurin throws IllegalStateException
      assertThrows(IllegalStateException.class, () -> channel.connect(path));
      assertTrue(channel.isConnected());

      // disconnect
      channel.disconnect();
      assertFalse(channel.isConnected());
      assertNull(channel.getRemoteAddress());
      channel.disconnect();
      assertFalse(channel.isConnected());

      // Connect again
      channel.connect(path);
      assertTrue(channel.isConnected());
      channel.close();
      assertFalse(channel.isConnected());
    }
  }

  @Test
  void bind() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertNull(channel.getLocalAddress());
      channel.bind(null);
      InetSocketAddress address = channel.getLocalAddress();
      assertTrue(address.getPort() > 0);
    }
  }

  @Test
  void getService_default() throws IOException {
    ScionService service1 = Scion.defaultService();
    ScionService service2 = Scion.newServiceWithDaemon(MockDaemon.DEFAULT_ADDRESS_STR);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertEquals(service1, channel.getService());

      // trigger service initialization in channel
      Path path = PackageVisibilityHelper.createMockRequestPath(null);
      channel.send(ByteBuffer.allocate(0), path);
      assertNotEquals(service2, channel.getService());
      assertEquals(service1, channel.getService());
    }
    service2.close();
  }

  @Test
  void getService_non_default() throws IOException {
    ScionService service1 = Scion.defaultService();
    ScionService service2 = Scion.newServiceWithDaemon(MockDaemon.DEFAULT_ADDRESS_STR);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(service2)) {
      assertEquals(service2, channel.getService());
      assertNotEquals(service1, channel.getService());
    }
    service2.close();
  }

  @Test
  void getService_non_default_NULL() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open(null)) {
      assertNull(channel.getService());
    }
  }

  @Test
  void getPathPolicy() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertEquals(PathPolicy.DEFAULT, channel.getPathPolicy());
      assertEquals(PathPolicy.MIN_HOPS, channel.getPathPolicy());
      channel.setPathPolicy(PathPolicy.MAX_BANDWIDTH);
      assertEquals(PathPolicy.MAX_BANDWIDTH, channel.getPathPolicy());
      // TODO test that path policy is actually used
    }
  }

  @Test
  void setPathPolicy_filterReturnsEmptyList() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      List<Path> paths = channel.getService().lookupPaths("127.0.0.1", 12345);

      // Create expired path
      channel.connect(paths.get(0));

      PathPolicy empty = paths1 -> Collections.emptyList();
      // We expect an exception because there is no path available.
      Exception e = assertThrows(ScionRuntimeException.class, () -> channel.setPathPolicy(empty));
      assertTrue(e.getMessage().startsWith("No path found to destination"));
    }
  }

  @Test
  void connect_noPathFound() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      List<Path> paths = channel.getService().lookupPaths("127.0.0.1", 12345);

      // Create empty path policy
      PathPolicy empty = paths1 -> Collections.emptyList();
      channel.setPathPolicy(empty);

      // Create expired path to trigger PathProvider
      Path expired = PackageVisibilityHelper.createExpiredPath(paths.get(0), 10);
      Exception e = assertThrows(ScionRuntimeException.class, () -> channel.connect(expired));
      assertTrue(e.getMessage().startsWith("No path found to destination"));
    }
  }

  @Test
  void send_bufferSize() throws IOException {
    Path path = PackageVisibilityHelper.createMockRequestPath(null);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      int size0 = channel.send(ByteBuffer.allocate(0), path);
      assertEquals(0, size0);

      int size100 = channel.send(ByteBuffer.wrap(new byte[100]), path);
      assertEquals(100, size100);
    }
  }

  @Test
  void send_bufferTooLarge() {
    Path path = PackageVisibilityHelper.createMockRequestPath(null);
    ByteBuffer buffer = ByteBuffer.allocate(65440);
    buffer.limit(buffer.capacity());
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      Exception ex = assertThrows(IOException.class, () -> channel.send(buffer, path));
      String msg = ex.getMessage();
      // Linux vs Windows(?)
      assertTrue(msg.contains("too long") || msg.contains("larger than"), ex.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void write_bufferTooLarge() throws IOException {
    Path path = PackageVisibilityHelper.createMockRequestPath(null);
    ByteBuffer buffer = ByteBuffer.allocate(100_000);
    buffer.limit(buffer.capacity());
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.connect(path);
      Exception ex = assertThrows(IOException.class, () -> channel.write(buffer));
      String msg = ex.getMessage();
      // Linux vs Windows(?)
      assertTrue(msg.contains("too long") || msg.contains("larger than"), ex.getMessage());
    }
  }

  @Test
  void read_NotConnectedFails() throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertThrows(NotYetConnectedException.class, () -> channel.read(buffer));
    }
  }

  @Test
  void read_ChannelClosedFails() throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.close();
      assertThrows(ClosedChannelException.class, () -> channel.read(buffer));
    }
  }

  @Test
  void write_NotConnectedFails() throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertThrows(NotYetConnectedException.class, () -> channel.write(buffer));
    }
  }

  @Test
  void write_ChannelClosedFails() throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(100);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      channel.close();
      assertThrows(ClosedChannelException.class, () -> channel.write(buffer));
    }
  }

  @Test
  void send_disconnected_expiredRequestPath() {
    // Expected behavior: expired paths should be replaced transparently.
    testExpired(
        (channel, expiringPath) -> {
          ByteBuffer sendBuf = ByteBuffer.wrap(PingPongChannelHelper.MSG.getBytes());
          try {
            long oldExpiration = expiringPath.getMetadata().getExpiration();
            assertTrue(Instant.now().getEpochSecond() > oldExpiration);
            channel.send(sendBuf, expiringPath);
            // Path is unmodifiable
            assertEquals(oldExpiration, expiringPath.getMetadata().getExpiration());
            long newExpiration = channel.getMappedPath(expiringPath).getMetadata().getExpiration();
            assertTrue(newExpiration > oldExpiration);
            assertTrue(Instant.now().getEpochSecond() < newExpiration);
            assertNull(channel.getConnectionPath());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        },
        false);
  }

  @Test
  void send_connected_expiredRequestPath() {
    // Expected behavior: expired paths should be replaced transparently.
    testExpired(
        (channel, expiringPath) -> {
          ByteBuffer sendBuf = ByteBuffer.wrap(PingPongChannelHelper.MSG.getBytes());
          try {
            long oldExpiration = expiringPath.getMetadata().getExpiration();
            assertTrue(Instant.now().getEpochSecond() > oldExpiration);
            channel.send(sendBuf, expiringPath.getRemoteSocketAddress());
            long newExpiration = channel.getMappedPath(expiringPath).getMetadata().getExpiration();
            assertTrue(newExpiration > oldExpiration);
            assertTrue(Instant.now().getEpochSecond() < newExpiration);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        },
        true);
  }

  @Test
  void write_expiredRequestPath() {
    // Expected behavior: expired paths should be replaced transparently.
    testExpired(
        (channel, expiredPath) -> {
          ByteBuffer sendBuf = ByteBuffer.wrap(PingPongChannelHelper.MSG.getBytes());
          try {
            channel.write(sendBuf);
            Path newPath = channel.getConnectionPath();
            assertTrue(
                newPath.getMetadata().getExpiration() > expiredPath.getMetadata().getExpiration());
            assertTrue(Instant.now().getEpochSecond() < newPath.getMetadata().getExpiration());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        },
        true);
  }

  private void testExpired(BiConsumer<ScionDatagramChannel, Path> sendMethod, boolean connect) {
    MockNetwork.stopTiny(); // We don't need the daemon here
    PingPongChannelHelper.Server serverFn = PingPongChannelHelper::defaultServer;
    PingPongChannelHelper.Client clientFn =
        (channel, basePath, id) -> {
          // Build a path that is already expired
          Path expiredPath = PackageVisibilityHelper.createExpiredPath(basePath, 10);
          assertTrue(Instant.now().getEpochSecond() > expiredPath.getMetadata().getExpiration());
          sendMethod.accept(channel, expiredPath);

          ByteBuffer response = ByteBuffer.allocate(100);
          channel.receive(response);

          response.flip();
          String pong = Charset.defaultCharset().decode(response).toString();
          assertEquals(PingPongChannelHelper.MSG, pong);
        };
    PingPongChannelHelper pph = PingPongChannelHelper.newBuilder(1, 10, 5).connect(connect).build();
    pph.runPingPong(serverFn, clientFn);
  }

  @Test
  void getConnectionPath() throws IOException {
    Path path = PackageVisibilityHelper.createMockRequestPath(null);
    ByteBuffer buffer = ByteBuffer.allocate(50);
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertNull(channel.getConnectionPath());
      // send should NOT set a path
      channel.send(buffer, path);
      assertNull(channel.getConnectionPath());

      // connect should set a path
      channel.connect(path);
      assertNotNull(channel.getConnectionPath());
      channel.disconnect();
      assertNull(channel.getConnectionPath());

      // send should NOT set a path
      if (Util.getJavaMajorVersion() >= 14) {
        // This fails because of disconnect(), see https://bugs.openjdk.org/browse/JDK-8231880
        channel.send(buffer, path);
        assertNull(channel.getConnectionPath());
      }
    }
  }

  @Test
  void testBug_doubleSendCausesNPE() throws IOException {
    try (ScionDatagramChannel server = ScionDatagramChannel.open()) {
      server.bind(dummyAddress);
      try (ScionDatagramChannel client = ScionDatagramChannel.open()) {
        assertFalse(client.isConnected());
        assertNull(client.getConnectionPath());
        assertNull(client.getRemoteAddress());
        ByteBuffer buffer = ByteBuffer.allocate(50);
        client.send(buffer, dummyAddress);
        assertFalse(client.isConnected());
        // The second send() used to fail with NPE
        client.send(buffer, dummyAddress);
        assertFalse(client.isConnected());
      }
    }
  }

  @Test
  void setOption_SCION() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      assertFalse(channel.getOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE));
      ScionDatagramChannel dc =
          channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      assertEquals(channel, dc);

      int margin = channel.getOption(ScionSocketOptions.SCION_PATH_EXPIRY_MARGIN);
      channel.setOption(ScionSocketOptions.SCION_PATH_EXPIRY_MARGIN, margin + 1000);
      assertEquals(margin + 1000, channel.getOption(ScionSocketOptions.SCION_PATH_EXPIRY_MARGIN));

      int tc = channel.getOption(ScionSocketOptions.SCION_TRAFFIC_CLASS);
      channel.setOption(ScionSocketOptions.SCION_TRAFFIC_CLASS, tc + 1);
      assertEquals(tc + 1, channel.getOption(ScionSocketOptions.SCION_TRAFFIC_CLASS));

      channel.close();
      assertThrows(
          ClosedChannelException.class,
          () -> channel.getOption(ScionSocketOptions.SCION_PATH_EXPIRY_MARGIN));
      assertThrows(
          ClosedChannelException.class,
          () -> channel.setOption(ScionSocketOptions.SCION_PATH_EXPIRY_MARGIN, 11));
    }
  }

  @Test
  void setOption_TrafficClass() throws IOException {
    ByteBuffer buf = ByteBuffer.wrap("Hello".getBytes());
    try (MockDatagramChannel mock = MockDatagramChannel.open();
        ScionDatagramChannel channel = ScionDatagramChannel.open(Scion.defaultService(), mock)) {
      // traffic class should be 0
      mock.setSendCallback(
          (buffer, address) -> {
            assertEquals(
                0, ScionPacketInspector.readPacket(buffer).getScionHeader().getTrafficClass());
            return 0;
          });
      channel.send(buf, dummyAddress);

      int trafficClass = channel.getOption(ScionSocketOptions.SCION_TRAFFIC_CLASS);
      assertEquals(0, trafficClass);
      channel.setOption(ScionSocketOptions.SCION_TRAFFIC_CLASS, 42);
      assertEquals(42, channel.getOption(ScionSocketOptions.SCION_TRAFFIC_CLASS));

      // traffic class should be 42
      mock.setSendCallback(
          (buffer, address) -> {
            assertEquals(
                42, ScionPacketInspector.readPacket(buffer).getScionHeader().getTrafficClass());
            return 0;
          });
      channel.send(buf, dummyAddress);
    }
  }

  @Test
  void setOption_Standard() throws IOException {
    try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
      ScionDatagramChannel ds = channel.setOption(StandardSocketOptions.SO_RCVBUF, 10000);
      assertEquals(channel, ds);
      assertEquals(10000, channel.getOption(StandardSocketOptions.SO_RCVBUF));

      channel.setOption(StandardSocketOptions.SO_SNDBUF, 10000);
      assertEquals(10000, channel.getOption(StandardSocketOptions.SO_SNDBUF));

      channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
      assertTrue(channel.getOption(StandardSocketOptions.SO_REUSEADDR));

      // The following fails on Windows.
      // From https://docs.oracle.com/javase/9/docs/api/java/net/StandardSocketOptions.html#IP_TOS
      // "The behavior of this socket option on [...], or an IPv6 socket, is not defined in this
      // release."
      // channel.setOption(StandardSocketOptions.IP_TOS, 5);
      // assertEquals(5, channel.getOption(StandardSocketOptions.IP_TOS));

      assertThrows(
          UnsupportedOperationException.class,
          () -> channel.getOption(StandardSocketOptions.SO_BROADCAST));
      channel.close();
      assertThrows(
          ClosedChannelException.class, () -> channel.getOption(StandardSocketOptions.SO_RCVBUF));
      assertThrows(
          ClosedChannelException.class,
          () -> channel.setOption(StandardSocketOptions.SO_RCVBUF, 10000));
    }
  }

  @Test
  void setOverrideSourceAddress() throws IOException {
    setOverrideSourceAddress(false, false);
  }

  @Test
  void setOverrideSourceAddress_withBind() throws IOException {
    setOverrideSourceAddress(true, false);
  }

  @Test
  void setOverrideSourceAddress_withConnect() throws IOException {
    setOverrideSourceAddress(false, true);
  }

  void setOverrideSourceAddress(boolean bind, boolean connect) throws IOException {
    assertFalse(bind && connect);
    ByteBuffer buf = ByteBuffer.wrap("Hello".getBytes());
    InetAddress overrideSrcIP = InetAddress.getByAddress(new byte[] {42, 42, 42, 42});
    int overrideSrcPort = 4242;
    InetSocketAddress overrideSrc = new InetSocketAddress(overrideSrcIP, overrideSrcPort);

    try (MockDatagramChannel mock = MockDatagramChannel.open();
        ScionDatagramChannel channel = ScionDatagramChannel.open(Scion.defaultService(), mock)) {

      // initialize local address
      InetSocketAddress localAddress;
      if (bind) {
        localAddress = new InetSocketAddress(InetAddress.getLocalHost(), 12345);
        channel.bind(localAddress);
      } else if (connect) {
        channel.connect(dummyAddress);
        localAddress = channel.getLocalAddress();
      } else {
        mock.setSendCallback((buffer, address) -> 0);
        channel.send(buf, dummyAddress);
        InetSocketAddress firstHop = MockNetwork.getBorderRouterAddress1();
        InetAddress localIP = ExternalIpDiscovery.getExternalIP(firstHop);
        localAddress = new InetSocketAddress(localIP, channel.getLocalAddress().getPort());
      }

      // src should be 127.0.0.1
      mock.setSendCallback((buffer, address) -> checkAddress(buffer, localAddress));
      channel.send(buf, dummyAddress);

      // src should be overrideAddress
      channel.setOverrideSourceAddress(overrideSrc);
      assertEquals(overrideSrc, channel.getOverrideSourceAddress());
      mock.setSendCallback((buffer, address) -> checkAddress(buffer, overrideSrc));
      channel.send(buf, dummyAddress);

      // src should be local address again
      channel.setOverrideSourceAddress(null);
      assertNull(channel.getOverrideSourceAddress());
      mock.setSendCallback((buffer, address) -> checkAddress(buffer, localAddress));
      channel.send(buf, dummyAddress);
    }
  }

  private int checkAddress(ByteBuffer buffer, InetSocketAddress expectedAddress) {
    ScionPacketInspector spi = ScionPacketInspector.readPacket(buffer);
    try {
      assertEquals(expectedAddress.getAddress(), spi.getScionHeader().getSrcHostAddress());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    assertEquals(expectedAddress.getPort(), spi.getOverlayHeaderUdp().getSrcPort());
    return 0;
  }
}
