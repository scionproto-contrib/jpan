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

  public enum Topology {
    DEFAULT("topologies/default/"),
    MINIMAL("topologies/minimal/"),
    TINY4("topologies/tiny4/"),
    TINY4B("topologies/tiny4b/");

    private final String configDir;

    Topology(String configDir) {
      this.configDir = configDir;
    }
  }

  static class MinimalInitializer extends AbstractSegmentsTest {
    MinimalInitializer(MockControlServer controlServer) {
      super(CFG_MINIMAL, controlServer);
      super.addResponsesScionprotoMinimal();
    }
  }

  static class DefaultInitializer extends AbstractSegmentsTest {
    DefaultInitializer(MockControlServer controlServer) {
      super(CFG_DEFAULT, controlServer);
      super.addResponsesScionprotoDefault();
    }
  }

  static class Tiny4Initializer extends AbstractSegmentsTest {
    Tiny4Initializer(MockControlServer controlServer) {
      super(CFG_TINY4, controlServer);
      super.addResponsesScionprotoTiny4();
    }
  }

  static class Tiny4bInitializer extends AbstractSegmentsTest {
    Tiny4bInitializer(MockControlServer controlServer) {
      super(CFG_TINY4B, controlServer);
      super.addResponsesScionprotoTiny4b();
    }
  }

  public static MockNetwork2 start(Topology topo, String topoOfLocalAS) {
    return new MockNetwork2(topo, topoOfLocalAS);
  }

  private MockNetwork2(Topology topo, String topoOfLocalAS) {
    topoServer = MockBootstrapServer.start(topo.configDir, topoOfLocalAS);
    InetSocketAddress topoAddr = topoServer.getAddress();
    DNSUtil.installNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
    controlServer = MockControlServer.start(topoServer.getControlServerPort());
    String topoFileOfLocalAS = topo.configDir + topoOfLocalAS + "/topology.json";
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, topoFileOfLocalAS);
    switch (topo) {
      case DEFAULT:
        new DefaultInitializer(controlServer);
        break;
      case MINIMAL:
        new MinimalInitializer(controlServer);
        break;
      case TINY4:
        new Tiny4Initializer(controlServer);
        break;
      case TINY4B:
        new Tiny4bInitializer(controlServer);
        break;
      default:
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
