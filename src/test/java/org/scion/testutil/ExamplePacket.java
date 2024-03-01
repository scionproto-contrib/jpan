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

package org.scion.testutil;

import java.net.InetSocketAddress;
import org.scion.PackageVisibilityHelper;
import org.scion.RequestPath;
import org.scion.ScionUtil;

public class ExamplePacket {

  public static final String MSG = "Hello scion";

  /**
   * Packet bytes for a message sent in the "tiny"network config in scionproto.
   *
   * <p>Recording from end2end example (w/o tracing etc): Server packet received (ping).
   */
  public static final byte[] PACKET_BYTES_SERVER_E2E_PING = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 1,
    1, 0, 32, 0, 1, 0, 125, -5, 101, 83, 118, -81, 0, 63, 0, 0,
    0, 2, 118, -21, 86, -46, 89, 0, 0, 63, 0, 1, 0, 0, -8, 2,
    -114, 25, 76, -122, -83, -100, 31, -112, 0, 19, 68, -82, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };
}
