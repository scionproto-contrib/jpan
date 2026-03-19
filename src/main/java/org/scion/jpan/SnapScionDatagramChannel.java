// Copyright 2026 ETH Zurich
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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import org.scion.jpan.internal.PathProvider;
import org.scion.jpan.internal.snap.SnapControlClient;
import org.scion.jpan.internal.snap.SnapDataPlane;
import org.scion.jpan.internal.snap.SnapTunnelSession;

/** SCION datagram channel using SNAP encapsulation instead of direct UDP underlay. */
final class SnapScionDatagramChannel extends ScionDatagramChannel {

  private final SnapTunnelSession snapTunnel;

  SnapScionDatagramChannel(
      ScionService service,
      DatagramChannel channel,
      PathProvider pathProvider,
      SnapTunnelSession snapTunnel)
      throws IOException {
    super(service, channel, pathProvider);
    this.snapTunnel = snapTunnel;
  }

  static SnapScionDatagramChannel create(
      ScionService service, DatagramChannel channel, PathProvider pathProvider) throws IOException {
    SnapDataPlane dp = service.getSnapDataPlane();
    if (dp == null || dp.getSnapStaticX25519() == null) {
      throw new ScionRuntimeException("SNAP mode requested but no SNAP dataplane/static key available");
    }

    String snapTunControlEndpoint = dp.getSnapTunControlAddress();
    if (snapTunControlEndpoint == null || snapTunControlEndpoint.isEmpty()) {
      snapTunControlEndpoint = SnapControlEndpointResolver.resolve(service);
    }
    SnapControlClient snapControlClient =
        (snapTunControlEndpoint == null || snapTunControlEndpoint.isEmpty())
            ? null
            : new SnapControlClient(snapTunControlEndpoint);

    SnapTunnelSession session =
        new SnapTunnelSession(
            channel,
            dp.getAddress(),
            Arrays.copyOf(dp.getSnapStaticX25519(), 32),
            snapControlClient);
    return new SnapScionDatagramChannel(service, channel, pathProvider, session);
  }

  @Override
  protected int sendUnderlay(ByteBuffer buffer, InetSocketAddress remoteHost) throws IOException {
    byte[] scionPacket = new byte[buffer.remaining()];
    buffer.get(scionPacket);
    return snapTunnel.sendPacket(scionPacket);
  }

  @Override
  protected InetSocketAddress receiveUnderlay(ByteBuffer buffer) throws IOException {
    return snapTunnel.receivePacket(buffer);
  }
}
