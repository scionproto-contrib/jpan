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

package org.scion;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

public class PathServiceTest {

  @Test
  public void getScionAddress() throws IOException {
    ScionPathService pathService = ScionPathService.create();
    // InetAddress addr = Inet4Address.getByName("localhost");
    InetAddress addr = Inet4Address.getByName("ethz.ch");
    ScionAddress sAddr = pathService.getScionAddress(addr);
    //assertEquals(sAddr, channel.getLocalAddress());
  }

}
