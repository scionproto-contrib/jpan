// Copyright 2024 ETH Zurich
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

package org.scion.jpan.socket;

import java.net.*;
import org.scion.jpan.*;

/**
 * A DatagramSocket that is SCION path aware. It can send and receive SCION packets.
 *
 * <p>Note: use of this class is discouraged in favor of org.scion.{@link ScionDatagramChannel}. The
 * reason is that this class' API (InetAddress and DatagramPacket) cannot be extended to support
 * SCION paths. As a consequence, a server needs to cache paths internally which requires memory and
 * may cause exceptions if more connections (=paths) are managed than the configured thresholds
 * allows.
 *
 * @deprecated Please use ScionDatagramChannel instead
 */
@Deprecated // Please use ScionDatagramChannel instead
public class DatagramSocket extends ScionDatagramSocket {

  @Deprecated // Please use ScionDatagramChannel instead
  public DatagramSocket() throws SocketException {
    this(new InetSocketAddress(0), null);
  }

  @Deprecated // Please use ScionDatagramChannel instead
  public DatagramSocket(int port) throws SocketException {
    this(port, null);
  }

  @Deprecated // Please use ScionDatagramChannel instead
  public DatagramSocket(int port, InetAddress bindAddress) throws SocketException {
    this(new InetSocketAddress(bindAddress, port));
  }

  @Deprecated // Please use ScionDatagramChannel instead
  public DatagramSocket(SocketAddress bindAddress) throws SocketException {
    this(bindAddress, null);
  }

  // "private" to avoid ambiguity with DatagramSocket((SocketAddress) null) -> use create()
  private DatagramSocket(ScionService service) throws SocketException {
    super(service, null);
  }

  // "private" for consistency, all non-standard constructors are private -> use create()
  private DatagramSocket(SocketAddress bindAddress, ScionService service) throws SocketException {
    super(bindAddress, service);
  }

  @Deprecated // Please use ScionDatagramChannel instead
  public static DatagramSocket create(ScionService service) throws SocketException {
    return new DatagramSocket(service);
  }

  @Deprecated // Please use ScionDatagramChannel instead
  public static DatagramSocket create(SocketAddress bindAddress, ScionService service)
      throws SocketException {
    return new DatagramSocket(bindAddress, service);
  }
}
