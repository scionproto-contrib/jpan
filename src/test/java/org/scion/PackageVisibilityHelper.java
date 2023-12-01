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

package org.scion;

import com.google.protobuf.ByteString;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import org.scion.internal.ScionHeaderParser;
import org.scion.proto.daemon.Daemon;
import org.scion.testutil.ExamplePacket;

/**
 * Helper class to access package private methods in org.scion.ScionService and ScionPacketHelper.
 */
public class PackageVisibilityHelper {

  public static final String DEBUG_PROPERTY_DNS_MOCK = ScionConstants.DEBUG_PROPERTY_DNS_MOCK;

  public static InetSocketAddress getDstAddress(ByteBuffer packet) throws UnknownHostException {
    return ScionHeaderParser.readDestinationSocketAddress(packet);
  }

  public static RequestPath createDummyPath() {
    return createDummyPath(
        0, ExamplePacket.SRC_HOST, 55555, new byte[0], new InetSocketAddress(12345));
  }

  public static RequestPath createDummyPath(
      long dstIsdAs, byte[] dstHost, int dstPort, byte[] raw, InetSocketAddress firstHop) {
    ByteString bs = ByteString.copyFrom(raw);
    String firstHopString = firstHop.getHostString() + ":" + firstHop.getPort();
    Daemon.Interface inter =
        Daemon.Interface.newBuilder()
            .setAddress(Daemon.Underlay.newBuilder().setAddress(firstHopString).build())
            .build();
    Daemon.Path path = Daemon.Path.newBuilder().setRaw(bs).setInterface(inter).build();
    return RequestPath.create(path, dstIsdAs, dstHost, dstPort);
  }

  public static RequestPath createRequestPath110_112(
      Daemon.Path.Builder builder,
      long dstIsdAs,
      byte[] dstHost,
      int dstPort,
      InetSocketAddress firstHop) {
    ByteString bs = ByteString.copyFrom(ExamplePacket.PATH_RAW_TINY_110_112);
    String firstHopString = firstHop.getHostString() + ":" + firstHop.getPort();
    Daemon.Interface inter =
        Daemon.Interface.newBuilder()
            .setAddress(Daemon.Underlay.newBuilder().setAddress(firstHopString).build())
            .build();
    Daemon.Path path =
        builder
            .setRaw(bs)
            .setInterface(inter)
            .addInterfaces(
                Daemon.PathInterface.newBuilder().setId(2).setIsdAs(ExamplePacket.SRC_IA).build())
            .addInterfaces(
                Daemon.PathInterface.newBuilder().setId(1).setIsdAs(ExamplePacket.DST_IA).build())
            .build();
    return RequestPath.create(path, dstIsdAs, dstHost, dstPort);
  }
}
