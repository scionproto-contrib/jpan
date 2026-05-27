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

import java.net.InetSocketAddress;

/** SNAP data plane information returned by the SNAP control API. */
public class SnapService {
  private final InetSocketAddress address;
  private final String snapTunControlAddress;
  private final byte[] snapStaticX25519;

  SnapService(InetSocketAddress address, String snapTunControlAddress, byte[] snapStaticX25519) {
    this.address = address;
    this.snapTunControlAddress = snapTunControlAddress;
    this.snapStaticX25519 = snapStaticX25519;
  }

  public InetSocketAddress getAddress() {
    return address;
  }

  public String getSnapTunControlAddress() {
    return snapTunControlAddress;
  }

  public byte[] getSnapStaticX25519() {
    return snapStaticX25519;
  }
}
