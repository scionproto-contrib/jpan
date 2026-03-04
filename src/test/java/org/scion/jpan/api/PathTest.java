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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.RequestPath;
import org.scion.jpan.internal.util.IPHelper;

class PathTest {

  @Test
  void copy() throws UnknownHostException {
    RequestPath p = PackageVisibilityHelper.createDummyPath();
    InetAddress ip = IPHelper.toInetAddress("127.0.0.25");
    RequestPath p2 = (RequestPath) p.copy(ip, 555);
    assertEquals(p.getRawPath(), p2.getRawPath());
    assertEquals(p.getFirstHopAddress(), p2.getFirstHopAddress());
    assertEquals(p.getRemoteIsdAs(), p2.getRemoteIsdAs());
    assertNotEquals(p.getRemoteSocketAddress(), p2.getRemoteSocketAddress());
    assertEquals(ip, p2.getRemoteAddress());
    assertEquals(555, p2.getRemotePort());
  }
}
