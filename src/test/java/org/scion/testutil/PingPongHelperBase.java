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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.nio.channels.ClosedByInterruptException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.scion.RequestPath;
import org.scion.Scion;

public class PingPongHelperBase {

  public static final String MSG = "Hello scion!";
  private static final int TIMEOUT = 10; // seconds
  protected final CountDownLatch shutDownBarrier;

  private final int nClients;
  protected final int nServers;
  private final int nRounds;
  protected final boolean connectClients;

  protected final AtomicInteger nRoundsClient = new AtomicInteger();
  protected final AtomicInteger nRoundsServer = new AtomicInteger();
  protected final ConcurrentLinkedQueue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

  protected PingPongHelperBase(int nServers, int nClients, int nRounds, boolean connect) {
    this.nClients = nClients;
    this.nServers = nServers;
    this.nRounds = nRounds;
    this.connectClients = connect;
    shutDownBarrier = new CountDownLatch(nClients + nServers);
    MockNetwork.getAndResetForwardCount();
  }

  protected abstract static class AbstractEndpoint implements Runnable {
    protected final int id;

    AbstractEndpoint(int id) {
      this.id = id;
    }
  }

  public interface ClientFactory {
    Thread create(int id, RequestPath requestPath, int nRounds);
  }

  public interface ServerFactory {
    Thread create(int id, InetSocketAddress serverAddress, int nRounds);
  }

  public void runPingPong(ServerFactory serverFactory, ClientFactory clientFactory, boolean reset) {
    try {
      MockNetwork.startTiny();

      InetSocketAddress serverAddress = MockNetwork.getTinyServerAddress();
      RequestPath scionAddress = Scion.defaultService().getPaths(serverAddress).get(0);
      Thread[] servers = new Thread[nServers];
      for (int i = 0; i < servers.length; i++) {
        servers[i] = serverFactory.create(i, serverAddress, nRounds * nClients);
        servers[i].setName("Server-thread-" + i);
        servers[i].start();
      }

      Thread[] clients = new Thread[nClients];
      for (int i = 0; i < clients.length; i++) {
        clients[i] = clientFactory.create(i, scionAddress, nRounds);
        clients[i].setName("Client-thread-" + i);
        clients[i].start();
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
    } finally {
      MockNetwork.stopTiny();
      checkExceptions();
    }

    if (reset) {
      assertEquals(nRounds * nClients * 2, MockNetwork.getAndResetForwardCount());
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
}
