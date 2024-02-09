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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
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
import java.util.stream.Collectors;
import org.scion.PackageVisibilityHelper;
import org.scion.ResponsePath;
import org.scion.Scmp;
import org.scion.internal.ScionHeaderParser;
import org.scion.internal.ScmpParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockNetwork {

  public static final String BORDER_ROUTER_HOST = "127.0.0.1";
  private static final int BORDER_ROUTER_PORT1 = 30555;
  private static final int BORDER_ROUTER_PORT2 = 30556;
  public static final String TINY_SRV_ADDR_1 = "127.0.0.112";
  public static final int TINY_SRV_PORT_1 = 22233;
  public static final String TINY_SRV_ISD_AS = "1-ff00:0:112";
  public static final String TINY_SRV_NAME_1 = "server.as112.test";
  private static final Logger logger = LoggerFactory.getLogger(MockNetwork.class.getName());
  private static ExecutorService routers = null;
  private static MockDaemon daemon = null;
  static final AtomicInteger nForwardTotal = new AtomicInteger();
  static final AtomicIntegerArray nForwards = new AtomicIntegerArray(20);
  private static MockTopologyServer topoServer;
  private static MockControlServer controlServer;

  public enum Mode {
    /** Start daemon */
    DAEMON,
    /** Install bootstrap server with DNS NAPTR record */
    NAPTR,
    /** Install bootstrap server */
    BOOTSTRAP
  }

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
  }

  public static InetSocketAddress getTinyServerAddress() {
    return new InetSocketAddress(TINY_SRV_ADDR_1, TINY_SRV_PORT_1);
  }

  public static int getAndResetForwardCount() {
    for (int i = 0; i < nForwards.length(); i++) {
      nForwards.set(i, 0);
    }
    return nForwardTotal.getAndSet(0);
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

            switch (PackageVisibilityHelper.getNextHdr(buffer)) {
              case UDP:
                handleUdp(buffer, srcAddress, outgoing);
                break;
              case SCMP:
                handleScmp(buffer, srcAddress, outgoing);
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

  private void handleScmp(ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel outgoing)
          throws IOException {
    // From here on we use linear reading using the buffer's position() mechanism
    buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
    ResponsePath path = PackageVisibilityHelper.getResponsePath(buffer, (InetSocketAddress) srcAddress);
    Scmp.ScmpMessage scmpMsg = ScmpParser.consume(buffer, path);
    logger.info(" received SCMP " + scmpMsg.getTypeCode().name() + " " + scmpMsg.getTypeCode().getText());

    if (scmpMsg instanceof Scmp.ScmpEcho) {
      outgoing.send(buffer, srcAddress);
      buffer.clear();
    } else if (scmpMsg instanceof Scmp.ScmpTraceroute) {
      // TODO return traceroute
    } else {
      // TODO forward error???
    }

//    outgoing.send(buffer, dstAddress);
//    buffer.clear();
//    MockNetwork.nForwardTotal.incrementAndGet();
//    MockNetwork.nForwards.incrementAndGet(id);
  }

  public int getPort1() {
    return port1;
  }
}
