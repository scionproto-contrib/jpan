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

package org.scion.jpan.internal.snap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/**
 * Encapsulates SNAP-tunnel transport for a channel: wraps a {@link SnapTunnelSession} and
 * dispatches send/receive/close through it instead of a plain UDP underlay.
 *
 * <p>A channel either holds an instance of this class (SNAP mode) or {@code null} (plain UDP
 * underlay) -- that is the only thing a channel needs to know about SNAP; the handshake,
 * encryption, and wire format all stay behind {@link SnapTunnelSession}.
 */
public final class SnapUnderlay {
  private final SnapTunnelSession session;

  private SnapUnderlay(SnapTunnelSession session) {
    this.session = session;
  }

  /** Wraps an already-built session, e.g. one pointed at a mock SNAP server for tests. */
  public static SnapUnderlay wrap(SnapTunnelSession session) {
    return session == null ? null : new SnapUnderlay(session);
  }

  /**
   * Builds a SNAP tunnel from already-resolved SNAP dataplane configuration. Callers that only have
   * a {@code ScionService} (rather than these already-resolved pieces) should go through {@code
   * org.scion.jpan.SnapUnderlaySupport} instead, which resolves them.
   */
  public static SnapUnderlay create(
      DatagramChannel channel,
      InetSocketAddress dataPlaneAddress,
      byte[] peerStaticKey,
      SnapControlClient snapControlClient) {
    SnapTunnelSession session =
        new SnapTunnelSession(channel, dataPlaneAddress, peerStaticKey, snapControlClient);
    return new SnapUnderlay(session);
  }

  /** The real, OS-backed channel carrying encrypted SNAP traffic; for selector registration. */
  public DatagramChannel transportChannel() {
    return session.transportChannel();
  }

  public int send(ByteBuffer buffer) throws IOException {
    byte[] scionPacket = new byte[buffer.remaining()];
    buffer.get(scionPacket);
    return session.sendPacket(scionPacket);
  }

  public InetSocketAddress receive(ByteBuffer buffer) throws IOException {
    return session.receivePacket(buffer);
  }

  /** Ensures the handshake has completed and returns the SNAP-assigned source address. */
  public InetSocketAddress ensureConnectedSourceAddress() throws IOException {
    session.ensureConnected();
    return session.localTunnelAddress();
  }

  public void close() {
    session.close();
  }
}
