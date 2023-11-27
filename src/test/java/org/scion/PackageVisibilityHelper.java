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
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.List;
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

  public static Path createDummyPath() {
    return createDummyPath(
        0, 0, ExamplePacket.SRC_HOST, 55555, new byte[0], new InetSocketAddress(12345));
  }

  public static RequestPath createDummyPath(
      long srcIsdAs,
      long dstIsdAs,
      byte[] dstHost,
      int dstPort,
      byte[] raw,
      InetSocketAddress firstHop) {
    ByteString bs = ByteString.copyFrom(raw);
    String firstHopString = firstHop.getHostString() + ":" + firstHop.getPort();
    Daemon.Interface inter =
        Daemon.Interface.newBuilder()
            .setAddress(Daemon.Underlay.newBuilder().setAddress(firstHopString).build())
            .build();
    Daemon.Path path = Daemon.Path.newBuilder().setRaw(bs).setInterface(inter).build();
    return RequestPath.create(path, srcIsdAs, dstIsdAs, dstHost, dstPort);
  }
}
