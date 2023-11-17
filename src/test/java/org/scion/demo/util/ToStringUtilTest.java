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

package org.scion.demo.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToStringUtilTest {

  @Test
  void testIPv4_fromBytes() {
    byte[] bytes1 = new byte[] {127, 0, 15, 23};
    String ip1 = ToStringUtil.toStringIPv4(bytes1);
    assertEquals("127.0.15.23", ip1);
  }

  @Test
  void testIPv6_fromBytes() {
    // For now without :: substitution
    byte[] bytes1 = new byte[] {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 2};
    String ip1 = ToStringUtil.toStringIPv6(bytes1);
    assertEquals("100:0:100:0:100:0:100:2", ip1);

    //    byte[] bytes1 = new byte[]{1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,2};
    //    String ip1 = ScionUtil.toStringIPv6(bytes1);
    //    assertEquals("100::100:0:100:0:100:2", ip1);
    //
    //    byte[] bytes2 = new byte[]{1,0,0,0,0,0,0,0,0,0,0,0,1,0,0,2};
    //    String ip2 = ScionUtil.toStringIPv6(bytes2);
    //    assertEquals("100::100:2", ip2);
    //
    //    byte[] bytes3 = new byte[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1};
    //    String ip3 = ScionUtil.toStringIPv6(bytes3);
    //    assertEquals("::1", ip3);
    //
    //    byte[] bytes4 = new byte[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
    //    String ip4 = ScionUtil.toStringIPv6(bytes4);
    //    assertEquals("::", ip4);
  }
}
