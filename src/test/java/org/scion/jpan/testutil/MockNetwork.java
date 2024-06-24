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

package org.scion.jpan.testutil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.HopField;
import org.scion.jpan.demo.inspector.PathHeaderScion;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.demo.inspector.ScmpHeader;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.internal.ScmpParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mock network is a simplified version of the test network available in scionproto. The mock is
 * primarily used to run the "tiny" network. Some simplifications:<br>
 *
 * <p>- The mock has only two "border routers". They act as border routers for _all_ ASes. There are
 * two border routers to allow having multiple links between ASes.<br>
 * - The mock border routers forward traffic directly to the target AS, even if there is no direct
 * link in the topology.<br>
 * - The IP on both sides of the BR (link), at least by default, the same.<br>
 * - the border routers do only marginal verification on packets.<br>
 */
public class MockNetwork {

  public static final String BORDER_ROUTER_IPV4 = "127.0.0.1";
  public static final String BORDER_ROUTER_IPv6 = "::1";
  public static final String TINY_SRV_ADDR_1 = "127.0.0.112";
  public static final byte[] TINY_SRV_ADDR_BYTES_1 = {127, 0, 0, 112};
  public static final int TINY_SRV_PORT_1 = 22233;
  public static final String TINY_SRV_ISD_AS = "1-ff00:0:112";
  public static final String TINY_SRV_NAME_1 = "server.as112.test";
  static final AtomicInteger nForwardTotal = new AtomicInteger();
  static final AtomicIntegerArray nForwards = new AtomicIntegerArray(20);
  static final AtomicInteger dropNextPackets = new AtomicInteger();
  static final AtomicReference<Scmp.TypeCode> scmpErrorOnNextPacket = new AtomicReference<>();
  static final AtomicInteger answerNextScmpEchos = new AtomicInteger();
  static CountDownLatch barrier = null;
  public static final int BORDER_ROUTER_PORT1 = 30555;
  private static final int BORDER_ROUTER_PORT2 = 30556;
  private static final Logger logger = LoggerFactory.getLogger(MockNetwork.class.getName());
  private static ExecutorService routers = null;
  private static MockDaemon daemon = null;
  private static MockTopologyServer topoServer;
  private static MockControlServer controlServer;

  /**
   * Start a network with one daemon and a border router. The border router connects "1-ff00:0:110"
   * (considered local) with "1-ff00:0:112" (remote). This also installs a DNS TXT record for
   * resolving the SRV-address to "1-ff00:0:112".
   */
  public static synchronized void startTiny() {
    startTiny(true, true);
  }

  public static synchronized void startTiny(boolean localIPv4, boolean remoteIPv4) {
    startTiny(
        localIPv4 ? BORDER_ROUTER_IPV4 : BORDER_ROUTER_IPv6,
        remoteIPv4 ? BORDER_ROUTER_IPV4 : BORDER_ROUTER_IPv6,
        Mode.DAEMON);
  }

  public static synchronized void startTiny(Mode mode) {
    startTiny(BORDER_ROUTER_IPV4, BORDER_ROUTER_IPV4, mode);
  }

  private static synchronized void startTiny(String localIP, String remoteIP, Mode mode) {
    if (routers != null) {
      throw new IllegalStateException();
    }

    routers = Executors.newFixedThreadPool(2);

    MockScmpHandler.start();

    List<MockBorderRouter> brList = new ArrayList<>();
    brList.add(
        new MockBorderRouter(0, BORDER_ROUTER_PORT1, BORDER_ROUTER_PORT2, localIP, remoteIP));
    brList.add(
        new MockBorderRouter(
            1, BORDER_ROUTER_PORT1 + 10, BORDER_ROUTER_PORT2 + 10, localIP, remoteIP));

    barrier = new CountDownLatch(brList.size());
    for (MockBorderRouter br : brList) {
      routers.execute(br);
    }
    try {
      if (!barrier.await(1, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Failed to start border routers.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Timeout while waiting for border routers", e);
    }

    List<InetSocketAddress> brAddrList =
        brList.stream()
            .map(mBR -> new InetSocketAddress(BORDER_ROUTER_IPV4, mBR.getPort1()))
            .collect(Collectors.toList());
    try {
      daemon = MockDaemon.createForBorderRouter(brAddrList).start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    MockDNS.install(TINY_SRV_ISD_AS, TINY_SRV_NAME_1, TINY_SRV_ADDR_1);

    if (mode == Mode.NAPTR || mode == Mode.BOOTSTRAP) {
      topoServer =
          MockTopologyServer.start(MockTopologyServer.TOPOFILE_TINY_110, mode == Mode.NAPTR);
      controlServer = MockControlServer.start(topoServer.getControlServerPort());
    }

    dropNextPackets.getAndSet(0);
    answerNextScmpEchos.getAndSet(0);
    scmpErrorOnNextPacket.set(null);
  }

  public static synchronized void stopTiny() {
    if (topoServer != null) {
      controlServer.close();
      topoServer.close();
    }

    MockDNS.clear();

    if (daemon != null) {
      try {
        daemon.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      daemon = null;
    }

    if (routers != null) {
      try {
        routers.shutdownNow();
        // Wait a while for tasks to respond to being cancelled
        if (!routers.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.error("Router did not terminate");
        }
        logger.info("Router shut down");
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      routers = null;
    }

    MockScmpHandler.stop();

    dropNextPackets.getAndSet(0);
    answerNextScmpEchos.getAndSet(0);
    scmpErrorOnNextPacket.set(null);
  }

  public static InetSocketAddress getTinyServerAddress() throws IOException {
    return new InetSocketAddress(
        InetAddress.getByAddress(TINY_SRV_NAME_1, TINY_SRV_ADDR_BYTES_1), TINY_SRV_PORT_1);
  }

  public static int getAndResetForwardCount() {
    for (int i = 0; i < nForwards.length(); i++) {
      nForwards.set(i, 0);
    }
    return nForwardTotal.getAndSet(0);
  }

  /**
   * Set the routers to drop the next n packets.
   *
   * @param n packets to drop
   */
  public static void dropNextPackets(int n) {
    dropNextPackets.set(n);
  }

  /**
   * Set the routers to answer the next n SCMP echo requests. In the real world we would just
   * compare the SCMP's IP address to the BR's IP address. However, during unit tests this would
   * always be 127.0.0.1, so it would always match. To avoid the problem we needs this explicit
   * instruction to answer SCMP echo requests.
   *
   * @param n SCMP echo requests to answer
   */
  public static void answerNextScmpEchos(int n) {
    answerNextScmpEchos.set(n);
  }

  public static void returnScmpErrorOnNextPacket(Scmp.TypeCode scmpTypeCode) {
    scmpErrorOnNextPacket.set(scmpTypeCode);
  }

  public static int getForwardCount(int routerId) {
    return nForwards.get(routerId);
  }

  public static MockTopologyServer getTopoServer() {
    return topoServer;
  }

  public static MockControlServer getControlServer() {
    return controlServer;
  }

  public enum Mode {
    /** Start daemon */
    DAEMON,
    /** Install bootstrap server with DNS NAPTR record */
    NAPTR,
    /** Install bootstrap server */
    BOOTSTRAP
  }
}

class MockBorderRouter implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(MockBorderRouter.class.getName());

  private final int id;
  private final String name;
  private final int port1;
  private final int port2;
  private final String ip1;
  private final String ip2;

  MockBorderRouter(int id, int port1, int port2, String ip1, String ip2) {
    this.id = id;
    this.name = "BorderRouter-" + id;
    this.port1 = port1;
    this.port2 = port2;
    this.ip1 = ip1;
    this.ip2 = ip2;
  }

  @Override
  public void run() {
    Thread.currentThread().setName(name);
    InetSocketAddress bind1 = new InetSocketAddress(ip1, port1);
    InetSocketAddress bind2 = new InetSocketAddress(ip2, port2);
    try (DatagramChannel chnLocal = DatagramChannel.open().bind(bind1);
        DatagramChannel chnRemote = DatagramChannel.open().bind(bind2);
        Selector selector = Selector.open()) {
      chnLocal.configureBlocking(false);
      chnRemote.configureBlocking(false);
      chnLocal.register(selector, SelectionKey.OP_READ, chnRemote);
      chnRemote.register(selector, SelectionKey.OP_READ, chnLocal);
      ByteBuffer buffer = ByteBuffer.allocate(66000);
      MockNetwork.barrier.countDown();
      logger.info("{} started on ports {} <-> {}", name, bind1, bind2);

      while (true) {
        if (selector.select() == 0) {
          // This must be an interrupt
          selector.close();
          return;
        }

        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        while (iter.hasNext()) {
          SelectionKey key = iter.next();
          if (key.isReadable()) {
            DatagramChannel incoming = (DatagramChannel) key.channel();
            DatagramChannel outgoing = (DatagramChannel) key.attachment();
            SocketAddress srcAddress = incoming.receive(buffer);
            if (srcAddress == null) {
              throw new IllegalStateException();
            }
            buffer.flip();

            if (MockNetwork.dropNextPackets.get() > 0) {
              MockNetwork.dropNextPackets.decrementAndGet();
              iter.remove();
              continue;
            }

            Scmp.TypeCode errorCode = MockNetwork.scmpErrorOnNextPacket.getAndSet(null);
            if (errorCode != null) {
              sendScmp(errorCode, buffer, srcAddress, incoming);
              iter.remove();
              continue;
            }

            switch (PackageVisibilityHelper.getNextHdr(buffer)) {
              case UDP:
                forwardPacket(buffer, srcAddress, outgoing);
                break;
              case SCMP:
                handleScmp(buffer, srcAddress, incoming, outgoing);
                break;
              default:
                logger.error(
                    "HDR not supported: {}", PackageVisibilityHelper.getNextHdr(buffer).code());
                throw new UnsupportedOperationException();
            }
          }
          iter.remove();
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      logger.info("Shutting down router");
    }
  }

  private void forwardPacket(ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel outgoing)
      throws IOException {
    InetSocketAddress dstAddress = PackageVisibilityHelper.getDstAddress(buffer);
    logger.info(
        "{} forwarding {} bytes from {} to {}", name, buffer.remaining(), srcAddress, dstAddress);

    outgoing.send(buffer, dstAddress);
    buffer.clear();
    MockNetwork.nForwardTotal.incrementAndGet();
    MockNetwork.nForwards.incrementAndGet(id);
  }

  private void handleScmp(
      ByteBuffer buffer,
      SocketAddress srcAddress,
      DatagramChannel incoming,
      DatagramChannel outgoing)
      throws IOException {
    buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
    Scmp.Type type0 = ScmpParser.extractType(buffer);
    // ignore SCMP responses
    if (type0 == Scmp.Type.INFO_129 || type0 == Scmp.Type.INFO_131) {
      // always forward responses
      buffer.rewind();
      forwardPacket(buffer, srcAddress, outgoing);
      return;
    }

    // ignore SCMP requests unless we are instructed to answer them
    if (type0 == Scmp.Type.INFO_128 && MockNetwork.answerNextScmpEchos.get() == 0) {
      buffer.rewind();
      forwardPacket(buffer, srcAddress, outgoing);
      return;
    }
    MockNetwork.answerNextScmpEchos.decrementAndGet();

    buffer.rewind();
    InetSocketAddress dstAddress = PackageVisibilityHelper.getDstAddress(buffer);
    // From here on we use linear reading using the buffer's position() mechanism
    buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
    Path path = PackageVisibilityHelper.getResponsePath(buffer, (InetSocketAddress) srcAddress);
    Scmp.Type type = ScmpParser.extractType(buffer);
    Scmp.Message scmpMsg = PackageVisibilityHelper.createMessage(type, path);
    ScmpParser.consume(buffer, scmpMsg);
    Scmp.TypeCode typeCode = scmpMsg.getTypeCode();
    logger.info("{} received SCMP {} {}", name, typeCode.name(), typeCode.getText());

    if (scmpMsg instanceof Scmp.EchoMessage) {
      // send back!
      // This is very basic:
      // - we always answer regardless of whether we are actually the destination.
      // - We do not invert path / addresses
      sendScmp(Scmp.TypeCode.TYPE_129, buffer, srcAddress, incoming);
    } else if (scmpMsg instanceof Scmp.TracerouteMessage) {
      answerTraceRoute(buffer, srcAddress, incoming);
    } else {
      // forward error
      logger.info(
          "{} forwarding SCMP error {} from {} to {}",
          name,
          typeCode.getText(),
          srcAddress,
          dstAddress);
      outgoing.send(buffer, dstAddress);
      buffer.clear();
    }
  }

  private void sendScmp(
      Scmp.TypeCode type, ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel channel)
      throws IOException {
    // send back!
    buffer.rewind();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(buffer);
    spi.reversePath();
    ScmpHeader scmpHeader = spi.getScmpHeader();
    scmpHeader.setCode(type);
    ByteBuffer out = ByteBuffer.allocate(100);
    spi.writePacketSCMP(out);
    out.flip();
    channel.send(out, srcAddress);
    buffer.clear();
  }

  private void answerTraceRoute(
      ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel incoming) throws IOException {
    // This is very basic:
    // - we always answer regardless of whether we are actually the destination.
    buffer.rewind();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(buffer);
    spi.reversePath();
    ScmpHeader scmpHeader = spi.getScmpHeader();
    scmpHeader.setCode(Scmp.TypeCode.TYPE_131);
    PathHeaderScion phs = spi.getPathHeaderScion();
    for (int i = 0; i < phs.getHopCount(); i++) {
      HopField hf = phs.getHopField(i);
      // These answers are hardcoded to work specifically with ScmpTest.traceroute()
      if (hf.hasEgressAlert()) {
        scmpHeader.setTraceData(ScionUtil.parseIA("1-ff00:0:112"), 42);
      }
      if (hf.hasIngressAlert()) {
        scmpHeader.setTraceData(ScionUtil.parseIA("1-ff00:0:110"), 42);
      }
    }
    ByteBuffer out = ByteBuffer.allocate(100);
    spi.writePacketSCMP(out);
    out.flip();
    incoming.send(out, srcAddress);
    buffer.clear();
  }

  public int getPort1() {
    return port1;
  }
}
