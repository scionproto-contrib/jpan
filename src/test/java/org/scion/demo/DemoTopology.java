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

import org.scion.ScionConstants;
import org.scion.testutil.MockDaemon;
import org.scion.testutil.MockNetwork;

import java.net.InetSocketAddress;

class DemoTopology {

    InetSocketAddress clientDaemonAddress;
    InetSocketAddress clientBorderRouter;


    static DemoTopology configureTiny110_112() {
        DemoTopology cfg = new DemoTopology();
        cfg.clientDaemonAddress = new InetSocketAddress("127.0.0.12", 30255);
        //   cfg.serverDaemonAddress = new InetSocketAddress("fd00:f00d:cafe::7", 30255);
        cfg.clientBorderRouter = new InetSocketAddress("127.0.0.9", 31002);
        configurePathService("127.0.0.12", 30255);
        return cfg;
    }

    static DemoTopology configureTiny111_112() {
        DemoTopology cfg = new DemoTopology();
        cfg.clientDaemonAddress = new InetSocketAddress("127.0.0.19", 30255);
        //   cfg.serverDaemonAddress = new InetSocketAddress("fd00:f00d:cafe::7", 30255);
        cfg.clientBorderRouter = new InetSocketAddress("127.0.0.17", 31008);
        configurePathService("127.0.0.19", 30255);
        return cfg;
    }

    static DemoTopology configureMock() {
        DemoTopology cfg = new DemoTopology();
        MockNetwork.startTiny(true, false);
        cfg.clientDaemonAddress = MockDaemon.DEFAULT_ADDRESS;
        //    cfg.serverDaemonAddress = new InetSocketAddress("fd00:f00d:cafe::7", 30255);
        cfg.clientBorderRouter = new InetSocketAddress(MockNetwork.BORDER_ROUTER_HOST, MockNetwork.BORDER_ROUTER_PORT1);
        return cfg;
    }

    static void shutDown() {
        MockNetwork.stopTiny();
    }

    private static void configurePathService(String address, int port) {
        System.setProperty(ScionConstants.PROPERTY_DAEMON_HOST, address);//.substring(1)); // TODO
        System.setProperty(ScionConstants.PROPERTY_DAEMON_PORT, String.valueOf(port));
    }
}
