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

import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import org.scion.jpan.internal.snap.SnapControlClient;
import org.scion.jpan.internal.snap.SnapControlEndpointResolver;
import org.scion.jpan.internal.snap.SnapService;
import org.scion.jpan.internal.snap.SnapUnderlay;

/**
 * Bridges a {@link ScionService}'s SNAP configuration to a {@link SnapUnderlay}. This lives in
 * {@code org.scion.jpan} (rather than alongside {@link SnapUnderlay} in {@code internal.snap})
 * because it needs {@link ScionService}'s package-private SNAP accessors ({@code
 * preferSnapUnderlay()}, {@code getSnapDataPlane()}, {@code getLocalAS()}), which are not visible
 * from a sub-package.
 */
final class SnapUnderlaySupport {

  private SnapUnderlaySupport() {}

  /**
   * @return {@code null} if {@code service} does not have SNAP mode enabled -- callers should
   *     treat that as "use a plain UDP underlay instead."
   */
  static SnapUnderlay createFor(ScionService service, DatagramChannel channel) {
    if (service == null || !service.preferSnapUnderlay()) {
      return null;
    }
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

    return SnapUnderlay.create(
        channel, dp.getAddress(), Arrays.copyOf(dp.getSnapStaticX25519(), 32), snapControlClient);
  }
}
