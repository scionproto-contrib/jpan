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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionService;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.proto.control_plane.Seg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mock network for larger topologies than tiny. A local daemon is _not_ supported. */
public class MockNetwork2 implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(MockNetwork2.class.getName());
  public static final String AS_HOST = "my-as-host-test.org";
  private final Barrier barrier = new Barrier();
  private final List<AsInfo> asInfos = new ArrayList<>();
  private final Topology topo;

  // Control service
  private final MockBootstrapServer topoServer;
  private final List<MockControlServer> controlServices = new ArrayList<>();

  // Path service
  private final List<MockPathService> pathServices = new ArrayList<>();

  // Border routers
  private final ExecutorService routers;
  private final List<InetSocketAddress> brAddresses = new ArrayList<>();

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
    return new MockNetwork2(topo, new String[] {topoOfLocalAS}, false);
  }

  public static MockNetwork2 startPS(Topology topo, String... topoOfLocalAS) {
    // This is currently a hack. We cn provide multiple AS numbers, when instead we also want to
    // be able to provide multiple ISD numbers.
    return new MockNetwork2(topo, topoOfLocalAS, true);
  }

  private MockNetwork2(Topology topo, String[] toposOfLocalAS, boolean usePathService) {
    this.topo = topo;
    routers = Executors.newFixedThreadPool(2);
    if (usePathService) {
      topoServer = null;
      for (String topoOfLocalAS : toposOfLocalAS) {
        Path path = Paths.get(topo.configDir + topoOfLocalAS);
        asInfos.add(JsonFileParser.parseTopology(path));
      }
      pathServices.add(MockPathService.start(MockPathService.DEFAULT_PORT_0, asInfos));
      pathServices.add(MockPathService.start(MockPathService.DEFAULT_PORT_1, asInfos));
      String ps0 = "[::1]:" + MockPathService.DEFAULT_PORT_0;
      String ps1 = "127.0.0.1:" + MockPathService.DEFAULT_PORT_1;
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_PATH_SERVICE, ps0 + ";" + ps1);
    } else {
      topoServer = MockBootstrapServer.start(topo.configDir, toposOfLocalAS[0]);
      InetSocketAddress topoAddr = topoServer.getAddress();
      DNSUtil.bootstrapNAPTR(AS_HOST, topoAddr.getAddress().getAddress(), topoAddr.getPort());
      for (InetSocketAddress csAddress : topoServer.getControlServerAddresses()) {
        controlServices.add(MockControlServer.start(csAddress.getPort()));
      }
      String topoFileOfLocalAS = topo.configDir + toposOfLocalAS[0] + "/topology.json";
      System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, topoFileOfLocalAS);
      asInfos.add(topoServer.getASInfo());
    }

    // Initialize segments
    ScenarioInitializer.init(topo, this);
  }

  public void startBorderRouters(String nameOfRemoteAs) {
    Path path = Paths.get(topo.configDir + nameOfRemoteAs);
    AsInfo remoteAsInfo = JsonFileParser.parseTopology(path);

    String remote = remoteAsInfo.getBorderRouters().get(0).getInternalAddress();
    int remoteId = remoteAsInfo.getBorderRouters().get(0).getInterfaces().get(0).id;
    for (AsInfo asInfo : asInfos) {
      // asInfo.connectWith(remoteAsInfo, remote);
      startBorderRouters(asInfo, remote, remoteId);
    }
  }

  private void startBorderRouters(AsInfo asInfoLocal, String remote, int remoteId) {
    List<MockBorderRouter> brList = new ArrayList<>();
    for (AsInfo.BorderRouter br : asInfoLocal.getBorderRouters()) {
      for (AsInfo.BorderRouterInterface brIf : br.getInterfaces()) {
        System.err.println("BR: " + br.getInternalAddress() + " " + brIf.isdAs);
        // TODO what is this?
        //        if (brIf.getRemoteInterface() == null) {
        //          // can happen for e.g. AS 111
        //          continue;
        //        }
        // String remote = brIf.getRemoteInterface().getBorderRouter().getInternalAddress();
        InetSocketAddress bind1 = IPHelper.toInetSocketAddress(br.getInternalAddress());
        InetSocketAddress bind2 = IPHelper.toInetSocketAddress(remote);
        //        brList.add(
        //            new MockBorderRouter(
        //                brList.size(), bind1, bind2, brIf.id, brIf.getRemoteInterface().id,
        // barrier));
        brList.add(new MockBorderRouter(brList.size(), bind1, bind2, brIf.id, remoteId, barrier));
        brAddresses.add(bind1);
      }
    }

    barrier.reset(brList.size());
    for (MockBorderRouter br : brList) {
      routers.execute(br);
    }
    if (!barrier.await(1, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Failed to start border routers.");
    }
  }

  public void reset() {
    controlServices.forEach(MockControlServer::clearSegments);
    controlServices.forEach(MockControlServer::getAndResetCallCount);
    pathServices.forEach(MockPathService::clearSegments);
    pathServices.forEach(MockPathService::getAndResetCallCount);
    if (topoServer != null) {
      topoServer.getAndResetCallCount();
    }
    barrier.reset(0);
  }

  @Override
  public void close() {
    controlServices.forEach(MockControlServer::close);
    controlServices.clear();
    pathServices.forEach(MockPathService::close);
    pathServices.clear();
    if (topoServer != null) {
      topoServer.close();
    }
    barrier.reset(0);
    DNSUtil.clear();
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_PATH_SERVICE);
    // Defensive clean up
    ScionService.closeDefault();
    brAddresses.clear();

    try {
      routers.shutdownNow();
      // Wait a while for tasks to respond to being canceled
      if (!routers.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.error("Router did not terminate");
      }
      logger.info("Router shut down");
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  public MockBootstrapServer getTopoServer() {
    return topoServer;
  }

  public MockControlServer getControlServer() {
    return controlServices.get(0);
  }

  public List<MockControlServer> getControlServers() {
    return Collections.unmodifiableList(controlServices);
  }

  public List<MockPathService> getPathServices() {
    return pathServices;
  }

  public MockPathService getPathService() {
    return pathServices.get(0);
  }

  public void addResponse(
      long srcIA, boolean srcIsCore, long dstIA, boolean dstIsCore, Seg.SegmentsResponse response) {
    controlServices.forEach(c -> c.addResponse(srcIA, srcIsCore, dstIA, dstIsCore, response));
    pathServices.forEach(p -> p.addResponse(srcIA, srcIsCore, dstIA, dstIsCore, response));
  }
}
