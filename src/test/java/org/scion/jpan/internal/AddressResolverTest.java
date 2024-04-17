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

package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.testutil.DNSUtil;
import org.scion.jpan.testutil.MockDaemon;

class AddressResolverTest {

  @Test
  void resolve() throws IOException, URISyntaxException {
    URL resource = getClass().getClassLoader().getResource("etc-scion-hosts");
    java.nio.file.Path file = Paths.get(resource.toURI());
    System.setProperty(Constants.PROPERTY_HOSTS_FILES, file.toString());
    MockDaemon.createAndStartDefault();
    try {
      ScionService service = Scion.defaultService();

      //  # /etc/scion/hosts test file
      //  1-ff00:0:111,[42.0.0.11] test-server
      //  1-ff00:0:112,[42.0.0.12] test-server-1 test-server-2
      //  1-ff00:0:113,[::42] test-server-ipv6

      String hostName4 = "test-server";
      InetAddress addr4 = DNSUtil.install(hostName4, new byte[] {127, 0, 0, 42});
      String hostName6 = "test-server-ipv6";
      byte[] bytesIPv6 = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x42};
      InetAddress addr6 = DNSUtil.install(hostName6, bytesIPv6);

      AddressResolver ar = new AddressResolver(service);

      ScionAddress resolved4 = ar.resolve(addr4);
      assertEquals(ScionUtil.parseIA("1-ff00:0:111"), resolved4.getIsdAs());
      assertArrayEquals(new byte[] {42, 0, 0, 11}, resolved4.getInetAddress().getAddress());

      ScionAddress resolved6 = ar.resolve(addr6);
      assertEquals(ScionUtil.parseIA("1-ff00:0:113"), resolved6.getIsdAs());
      assertArrayEquals(bytesIPv6, resolved6.getInetAddress().getAddress());

      // Invalid host name
      InetAddress fail1 = InetAddress.getByAddress("wrong-host", new byte[] {127, 0, 0, 1});
      assertThrows(ScionException.class, () -> ar.resolve(fail1));
      // Hostname is an IP. This is invalid because currently we cannot do reverse lookup
      InetAddress fail2 =
          InetAddress.getByAddress("192.0.2.1", new byte[] {ByteUtil.toByte(192), 0, 2, 1});
      assertThrows(ScionException.class, () -> ar.resolve(fail2));
    } finally {
      MockDaemon.closeDefault();
      System.clearProperty(Constants.PROPERTY_HOSTS_FILES);
    }
  }
}
