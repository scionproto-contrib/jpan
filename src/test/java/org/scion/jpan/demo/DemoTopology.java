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

package org.scion.jpan.demo;

import java.net.InetSocketAddress;
import org.scion.jpan.Constants;
import org.scion.jpan.testutil.MockDaemon;
import org.scion.jpan.testutil.MockNetwork;

/** Helper class for setting up a demo topology. */
public class DemoTopology {

  private InetSocketAddress clientDaemonAddress;

  static DemoTopology configureMock(boolean remoteIPv4) {
    DemoTopology cfg = new DemoTopology();
    MockNetwork.startTiny(remoteIPv4);
    cfg.clientDaemonAddress = MockDaemon.getAddress();
    return cfg;
  }

  static DemoTopology configureMockV6() {
    return configureMock(false);
  }

  static DemoTopology configureMockV4() {
    return configureMock(true);
  }

  public static void shutDown() {
    MockNetwork.stopTiny();
  }

  private static void configurePathService(String address, int port) {
    System.setProperty(Constants.PROPERTY_DAEMON, address + ":" + port);
  }
}
