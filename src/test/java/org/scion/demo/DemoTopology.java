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

package org.scion.demo;

import java.net.InetSocketAddress;
import org.scion.Constants;
import org.scion.testutil.MockDaemon;
import org.scion.testutil.MockNetwork;

/** Helper class for setting up a demo topology. */
class DemoTopology {

  InetSocketAddress clientDaemonAddress;

  static DemoTopology configureTiny110_112() {
    DemoTopology cfg = new DemoTopology();
    cfg.clientDaemonAddress = new InetSocketAddress("127.0.0.12", 30255);
    //   cfg.serverDaemonAddress = new InetSocketAddress("fd00:f00d:cafe::7", 30255);
    configurePathService("127.0.0.12", 30255);
    return cfg;
  }

  static DemoTopology configureTiny111_112() {
    DemoTopology cfg = new DemoTopology();
    cfg.clientDaemonAddress = new InetSocketAddress("127.0.0.19", 30255);
    //   cfg.serverDaemonAddress = new InetSocketAddress("fd00:f00d:cafe::7", 30255);
    configurePathService("127.0.0.19", 30255);
    return cfg;
  }

  static DemoTopology configureMock(boolean remoteIPv4) {
    DemoTopology cfg = new DemoTopology();
    MockNetwork.startTiny(true, remoteIPv4);
    cfg.clientDaemonAddress = MockDaemon.DEFAULT_ADDRESS;
    //    cfg.serverDaemonAddress = new InetSocketAddress("fd00:f00d:cafe::7", 30255);
    return cfg;
  }

  static DemoTopology configureMock() {
    return configureMock(false);
  }

  static void shutDown() {
    MockNetwork.stopTiny();
  }

  private static void configurePathService(String address, int port) {
    System.setProperty(Constants.PROPERTY_DAEMON, address + ":" + port);
  }
}
