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

package org.scion.jpan;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Instant;
import java.util.List;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.internal.header.ScionHeaderParser;
import org.scion.jpan.internal.paths.ControlServiceGrpc;
import org.scion.jpan.internal.snap.SnapTunnel;
import org.scion.jpan.internal.snap.SnapUnderlay;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.selectors.PathSelector;
import org.scion.jpan.selectors.PathSelectorFactory;
import org.scion.jpan.selectors.PathSelectorWithRefresh;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockNetwork;

/**
 * Helper class to access package private methods in org.scion.ScionService and ScionPacketHelper.
 */
public class PackageVisibilityHelper {

  public static final String DEBUG_PROPERTY_DNS_MOCK = HeaderConstants.DEBUG_PROPERTY_MOCK_DNS_TXT;

  public static void setIgnoreEnvironment(boolean flag) {
    Constants.debugIgnoreEnvironment = flag;
  }

  public static ControlServiceGrpc getControlService(ScionService ss) {
    return ss.getControlServiceConnection();
  }

  public static List<PathMetadata> getPaths(ScionService ss, long srcIsdAs, long dstIsdAs) {
    return ss.getPathList(srcIsdAs, dstIsdAs);
  }

  public static HeaderConstants.HdrTypes getNextHdr(ByteBuffer packet) {
    return ScionHeaderParser.extractNextHeader(packet);
  }

  public static InetSocketAddress getDstAddress(ByteBuffer packet) {
    return ScionHeaderParser.extractDestinationSocketAddress(packet);
  }

  public static ResponsePath getResponsePath(ByteBuffer packet, InetSocketAddress firstHop) {
    return ScionHeaderParser.extractResponsePath(packet, firstHop);
  }

  /**
   * @param firstHop Can be 'null'. If 'null': uses MockNetwork.getBorderRouterAddress1().
   * @return RequestPath
   */
  public static RequestPath createMockRequestPath(InetSocketAddress firstHop) {
    if (firstHop == null) {
      firstHop = MockNetwork.getBorderRouterAddress1();
    }
    return PackageVisibilityHelper.createDummyPath(
        ScionUtil.parseIA("1-ff00:0:110"),
        ScionUtil.parseIA("1-ff00:0:112"),
        new byte[] {127, 0, 0, 1},
        54321,
        ExamplePacket.PATH_RAW_TINY_110_112,
        firstHop);
  }

  public static RequestPath createDummyPath() {
    try {
      InetAddress dstIP = InetAddress.getByAddress(ExamplePacket.SRC_HOST);
      return createDummyPath(dstIP, 55555);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  public static RequestPath createDummyPath(InetSocketAddress dstAddr) {
    return createDummyPath(dstAddr.getAddress(), dstAddr.getPort());
  }

  public static RequestPath createDummyPath(ScionSocketAddress dstAddr) {
    InetAddress dstIp = dstAddr.getAddress();
    InetSocketAddress firstHop = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    return createDummyPath(0, dstAddr.getIsdAs(), dstIp, dstAddr.getPort(), new byte[0], firstHop);
  }

  public static RequestPath createDummyPath(InetAddress dstIP, int dstPort) {
    InetSocketAddress firstHop = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    return createDummyPath(0, 0, dstIP, dstPort, new byte[0], firstHop);
  }

  public static RequestPath createDummyPath(
      long srcIsdAs,
      long dstIsdAs,
      byte[] dstHost,
      int dstPort,
      byte[] raw,
      InetSocketAddress firstHop) {
    try {
      return createDummyPath(
          srcIsdAs, dstIsdAs, InetAddress.getByAddress(dstHost), dstPort, raw, firstHop);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  public static RequestPath createDummyPath(
      long srcIsdAs,
      long dstIsdAs,
      InetAddress dstHost,
      int dstPort,
      byte[] raw,
      InetSocketAddress firstHop) {
    String firstHopString = firstHop.getHostString() + ":" + firstHop.getPort();
    PathMetadata.Interface inter = PathMetadata.Interface.create(firstHopString);
    long ts = Instant.now().getEpochSecond() + 100;
    PathMetadata path =
        PathMetadata.newBuilder()
            .setRaw(raw.clone())
            .setLocalInterface(inter)
            .setExpiration(ts)
            .setSrcIsdAs(srcIsdAs)
            .setDstIsdAs(dstIsdAs)
            .build();
    return RequestPath.create(path, dstHost, dstPort, IPHelper.toInetSocketAddress(firstHopString));
  }

  public static ResponsePath createDummyResponsePath(
      byte[] raw,
      long srcIsdAs,
      byte[] srcIP,
      int srcPort,
      long dstIsdAs,
      byte[] dstIP,
      int dstPort,
      InetSocketAddress firstHop) {
    try {
      InetAddress src = InetAddress.getByAddress(srcIP);
      InetAddress dst = InetAddress.getByAddress(dstIP);
      return ResponsePath.create(raw, srcIsdAs, src, srcPort, dstIsdAs, dst, dstPort, firstHop);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  public static RequestPath createRequestPath110_110(
      PathMetadata.Builder builder, long isdAs, InetAddress dstHost, int dstPort) {
    builder.setSrcIsdAs(isdAs).setDstIsdAs(isdAs);
    InetSocketAddress firstHop = new InetSocketAddress(dstHost, dstPort);
    return RequestPath.create(builder.build(), dstHost, dstPort, firstHop);
  }

  public static RequestPath createRequestPath110_112(
      PathMetadata.Builder builder, InetAddress dstHost, int dstPort, InetSocketAddress firstHop) {
    long srcIsdAs = ExamplePacket.SRC_IA;
    long dstIsdAs = ExamplePacket.DST_IA;
    String firstHopString = firstHop.getHostString() + ":" + firstHop.getPort();
    PathMetadata.Interface inter = PathMetadata.Interface.create(firstHopString);
    PathMetadata path =
        builder
            .setRaw(ExamplePacket.PATH_RAW_TINY_110_112)
            .setLocalInterface(inter)
            .addInterfaces(PathMetadata.PathInterface.create(srcIsdAs, 2))
            .addInterfaces(PathMetadata.PathInterface.create(dstIsdAs, 1))
            .build();
    return RequestPath.create(path, dstHost, dstPort, IPHelper.toInetSocketAddress(firstHopString));
  }

  public static RequestPath createRequestPath(PathMetadata path, InetSocketAddress dst) {
    InetSocketAddress firstHop = IPHelper.toInetSocketAddress("127.0.0.1:22311");
    return RequestPath.create(path, dst.getAddress(), dst.getPort(), firstHop);
  }

  public static Path createExpiredPath(Path base, int expiredSinceSecs) {
    long time = Instant.now().getEpochSecond() - expiredSinceSecs;
    PathMetadata m = PathMetadata.newBuilder().from(base.getMetadata()).setExpiration(time).build();
    InetSocketAddress firstHop = IPHelper.toInetSocketAddress("127.0.0.1:22311");
    return RequestPath.create(m, base.getRemoteAddress(), base.getRemotePort(), firstHop);
  }

  public static ScionSocketAddress toSSA(long isdAs, InetSocketAddress dstAddr) {
    return ScionSocketAddress.from(isdAs, dstAddr.getAddress(), dstAddr.getPort());
  }

  public static ScionSocketAddress toSSA(String isdAs, InetSocketAddress dstAddr) {
    return toSSA(ScionUtil.parseIA(isdAs), dstAddr);
  }

  public static String getFirstHop(ScionService ss, PathMetadata path) {
    int id = (int) path.getInterfaces().get(0).getId();
    return IPHelper.toString(ss.getLocalAS().getBorderRouterAddress(id));
  }

  /**
   * Creates a {@link ScionDatagramChannel} in SNAP mode, backed by the given {@link SnapTunnel},
   * for unit-testing SNAP channel behavior without a real {@link ScionService}. The channel has no
   * path selector/factory (both null), since neither is needed unless the caller sends via
   * address-based resolution (as opposed to an explicit {@link Path}) -- an earlier version of this
   * helper built a selector via {@link Scion#defaultService()}, which depends on live DNS/daemon
   * resolution and made every test using it flaky/environment-dependent for no reason.
   */
  public static ScionDatagramChannel openSnapChannel(SnapTunnel tunnel) throws IOException {
    return openSnapChannel(null, tunnel);
  }

  /**
   * Like {@link #openSnapChannel(SnapTunnel)}, but attaches the given {@link ScionService} (a real
   * path selector/factory is only built when {@code service} is non-null). Needed for tests that
   * exercise address-based resolution (e.g. {@code send(ByteBuffer, SocketAddress)}), which
   * requires a real selector. Pass {@code null} for the same lightweight, DNS/daemon-independent
   * behavior as the single-argument overload.
   */
  public static ScionDatagramChannel openSnapChannel(ScionService service, SnapTunnel tunnel)
      throws IOException {
    DatagramChannel udp = DatagramChannel.open();
    SnapUnderlay snapUnderlay = SnapUnderlay.wrap(tunnel);
    if (service == null) {
      return new ScionDatagramChannel(null, udp, null, null, snapUnderlay);
    }
    PathSelector selector = PathSelectorWithRefresh.create(service, PathPolicy.DEFAULT);
    PathSelectorFactory factory = PathSelectorWithRefresh.Factory.create(PathPolicy.DEFAULT);
    return new ScionDatagramChannel(service, udp, selector, factory, snapUnderlay);
  }

  public abstract static class AbstractChannel extends AbstractScionChannel<AbstractChannel> {
    protected AbstractChannel(
        ScionService service,
        DatagramChannel channel,
        PathSelector selector,
        PathSelectorFactory factory) {
      super(service, channel, selector, factory);
    }
  }
}
