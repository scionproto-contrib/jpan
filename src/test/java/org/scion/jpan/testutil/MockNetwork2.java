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
import java.util.function.Consumer;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionService;

/** Mock network for larger topologies than tiny. A local daemon is _not_ supported. */
public class MockNetwork2 implements AutoCloseable {
  public static final String AS_HOST = "my-as-host-test.org";
  private final MockBootstrapServer topoServer;
  private final MockControlServer controlServer;

  public enum Topology {
    DEFAULT("topologies/default/", ScenarioInitializer::addResponsesScionprotoDefault),
    MINIMAL("topologies/minimal/", ScenarioInitializer::addResponsesScionprotoMinimal),
    TINY4("topologies/tiny4/", ScenarioInitializer::addResponsesScionprotoTiny4),
    TINY4B("topologies/tiny4b/", ScenarioInitializer::addResponsesScionprotoTiny4b);

    private final String configDir;
    private final Consumer<ScenarioInitializer> initializer;

    Topology(String configDir, Consumer<ScenarioInitializer> initializer) {
      this.configDir = configDir;
      this.initializer = initializer;
    }

    public String configDir() {
      return configDir;
    }

    public Consumer<ScenarioInitializer> initializer() {
      return initializer;
    }
  }

  public static MockNetwork2 start(Topology topo, String topoOfLocalAS) {
    return new MockNetwork2(topo, topoOfLocalAS);
  }

  private MockNetwork2(Topology topo, String topoOfLocalAS) {
    topoServer = MockBootstrapServer.start(topo.configDir, topoOfLocalAS);
    InetSocketAddress topoAddr = topoServer.getAddress();
    DNSUtil.bootstrapNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
    controlServer = MockControlServer.start(topoServer.getControlServerPort());
    String topoFileOfLocalAS = topo.configDir + topoOfLocalAS + "/topology.json";
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, topoFileOfLocalAS);

    // Initialize segments
    ScenarioInitializer.init(topo, controlServer);
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
