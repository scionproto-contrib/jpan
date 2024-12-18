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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.nio.channels.ClosedByInterruptException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionService;

/**
 * This uses a "tiny" network topology for ping-pong experiments. By default, the client is in 110
 * and the server in 112.
 */
public class PingPongHelperBase {

  public static final String MSG = "Hello scion!";
  public static final String SERVER_ISD_AS = MockNetwork.TINY_SRV_ISD_AS;
  public static final String SERVER_TOPO = MockNetwork.TINY_SRV_TOPO_V4 + "/topology.json";

  private static final int TIMEOUT = 10; // seconds
  private static final String SERVER_NAME = "ping.pong.org";
  protected final CountDownLatch shutDownBarrier;

  private final int nClients;
  protected final int nServers;
  private final int nRounds;
  protected final boolean connectClients;
  private final boolean checkCounters;
  private final String serverIsdAs;
  protected final InetSocketAddress serverAddressOrNull;
  protected final ScionService serverService;

  final CountDownLatch startUpBarrierClient;
  final CountDownLatch startUpBarrierServer;
  protected final AtomicInteger nRoundsClient = new AtomicInteger();
  protected final AtomicInteger nRoundsServer = new AtomicInteger();
  protected final ConcurrentLinkedQueue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

  protected PingPongHelperBase(
      int nServers,
      int nClients,
      int nRounds,
      boolean connect,
      boolean checkCounters,
      String serverIsdAs,
      InetSocketAddress serverAddressOrNull,
      ScionService serverService) {
    this.nClients = nClients;
    this.nServers = nServers;
    this.nRounds = nRounds;
    this.connectClients = connect;
    this.checkCounters = checkCounters;
    this.serverIsdAs = serverIsdAs;
    this.serverAddressOrNull = serverAddressOrNull;
    this.serverService = serverService;

    startUpBarrierClient = new CountDownLatch(nClients);
    startUpBarrierServer = new CountDownLatch(nServers);
    shutDownBarrier = new CountDownLatch(nClients + nServers);
    MockNetwork.getAndResetForwardCount();
  }

  abstract class AbstractEndpoint extends Thread {
    protected final int id;
    private InetSocketAddress localAddress;

    AbstractEndpoint(int id) {
      this.id = id;
    }

    protected final void registerStartUpClient() {
      PingPongHelperBase.this.startUpBarrierClient.countDown();
    }

    protected final void registerStartUpServer(InetSocketAddress localAddress) {
      try {
        InetAddress localIP = InetAddress.getByAddress(SERVER_NAME, new byte[] {127, 0, 0, 1});
        this.localAddress = new InetSocketAddress(localIP, localAddress.getPort());
      } catch (UnknownHostException e) {
        throw new RuntimeException(e);
      }
      PingPongHelperBase.this.startUpBarrierServer.countDown();
    }

    public InetSocketAddress getLocalAddress() {
      return localAddress;
    }
  }

  interface ClientFactory {
    AbstractEndpoint create(int id, Path requestPath, int nRounds);
  }

  interface ServerFactory {
    AbstractEndpoint create(int id, int nRounds);
  }

  void start() {
    MockNetwork.startTiny();
  }

  void run(ServerFactory serverFactory, ClientFactory clientFactory) {
    try {
      AbstractEndpoint[] servers = new AbstractEndpoint[nServers];
      for (int i = 0; i < servers.length; i++) {
        servers[i] = serverFactory.create(i, nRounds * nClients);
        servers[i].setName("Server-thread-" + i);
        servers[i].start();
      }
      // Wait for server(s) and clients to start
      if (!startUpBarrierServer.await(1, TimeUnit.SECONDS)) {
        throw new RuntimeException("Server startup failed: " + startUpBarrierServer);
      }
      InetSocketAddress serverAddress = servers[0].getLocalAddress();
      MockDNS.install(serverIsdAs, serverAddress.getAddress());
      Path requestPath = Scion.defaultService().lookupAndGetPath(serverAddress, null);

      Thread[] clients = new Thread[nClients];
      for (int i = 0; i < clients.length; i++) {
        clients[i] = clientFactory.create(i, requestPath, nRounds);
        clients[i].setName("Client-thread-" + i);
        clients[i].start();
      }
      // Wait for server(s) and clients to start
      if (!startUpBarrierClient.await(1, TimeUnit.SECONDS)) {
        throw new RuntimeException("Client startup failed: " + startUpBarrierClient);
      }

      // This enables shutdown in case of an error.
      // Wait for all threads to finish.
      if (!shutDownBarrier.await(TIMEOUT, TimeUnit.SECONDS)) {
        for (Thread client : clients) {
          client.interrupt();
        }
        for (Thread server : servers) {
          server.interrupt();
        }
        if (!shutDownBarrier.await(1, TimeUnit.SECONDS)) {
          checkExceptions();
          fail();
        }
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      exceptions.add(e);
      throw new RuntimeException(e);
    }
  }

  void close() {
    if (serverService != null) {
      try {
        serverService.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    ScionService.closeDefault();
    MockNetwork.stopTiny();

    checkExceptions();

    if (checkCounters) {
      assertEquals(nRounds * nClients * 2, MockNetwork.getForwardCount());
    }
    assertEquals(nRounds * nClients, nRoundsClient.get());
    // For now, we assume that every request is handles by every server thread.
    // we may have to make this configurable for future tests that work differently.
    assertEquals(nServers * nRounds * nClients, nRoundsServer.get());
  }

  private void checkExceptions() {
    for (Iterator<Throwable> it = exceptions.iterator(); it.hasNext(); ) {
      Throwable t = it.next();
      if (t instanceof ClosedByInterruptException) {
        it.remove();
      }
      t.printStackTrace();
    }
    assertEquals(0, exceptions.size());
    exceptions.clear();
  }

  protected abstract static class Builder<T extends PingPongHelperBase.Builder<T>> {
    protected final int nClients;
    protected final int nServers;
    protected final int nRounds;
    protected boolean connectClients = true;
    protected boolean checkCounters = true;
    protected String serverIsdAs = SERVER_ISD_AS;
    protected ScionService serverService = null;
    private boolean serverServiceIsSet = false;

    protected Builder(int nServers, int nClients, int nRounds, boolean connect) {
      this.nClients = nClients;
      this.nServers = nServers;
      this.nRounds = nRounds;
      this.connectClients = connect;
    }

    @SuppressWarnings("unchecked")
    public T serverIsdAs(String serverIsdAs) {
      this.serverIsdAs = serverIsdAs;
      return (T) this;
    }

    @SuppressWarnings("unchecked")
    public T connect(boolean connectClients) {
      this.connectClients = connectClients;
      return (T) this;
    }

    @SuppressWarnings("unchecked")
    public T checkCounters(boolean checkCounters) {
      this.checkCounters = checkCounters;
      return (T) this;
    }

    @SuppressWarnings("unchecked")
    public T serverService(ScionService service) {
      this.serverService = service;
      this.serverServiceIsSet = true;
      return (T) this;
    }

    protected ScionService service() {
      return serverServiceIsSet ? serverService : Scion.newServiceWithTopologyFile(SERVER_TOPO);
    }
  }
}
