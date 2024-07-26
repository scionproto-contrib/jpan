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
import org.scion.jpan.internal.AbstractSegmentsMinimalTest;

/** Mock network for larger topologies than tiny. A local daemon is _not_ supported. */
public class MockNetwork2 implements AutoCloseable {
  public static final String AS_HOST =
      "my-as-host-test.org"; // TODO remove from AbstractSegmentsMinimalTest
  private final MockBootstrapServer topoServer;
  private final MockControlServer controlServer;

  static class MinimalInitializer extends AbstractSegmentsMinimalTest {
    public void addResponses() {
      super.addResponses();
    }

    void init(MockControlServer controlServer) {
      AbstractSegmentsMinimalTest.controlServer = controlServer;
    }
  }

  public static MockNetwork2 start(String configDir, String topoFileOfLocalAS) {
    return new MockNetwork2(configDir, topoFileOfLocalAS);
  }

  private MockNetwork2(String configDir, String topoFileOfLocalAS) {
    topoServer = MockBootstrapServer.start(configDir, topoFileOfLocalAS);
    InetSocketAddress topoAddr = topoServer.getAddress();
    DNSUtil.installNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
    controlServer = MockControlServer.start(topoServer.getControlServerPort());
    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, configDir + topoFileOfLocalAS);
    if (configDir.startsWith("topologies/minimal")) {
      MinimalInitializer data = new MinimalInitializer();
      data.init(controlServer);
      data.addResponses();
    } else {
      throw new UnsupportedOperationException();
    }
  }

  public void reset() {
    controlServer.clearSegments();
    topoServer.getAndResetCallCount();
    controlServer.getAndResetCallCount();
  }

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
