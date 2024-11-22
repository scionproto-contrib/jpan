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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.jpan.*;
import org.scion.jpan.internal.IPHelper;
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

  private static final String TINY_SRV_ADDR_1 = "127.0.0.112";
  private static final byte[] TINY_SRV_ADDR_BYTES_1 = {127, 0, 0, 112};
  private static final int TINY_SRV_PORT_1 = 22233;
  public static final String TINY_SRV_ISD_AS = "1-ff00:0:112";
  public static final String TINY_SRV_NAME_1 = "server.as112.test";
  public static final String TINY_SRV_TOPO_V4 = "topologies/tiny4/ASff00_0_112";
  public static final String TINY_SRV_TOPO_V6 = "topologies/tiny/ASff00_0_112";
  public static final String TINY_CLIENT_ISD_AS = "1-ff00:0:110";
  private static final String TINY_CLIENT_TOPO_V4 = MockBootstrapServer.TOPO_TINY_110;
  private static final String TINY_CLIENT_TOPO_V6 = "topologies/tiny/ASff00_0_110";
  static final AtomicInteger nForwardTotal = new AtomicInteger();
  static final AtomicIntegerArray nForwards = new AtomicIntegerArray(20);
  static final AtomicInteger dropNextPackets = new AtomicInteger();
  static final AtomicReference<Scmp.TypeCode> scmpErrorOnNextPacket = new AtomicReference<>();
  static CountDownLatch barrier = null;
  private static final Logger logger = LoggerFactory.getLogger(MockNetwork.class.getName());
  private static ExecutorService routers = null;
  private static MockDaemon daemon = null;
  private static MockBootstrapServer topoServer;
  private static MockControlServer controlServer;
  static AsInfo asInfo;

  private static MockNetwork mock;
  private final AsInfo asInfoLocal;
  private final AsInfo asInfoRemote;
  private final InetSocketAddress[] localAddress = new InetSocketAddress[2];

  /**
   * Start a network with one daemon and a border router. The border router connects "1-ff00:0:110"
   * (considered local) with "1-ff00:0:112" (remote). This also installs a DNS TXT record for
   * resolving the SRV-address to "1-ff00:0:112".
   */
  public static synchronized void startTiny() {
    startTiny(true);
  }

  /**
   * @param remoteIPv4 Whether the remote AS (112) should use IPv6 or not. Note that IPv6 addresses
   *     are mapped to ::1.
   */
  public static synchronized void startTiny(boolean remoteIPv4) {
    if (remoteIPv4) {
      startTiny(TINY_CLIENT_TOPO_V4, TINY_SRV_TOPO_V4, Mode.DAEMON);
    } else {
      startTiny(TINY_CLIENT_TOPO_V6, TINY_SRV_TOPO_V6, Mode.DAEMON);
    }
  }

  public static synchronized void startTiny(Mode mode) {
    startTiny(TINY_CLIENT_TOPO_V4, TINY_SRV_TOPO_V4, mode);
  }

  private static synchronized void startTiny(String localTopo, String remoteTopo, Mode mode) {
    if (routers != null) {
      throw new IllegalStateException();
    }

    mock = new MockNetwork(localTopo, remoteTopo);

    routers = Executors.newFixedThreadPool(2);

    String remoteIP =
        mock.asInfoLocal.getBorderRouterAddressByIA(ScionUtil.parseIA(TINY_SRV_ISD_AS));
    remoteIP = IPHelper.extractIP(remoteIP);
    if (!useShim()) {
      // Do not start a SCMP handler on 30041 if we want to use SHIMs.
      // The SHIM also includes its own SCMP handler.
      MockScmpHandler.start(remoteIP);
    }

    List<MockBorderRouter> brList = new ArrayList<>();
    for (AsInfo.BorderRouter br : mock.asInfoLocal.getBorderRouters()) {
      for (AsInfo.BorderRouterInterface brIf : br.getInterfaces()) {
        if (brIf.getRemoteInterface() == null) {
          // can happen for e.g. AS 111
          continue;
        }
        String remote = brIf.getRemoteInterface().getBorderRouter().getInternalAddress();
        InetSocketAddress bind1 = IPHelper.toInetSocketAddress(br.getInternalAddress());
        InetSocketAddress bind2 = IPHelper.toInetSocketAddress(remote);
        brList.add(
            new MockBorderRouter(
                brList.size(), bind1, bind2, brIf.id, brIf.getRemoteInterface().id));
        mock.localAddress[brList.size() - 1] = bind1;
      }
    }

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

    if (mode == Mode.DAEMON) {
      try {
        daemon = MockDaemon.createForBorderRouter(brList).start();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    MockDNS.install(TINY_SRV_ISD_AS, TINY_SRV_NAME_1, TINY_SRV_ADDR_1);

    switch (mode) {
      case BOOTSTRAP:
      case NAPTR:
        topoServer = MockBootstrapServer.start(localTopo, mode == Mode.NAPTR);
        asInfo = topoServer.getASInfo();
        controlServer = MockControlServer.start(asInfo.getControlServerPort());
        break;
      case AS_ONLY:
        asInfo = JsonFileParser.parseTopology(Paths.get(localTopo));
        controlServer = MockControlServer.start(asInfo.getControlServerPort());
        break;
      case DAEMON:
        asInfo = daemon.getASInfo();
        break;
    }

    dropNextPackets.getAndSet(0);
    scmpErrorOnNextPacket.set(null);
    getAndResetForwardCount();
  }

  public static synchronized void stopTiny() {
    if (controlServer != null) {
      controlServer.close();
    }
    if (topoServer != null) {
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
    scmpErrorOnNextPacket.set(null);
    asInfo = null;
    mock = null;
  }

  private MockNetwork(String localTopo, String remoteTopo) {
    asInfoLocal = JsonFileParser.parseTopology(Paths.get(localTopo));
    asInfoRemote = JsonFileParser.parseTopology(Paths.get(remoteTopo));
    asInfoLocal.connectWith(asInfoRemote);
  }

  public static boolean useShim() {
    String config = ScionUtil.getPropertyOrEnv(Constants.PROPERTY_SHIM, Constants.ENV_SHIM);
    boolean hasAllPorts = mock.asInfoLocal.getPortRange().hasPortRangeALL();
    return config != null ? Boolean.parseBoolean(config) : !hasAllPorts;
  }

  public static InetSocketAddress getBorderRouterAddress1() {
    return mock.localAddress[0];
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

  public static int getForwardCount() {
    return nForwardTotal.get();
  }

  /**
   * Set the routers to drop the next n packets.
   *
   * @param n packets to drop
   */
  public static void dropNextPackets(int n) {
    dropNextPackets.set(n);
  }

  public static void returnScmpErrorOnNextPacket(Scmp.TypeCode scmpTypeCode) {
    scmpErrorOnNextPacket.set(scmpTypeCode);
  }

  public static int getForwardCount(int routerId) {
    return nForwards.get(routerId);
  }

  public static MockBootstrapServer getTopoServer() {
    return topoServer;
  }

  public static MockControlServer getControlServer() {
    return controlServer;
  }

  public enum Mode {
    /** Start daemon (and no bootstrap server). */
    DAEMON,
    /** Install bootstrap server with DNS NAPTR record. */
    NAPTR,
    /** Install bootstrap server (without DNS). */
    BOOTSTRAP,
    /**
     * Install neither daemon nor BOOTSTRAP server (and no DNS). This is not an official scenario
     * but a desirable feature. There is only a topofile, but no TRC (meta) files.
     */
    AS_ONLY
  }
}
