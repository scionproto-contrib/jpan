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

package org.scion.testutil;

import org.scion.PackageVisibilityHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MockNetwork {

  private static final Logger logger = LoggerFactory.getLogger(MockNetwork.class.getName());

  private static ExecutorService pool = null;
  private static MockDaemon daemon = null;
  public static final String BORDER_ROUTER_HOST = "127.0.0.1";
  public static final int BORDER_ROUTER_PORT1 = 30555;
  public static final int BORDER_ROUTER_PORT2 = 30556;


  /**
   * Start a network with one daemon and a border router. The border router connects "1-ff00:0:110"
   * (considered local) with "1-ff00:0:112" (remote).
   */
  public static synchronized void startTiny() {
    startTiny(true, true);
  }

  public static synchronized void startTiny(boolean localIPv4, boolean remoteIPv4) {
    if (pool != null) {
      throw new IllegalStateException();
    }
    pool = Executors.newSingleThreadExecutor();

    MockBorderRouter router =
        new MockBorderRouter("BorderRouter-1", BORDER_ROUTER_PORT1, BORDER_ROUTER_PORT2, localIPv4, remoteIPv4);
    pool.execute(router);

    InetSocketAddress brAddr = new InetSocketAddress(BORDER_ROUTER_HOST, router.getPort1());
    try {
      daemon = MockDaemon.createForBorderRouter(brAddr).start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static synchronized void stopTiny() {
    if (daemon != null) {
      try {
        daemon.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      daemon = null;
    }

    try {
      pool.shutdownNow();
      if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Threads did not terminate!");
      }
      pool = null;
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}

class MockBorderRouter implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(MockBorderRouter.class.getName());

  private final String name;
  private final int port1;
  private final int port2;
  private final boolean ipv4_1;
  private final boolean ipv4_2;

  MockBorderRouter(String name, int port1, int port2, boolean ipv4_1, boolean ipv4_2) {
    this.name = name;
    this.port1 = port1;
    this.port2 = port2;
    this.ipv4_1 = ipv4_1;
    this.ipv4_2 = ipv4_2;
  }

  @Override
  public void run() {
    System.out.println("Running " + name + " on ports " + port1 + " -> " + port2);
    InetSocketAddress bind1 = new InetSocketAddress(ipv4_1 ? "localhost" : "::1", port1);
    InetSocketAddress bind2 = new InetSocketAddress(ipv4_2 ? "localhost" : "::1", port2);
    try (DatagramChannel chnLocal = DatagramChannel.open().bind(bind1);
        DatagramChannel chnRemote = DatagramChannel.open().bind(bind2)) {
      chnLocal.configureBlocking(false);
      chnRemote.configureBlocking(false);
      // TODO use selectors, see e.g. https://www.baeldung.com/java-nio-selector
      while (true) {
        ByteBuffer bb = ByteBuffer.allocate(65000);
        SocketAddress a1 = chnLocal.receive(bb);
        if (a1 != null) {
          InetSocketAddress dst = getDstAddress(bb);
          logger.info("Service " + name + " sending to " + dst + "... ");
          bb.flip();
          chnRemote.send(bb, dst);
        }
        SocketAddress a2 = chnRemote.receive(bb);
        if (a2 != null) {
          InetSocketAddress dst = getDstAddress(bb);
          logger.info("Service " + name + " sending to " + dst + "... ");
          bb.flip();
          chnLocal.send(bb, dst);
        }
        Thread.sleep(100); // TODO use selector
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private InetSocketAddress getDstAddress(ByteBuffer bb) {
    return PackageVisibilityHelper.getDstAddress(bb.array());
  }

  public int getPort1() {
    return port1;
  }
}
