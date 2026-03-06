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
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.scion.jpan.*;
import org.scion.jpan.internal.util.IPHelper;

/**
 * The mock network is a simplified version of the test network available in scionproto. The mock is
 * primarily used to run the "tiny4" network. Some simplifications:<br>
 *
 * <p>- The mock has only two "border routers". They act as border routers for _all_ ASes. There are
 * two border routers to allow having multiple links between ASes.<br>
 * - The mock border routers forward traffic directly to the target AS, even if there is no direct
 * link in the topology.<br>
 * - The IP on both sides of the BR (link), at least by default, the same.<br>
 * - the border routers do only marginal verification on packets.<br>
 */
public class MockNetwork {

  // Number of calls that the service calls the daemon during initialization:
  // port-range, local AS, border routers
  public static final int SERVICE_TO_DAEMON_INIT_CALLS = 3;
  private static final String TINY_SRV_ADDR_1 = "127.0.0.112";
  private static final byte[] TINY_SRV_ADDR_BYTES_1 = {127, 0, 0, 112};
  private static final int TINY_SRV_PORT_1 = 22233;
  public static final String TINY_SRV_ISD_AS = "1-ff00:0:112";
  public static final String TINY_SRV_NAME_1 = "server.as112.test";
  public static final String TINY_SRV_TOPO_V4 = "topologies/tiny4/ASff00_0_112";
  public static final String TINY_SRV_TOPO_V6 = "topologies/tiny/ASff00_0_112";
  public static final String TINY_CLIENT_ISD_AS = "1-ff00:0:110";
  public static final String TINY_CLIENT_TOPO_V4 = MockBootstrapServer.TOPO_TINY_110;
  private static final String TINY_CLIENT_TOPO_V6 = "topologies/tiny/ASff00_0_110";
  private final AtomicBoolean hasStopped = new AtomicBoolean(false);
  private MockBorderRouterRunner routers = MockBorderRouterRunner.create();
  private MockDaemon daemon = null;
  private MockBootstrapServer topoServer;
  private final List<MockControlServer> controlServices = new ArrayList<>();

  private static MockNetwork mock;
  private final AsInfo asInfoLocal;
  private final AsInfo asInfoRemote;

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
    if (mock != null && !mock.hasStopped.get()) {
      throw new IllegalStateException();
    }
    mock = new MockNetwork(localTopo, remoteTopo, mode);
  }

  private MockNetwork(String localTopo, String remoteTopo, Mode mode) {
    asInfoLocal = JsonFileParser.parseTopology(Paths.get(localTopo));
    asInfoRemote = JsonFileParser.parseTopology(Paths.get(remoteTopo));
    asInfoLocal.connectWith(asInfoRemote);
    routers = MockBorderRouterRunner.create();

    String remoteIP = asInfoLocal.getBorderRouterAddressByIA(ScionUtil.parseIA(TINY_SRV_ISD_AS));
    remoteIP = IPHelper.extractIP(remoteIP);
    if (!useShimConfig()) {
      // Do not start a SCMP handler on 30041 if we want to use SHIMs.
      // The SHIM also includes its own SCMP handler.
      MockScmpHandler.start(remoteIP);
    }

    for (AsInfo.BorderRouter br : asInfoLocal.getBorderRouters()) {
      for (AsInfo.BorderRouterInterface brIf : br.getInterfaces()) {
        if (brIf.getRemoteInterface() == null) {
          // can happen for e.g. AS 111
          continue;
        }
        String remote = brIf.getRemoteInterface().getBorderRouter().getInternalAddress();
        InetSocketAddress bind1 = IPHelper.toInetSocketAddress(br.getInternalAddress());
        InetSocketAddress bind2 = IPHelper.toInetSocketAddress(remote);
        routers.add(bind1, bind2, brIf.id, brIf.getRemoteInterface().id);
      }
    }

    routers.start();

    if (mode == Mode.DAEMON) {
      daemon = MockDaemon.createForBorderRouter(routers.getBorderRouters()).start();
    }

    MockDNS.install(TINY_SRV_ISD_AS, TINY_SRV_NAME_1, TINY_SRV_ADDR_1);

    switch (mode) {
      case BOOTSTRAP:
      case NAPTR:
        topoServer = MockBootstrapServer.start(localTopo, mode == Mode.NAPTR);
        for (InetSocketAddress cs : asInfoLocal.getControlServerAddresses()) {
          controlServices.add(MockControlServer.start(cs.getPort()));
        }
        break;
      case AS_ONLY:
        for (InetSocketAddress cs : asInfoLocal.getControlServerAddresses()) {
          controlServices.add(MockControlServer.start(cs.getPort()));
        }
        break;
      case DAEMON:
        break;
    }
  }

  public static synchronized void stopTiny() {
    if (mock != null) {
      mock.stop();
    }
    MockDNS.clear();
    MockScmpHandler.stop();
  }

  public synchronized void stop() {
    controlServices.forEach(MockControlServer::close);
    controlServices.clear();
    if (topoServer != null) {
      topoServer.close();
    }

    if (daemon != null) {
      daemon.close();
      daemon = null;
    }

    routers.stop();
    hasStopped.set(true);
  }

  public static boolean useShim() {
    if (mock == null) {
      // Probably running MockNetwork2
      return false;
    }
    return mock.useShimConfig();
  }

  private boolean useShimConfig() {
    String config = ScionUtil.getPropertyOrEnv(Constants.PROPERTY_SHIM, Constants.ENV_SHIM);
    boolean hasAllPorts = asInfoLocal.getPortRange().hasPortRangeALL();
    return config != null ? Boolean.parseBoolean(config) : !hasAllPorts;
  }

  public static InetSocketAddress getBorderRouterAddress1() {
    return mock.routers.getBorderRouters().get(0).getAddress1();
  }

  public static List<InetSocketAddress> getBorderRouterAddresses() {
    return mock.routers.getBorderRouters().stream()
        .map(MockBorderRouter::getAddress1)
        .collect(Collectors.toList());
  }

  public static InetSocketAddress getTinyServerAddress() throws IOException {
    return new InetSocketAddress(
        InetAddress.getByAddress(TINY_SRV_NAME_1, TINY_SRV_ADDR_BYTES_1), TINY_SRV_PORT_1);
  }

  public static int getAndResetForwardCount() {
    if (mock == null) {
      return 0;
    }
    return mock.routers.getAndResetForwardCounters();
  }

  public static int getTotalForwardCount() {
    return mock == null ? 0 : mock.routers.getTotalForwardCount();
  }

  public static int getAndResetStunCount() {
    return mock.routers.getAndResetStunCount();
  }

  /**
   * Set the routers to drop the next n packets.
   *
   * @param n packets to drop
   */
  public static void dropNextPackets(int n) {
    mock.routers.dropNextPackets(n);
  }

  public static void returnScmpErrorOnNextPacket(Scmp.TypeCode scmpTypeCode) {
    mock.routers.returnScmpErrorOnNextPacket(scmpTypeCode);
  }

  public static MockBootstrapServer getTopoServer() {
    return mock.topoServer;
  }

  public static MockControlServer getControlServer() {
    return mock.controlServices.get(0);
  }

  public static List<MockControlServer> getControlServers() {
    return Collections.unmodifiableList(mock.controlServices);
  }

  public static MockDaemon getDaemon() {
    return mock.daemon;
  }

  public static void disableStun() {
    mock.routers.disableStun();
  }

  public static void setStunCallback(Predicate<ByteBuffer> stunCallback) {
    mock.routers.setStunCallback(stunCallback);
  }

  public static AsInfo getLocalAsInfo() {
    return mock.asInfoLocal;
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
