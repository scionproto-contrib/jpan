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

package org.scion.testutil;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.scion.PackageVisibilityHelper;
import org.scion.ResponsePath;
import org.scion.ScionUtil;
import org.scion.Scmp;
import org.scion.demo.inspector.HopField;
import org.scion.demo.inspector.PathHeaderScion;
import org.scion.demo.inspector.ScionPacketInspector;
import org.scion.demo.inspector.ScmpHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockNetwork {

  public static final String BORDER_ROUTER_HOST = "127.0.0.1";
  public static final String TINY_SRV_ADDR_1 = "127.0.0.112";
  public static final byte[] TINY_SRV_ADDR_BYTES_1 = {127, 0, 0, 112};
  public static final int TINY_SRV_PORT_1 = 22233;
  public static final String TINY_SRV_ISD_AS = "1-ff00:0:112";
  public static final String TINY_SRV_NAME_1 = "server.as112.test";
  static final AtomicInteger nForwardTotal = new AtomicInteger();
  static final AtomicIntegerArray nForwards = new AtomicIntegerArray(20);
  static final AtomicInteger dropNextPackets = new AtomicInteger();
  static final AtomicReference<Scmp.TypeCode> scmpErrorOnNextPacket = new AtomicReference<>();
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
    startTiny(localIPv4, remoteIPv4, Mode.DAEMON);
  }

  public static synchronized void startTiny(Mode mode) {
    startTiny(true, true, mode);
  }

  private static synchronized void startTiny(boolean localIPv4, boolean remoteIPv4, Mode mode) {
    if (routers != null) {
      throw new IllegalStateException();
    }

    routers = Executors.newFixedThreadPool(2);

    List<MockBorderRouter> brList = new ArrayList<>();
    brList.add(
        new MockBorderRouter(0, BORDER_ROUTER_PORT1, BORDER_ROUTER_PORT2, localIPv4, remoteIPv4));
    brList.add(
        new MockBorderRouter(
            1, BORDER_ROUTER_PORT1 + 10, BORDER_ROUTER_PORT2 + 10, localIPv4, remoteIPv4));

    for (MockBorderRouter br : brList) {
      routers.execute(br);
    }

    List<InetSocketAddress> brAddrList =
        brList.stream()
            .map(mBR -> new InetSocketAddress(BORDER_ROUTER_HOST, mBR.getPort1()))
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
    dropNextPackets.getAndSet(0);
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

  public static void dropNextPackets(int n) {
    // set the routers to drop the next packet
    dropNextPackets.set(n);
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
  private final boolean ipv4_1;
  private final boolean ipv4_2;

  MockBorderRouter(int id, int port1, int port2, boolean ipv4_1, boolean ipv4_2) {
    this.id = id;
    this.name = "BorderRouter-" + id;
    this.port1 = port1;
    this.port2 = port2;
    this.ipv4_1 = ipv4_1;
    this.ipv4_2 = ipv4_2;
  }

  @Override
  public void run() {
    Thread.currentThread().setName(name);
    InetSocketAddress bind1 = new InetSocketAddress(ipv4_1 ? "localhost" : "::1", port1);
    InetSocketAddress bind2 = new InetSocketAddress(ipv4_2 ? "localhost" : "::1", port2);
    try (DatagramChannel chnLocal = DatagramChannel.open().bind(bind1);
        DatagramChannel chnRemote = DatagramChannel.open().bind(bind2);
        Selector selector = Selector.open()) {
      chnLocal.configureBlocking(false);
      chnRemote.configureBlocking(false);
      chnLocal.register(selector, SelectionKey.OP_READ, chnRemote);
      chnRemote.register(selector, SelectionKey.OP_READ, chnLocal);
      ByteBuffer buffer = ByteBuffer.allocate(66000);
      logger.info(name + " started on ports " + bind1 + " <-> " + bind2);

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

            if (MockNetwork.scmpErrorOnNextPacket.get() != null) {
              sendScmp(
                  MockNetwork.scmpErrorOnNextPacket.getAndSet(null), buffer, srcAddress, incoming);
              iter.remove();
              continue;
            }

            switch (PackageVisibilityHelper.getNextHdr(buffer)) {
              case UDP:
                handleUdp(buffer, srcAddress, outgoing);
                break;
              case SCMP:
                handleScmp(buffer, srcAddress, incoming, outgoing);
                break;
              default:
                logger.error(
                    "HDR not supported: " + PackageVisibilityHelper.getNextHdr(buffer).code());
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

  private void handleUdp(ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel outgoing)
      throws IOException {
    InetSocketAddress dstAddress = PackageVisibilityHelper.getDstAddress(buffer);
    logger.info(
        name
            + " forwarding "
            + buffer.remaining()
            + " bytes from "
            + srcAddress
            + " to "
            + dstAddress);

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
    // From here on we use linear reading using the buffer's position() mechanism
    buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
    ResponsePath path =
        PackageVisibilityHelper.getResponsePath(buffer, (InetSocketAddress) srcAddress);
    Scmp.Type type = ScmpParser.extractType(buffer);
    Scmp.Message scmpMsg = PackageVisibilityHelper.createMessage(type, path);
    ScmpParser.consume(buffer, scmpMsg);
    logger.info(
        " received SCMP " + scmpMsg.getTypeCode().name() + " " + scmpMsg.getTypeCode().getText());

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
      InetSocketAddress dstAddress = PackageVisibilityHelper.getDstAddress(buffer);
      logger.info(
          name
              + " forwarding SCMP error "
              + scmpMsg.getTypeCode().getText()
              + " from "
              + srcAddress
              + " to "
              + dstAddress);
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
