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
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import org.scion.jpan.internal.snap.SnapControlClient;
import org.scion.jpan.internal.snap.SnapControlEndpointResolver;
import org.scion.jpan.internal.snap.SnapService;
import org.scion.jpan.internal.snap.SnapTunnelSession;
import org.scion.jpan.selectors.PathSelector;
import org.scion.jpan.selectors.PathSelectorFactory;

/** SCION datagram channel using SNAP encapsulation instead of direct UDP underlay. */
final class SnapScionDatagramChannel extends ScionDatagramChannel {

  private final SnapTunnelSession snapTunnel;

  SnapScionDatagramChannel(
      ScionService service,
      DatagramChannel channel,
      PathSelector pathProvider,
      PathSelectorFactory factory,
      SnapTunnelSession snapTunnel)
      throws IOException {
    super(service, channel, pathProvider, factory);
    this.snapTunnel = snapTunnel;
  }

  static SnapScionDatagramChannel create(
      ScionService service,
      DatagramChannel channel,
      PathSelector pathProvider,
      PathSelectorFactory factory)
      throws IOException {
    SnapService dp = service.getSnapDataPlane();
    if (dp == null || dp.getSnapStaticX25519() == null) {
      throw new ScionRuntimeException(
          "SNAP mode requested but no SNAP dataplane/static key available");
    }

    String snapTunControlEndpoint = dp.getSnapTunControlAddress();
    if (snapTunControlEndpoint == null || snapTunControlEndpoint.isEmpty()) {
      snapTunControlEndpoint = SnapControlEndpointResolver.resolve(service.getLocalAS());
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
    return new SnapScionDatagramChannel(service, channel, pathProvider, factory, session);
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

  @Override
  public int send(ByteBuffer srcBuffer, SocketAddress destination) throws IOException {
    writeLock().lock();
    try {
      ensureSnapSourceAddress();
      return super.send(srcBuffer, destination);
    } finally {
      writeLock().unlock();
    }
  }

  @Override
  public int send(ByteBuffer srcBuffer, Path path) throws IOException {
    writeLock().lock();
    try {
      ensureSnapSourceAddress();
      return super.send(srcBuffer, path);
    } finally {
      writeLock().unlock();
    }
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    writeLock().lock();
    try {
      ensureSnapSourceAddress();
      return super.write(src);
    } finally {
      writeLock().unlock();
    }
  }

  /**
   * Ensures the SNAP tunnel handshake has completed and installs the SNAP-server-assigned address
   * as the SCION source address. Without this, the source address would fall back to {@link
   * org.scion.jpan.internal.NatMapping}, which knows nothing about the SNAP tunnel and would report
   * the local (pre-NAT) address of an underlay socket that isn't even used to send traffic.
   */
  private void ensureSnapSourceAddress() throws IOException {
    if (getOverrideSourceAddress() != null) {
      return;
    }
    snapTunnel.ensureConnected();
    InetSocketAddress assigned = snapTunnel.localTunnelAddress();
    if (assigned != null) {
      setOverrideSourceAddress(assigned);
    }
  }
}
