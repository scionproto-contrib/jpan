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

package org.scion.jpan.demo;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.scion.jpan.Constants;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.MockNetwork2;

/** This test runs several applications against a local scionproto "default" topology. */
public class IntegrationTestLocal {

  private static final boolean PRINT = true;
  private static int nTests = 0;
  private static int nTestsSuccess = 0;

  private interface Test {
    void call() throws Exception;
  }

  public static void main(String[] args) {

    test("ScmpDemoDefault", IntegrationTestLocal::testScmpDemoDefault);

    test("ShowpathsDemo", IntegrationTestLocal::testShowpathsDemo);

    test("ScmpEchoDemo", IntegrationTestLocal::testScmpEchoDemo);

    test("ScmpTracerouteDemo", IntegrationTestLocal::testScmpTracerouteDemo);

    test("PingPongChannelDemo", IntegrationTestLocal::testPingPongChannelDemo);

    test("PingPongSocketDemo", IntegrationTestLocal::testPingPongSocketDemo);

    System.out.println();
    System.out.println("-------------------------------------------------");
    System.out.println("Test succeeded: " + nTestsSuccess + " / " + nTests);
    System.out.println("-------------------------------------------------");
  }

  private static void test(String name, Test test) {
    System.out.println("-------------------- Testing: " + name + " --------------------");
    nTests++;
    try {
      test.call();
    } catch (Exception e) {
      System.err.println("ERROR: " + e.getMessage());
      e.printStackTrace();
      return;
    }
    nTestsSuccess++;
    System.out.println("-------------------- Done: " + name + " -------------------- ");
  }

  private static void testShowpathsDemo() throws IOException {
    ShowpathsDemo.init(PRINT, ShowpathsDemo.Network.SCION_PROTO);
    ShowpathsDemo.run();
  }

  private static void testScmpDemoDefault() throws IOException {
    ScmpDemoDefault.init(PRINT, 1);
    ScmpDemoDefault.main();
  }

  private static void testScmpEchoDemo() throws IOException {
    ScmpEchoDemo.init(PRINT, ScmpEchoDemo.Network.SCION_PROTO, 1);
    ScmpEchoDemo.main();
  }

  private static void testScmpTracerouteDemo() throws IOException {
    ScmpTracerouteDemo.init(PRINT, ScmpTracerouteDemo.Network.SCION_PROTO);
    ScmpTracerouteDemo.main();
  }

  private static void testPingPongChannelDemo() throws IOException {
    ExecutorService ex;
    ex = Executors.newSingleThreadExecutor();
    try {
      ex.execute(
          () -> {
            try {
              PingPongChannelServer.service();
            } catch (IOException e) {
              e.printStackTrace();
              throw new RuntimeException(e);
            }
          });
      String topofile = MockNetwork2.Topology.DEFAULT.configDir() + "ASff00_0_112/topology.json";
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, topofile);
      MockDNS.install("1-ff00:0:112", PingPongChannelServer.SERVER_ADDRESS.getAddress());
      PingPongChannelClient.run();
    } finally {
      ex.shutdownNow();
    }
  }

  private static void testPingPongSocketDemo() throws IOException {
    ExecutorService ex;
    ex = Executors.newSingleThreadExecutor();
    try {
      ex.execute(
          () -> {
            try {
              PingPongSocketServer.service();
            } catch (IOException e) {
              e.printStackTrace();
              throw new RuntimeException(e);
            }
          });
      String topofile = MockNetwork2.Topology.DEFAULT.configDir() + "ASff00_0_112/topology.json";
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, topofile);
      MockDNS.install("1-ff00:0:112", PingPongChannelServer.SERVER_ADDRESS.getAddress());
      PingPongSocketClient.run();
    } finally {
      ex.shutdownNow();
    }
  }
}
