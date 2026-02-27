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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Instant;
import java.util.List;
import org.scion.jpan.internal.PathProvider;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.internal.header.ScionHeaderParser;
import org.scion.jpan.internal.paths.ControlServiceGrpc;
import org.scion.jpan.internal.paths.Segments;
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

  public static List<PathMetadata> getPathsCS(ScionService ss, long srcIsdAs, long dstIsdAs) {
    boolean minimizeRequests =
        ScionUtil.getPropertyOrEnv(
            Constants.PROPERTY_RESOLVER_MINIMIZE_REQUESTS,
            Constants.ENV_RESOLVER_MINIMIZE_REQUESTS,
            Constants.DEFAULT_RESOLVER_MINIMIZE_REQUESTS);
    ControlServiceGrpc cs = ss.getControlServiceConnection();
    return Segments.getPathsCS(cs, ss.getLocalAS(), srcIsdAs, dstIsdAs, minimizeRequests);
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
    InetSocketAddress dstAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
    try {
      InetAddress dstIP = InetAddress.getByAddress(ExamplePacket.SRC_HOST);
      return createDummyPath(0, 0, dstIP, 55555, new byte[0], dstAddr);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
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
            .build();
    return RequestPath.create(path, srcIsdAs, dstIsdAs, dstHost, dstPort);
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
    return RequestPath.create(builder.build(), isdAs, isdAs, dstHost, dstPort);
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
    return RequestPath.create(path, srcIsdAs, dstIsdAs, dstHost, dstPort);
  }

  public static RequestPath createRequestPath(
      PathMetadata path, long srcIsdAs, long dstIsdAs, InetSocketAddress dst) {
    return RequestPath.create(path, srcIsdAs, dstIsdAs, dst.getAddress(), dst.getPort());
  }

  public static Path createExpiredPath(Path base, int expiredSinceSecs) {
    long time = Instant.now().getEpochSecond() - expiredSinceSecs;
    PathMetadata m = PathMetadata.newBuilder().from(base.getMetadata()).setExpiration(time).build();
    return RequestPath.create(
        m,
        base.getLocalIsdAs(),
        base.getRemoteIsdAs(),
        base.getRemoteAddress(),
        base.getRemotePort());
  }

  public abstract static class AbstractChannel extends AbstractScionChannel<AbstractChannel> {
    protected AbstractChannel(
        ScionService service, DatagramChannel channel, PathProvider pathProvider) {
      super(service, channel, pathProvider);
    }
  }
}
