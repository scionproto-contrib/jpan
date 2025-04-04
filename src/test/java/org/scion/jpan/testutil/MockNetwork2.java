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

package org.scion.jpan.testutil;

import java.net.InetSocketAddress;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionService;
import org.scion.jpan.internal.AbstractSegmentsTest;

/** Mock network for larger topologies than tiny. A local daemon is _not_ supported. */
public class MockNetwork2 implements AutoCloseable {
  public static final String AS_HOST = "my-as-host-test.org";
  private final MockBootstrapServer topoServer;
  private final MockControlServer controlServer;

  static class MinimalInitializer extends AbstractSegmentsTest {
    MinimalInitializer(MockControlServer controlServer) {
      super(CFG_MINIMAL);
      super.controlServer = controlServer;
      super.addResponsesScionprotoMinimal();
    }
  }

  static class DefaultInitializer extends AbstractSegmentsTest {
    DefaultInitializer(MockControlServer controlServer) {
      super(CFG_DEFAULT);
      super.controlServer = controlServer;
      super.addResponsesScionprotoDefault();
    }
  }

  static class Tiny4Initializer extends AbstractSegmentsTest {
    Tiny4Initializer(MockControlServer controlServer) {
      super(CFG_TINY4);
      super.controlServer = controlServer;
      super.addResponsesScionprotoTiny4();
    }
  }

  static class Tiny4bInitializer extends AbstractSegmentsTest {
    Tiny4bInitializer(MockControlServer controlServer) {
      super(CFG_TINY4B);
      super.controlServer = controlServer;
      super.addResponsesScionprotoTiny4b();
    }
  }

  public static MockNetwork2 start(String configDir, String topoOfLocalAS) {
    return new MockNetwork2(configDir, topoOfLocalAS);
  }

  private MockNetwork2(String configDir, String topoOfLocalAS) {
    topoServer = MockBootstrapServer.start(configDir, topoOfLocalAS);
    InetSocketAddress topoAddr = topoServer.getAddress();
    DNSUtil.installNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
    controlServer = MockControlServer.start(topoServer.getControlServerPort());
    String topoFileOfLocalAS = configDir + topoOfLocalAS + "/topology.json";
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, topoFileOfLocalAS);
    if (configDir.startsWith("topologies/minimal")) {
      MinimalInitializer data = new MinimalInitializer(controlServer);
    } else if (configDir.startsWith("topologies/default")) {
      DefaultInitializer data = new DefaultInitializer(controlServer);
    } else if (configDir.startsWith("topologies/tiny4b")) {
      Tiny4bInitializer data = new Tiny4bInitializer(controlServer);
    } else if (configDir.startsWith("topologies/tiny4")) {
      Tiny4Initializer data = new Tiny4Initializer(controlServer);
    } else {
      throw new UnsupportedOperationException();
    }
  }

  public void reset() {
    controlServer.clearSegments();
    topoServer.getAndResetCallCount();
    controlServer.getAndResetCallCount();
  }

  @Override
  public void close() {
    controlServer.close();
    topoServer.close();
    DNSUtil.clear();
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    // Defensive clean up
    ScionService.closeDefault();
  }

  public MockBootstrapServer getTopoServer() {
    return topoServer;
  }

  public MockControlServer getControlServer() {
    return controlServer;
  }
}
