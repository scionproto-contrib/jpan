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
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionService;
import org.scion.jpan.internal.util.Config;

/**
 * Bridges a {@link ScionService}'s SNAP configuration to a {@link SnapUnderlay}. This lives in
 * {@code org.scion.jpan} (rather than alongside {@link SnapUnderlay} in {@code internal.snap})
 * because it needs {@link ScionService}'s package-private SNAP accessors ({@code
 * preferSnapUnderlay()}, {@code getSnapDataPlane()}, {@code getLocalAS()}), which are not visible
 * from a sub-package.
 */
public final class SnapUnderlaySupport {

  private SnapUnderlaySupport() {}

  /**
   * Opens a channel suitable for {@code service}. When SNAP mode is enabled, this explicitly opens
   * an IPv4 ({@link StandardProtocolFamily#INET}) channel: the SNAP dataplane is IPv4-only, but a
   * platform-default {@link DatagramChannel#open()} can come back IPv6/dual-stack depending on
   * JVM/platform defaults (observed to vary even across runs on the same machine). Since this
   * channel is now also SnapUnderlay's real transport channel (see {@link #createFor}), that
   * mismatch silently breaks the WireGuard handshake -- the handshake-init "send" reports success,
   * but the response from the (address-family-mismatched) dataplane is never received/matched, so
   * it just times out. A non-SNAP channel keeps the platform default, e.g. for genuine IPv6 SCION
   * deployments.
   */
  public static DatagramChannel openChannelFor(ScionService service) throws IOException {
    if (service != null && Config.isUnderlaySnapAllowed()) {
      return DatagramChannel.open(StandardProtocolFamily.INET);
    }
    return DatagramChannel.open();
  }

  /**
   * @return {@code null} if {@code service} does not have SNAP mode enabled -- callers should treat
   *     that as "use a plain UDP underlay instead."
   */
  public static SnapUnderlay createFor(SnapDataplaneDetails dp, DatagramChannel channel) {
    if (!Config.isUnderlaySnapAllowed()) {
      return null;
    }
    if (dp == null || dp.getSnapStaticX25519() == null) {
      throw new ScionRuntimeException(
          "SNAP mode requested but no SNAP dataplane/static key available");
    }

    String snapTunControlEndpoint = dp.getSnapTunControlAddress();
    SnapControlClient snapControlClient =
        (snapTunControlEndpoint == null || snapTunControlEndpoint.isEmpty())
            ? null
            : new SnapControlClient(snapTunControlEndpoint);

    return SnapUnderlay.create(
        channel, dp.getAddress(), Arrays.copyOf(dp.getSnapStaticX25519(), 32), snapControlClient);
  }
}
