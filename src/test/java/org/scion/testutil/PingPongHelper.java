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
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.scion.DatagramChannel;
import org.scion.Path;
import org.scion.RequestPath;
import org.scion.Scion;

public class PingPongHelper {

  public static final String MSG = "Hello scion!";
  private static final int TIMEOUT = 5; // seconds
  private final CountDownLatch BARRIER;

  private final int nClients;
  private final int nServers;
  private final int nRounds;

  private final AtomicInteger nRoundsClient = new AtomicInteger();
  private final AtomicInteger nRoundsServer = new AtomicInteger();
  private final ConcurrentLinkedQueue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

  public PingPongHelper(int nServers, int nClients, int nRounds) {
    this.nClients = nClients;
    this.nServers = nServers;
    this.nRounds = nRounds;
    BARRIER = new CountDownLatch(nClients + nServers);
    MockNetwork.getAndResetForwardCount();
  }

  private abstract class AbstractEndpoint implements Runnable {
    protected final int id;

    AbstractEndpoint(int id) {
      this.id = id;
    }

    abstract void runImpl(DatagramChannel channel) throws IOException;

    @Override
    public final void run() {
      try (DatagramChannel channel = DatagramChannel.open()) {
        channel.configureBlocking(true);
        runImpl(channel);
      } catch (IOException e) {
        System.out.println(
            "ENDPOINT " + Thread.currentThread().getName() + ": I/O error: " + e.getMessage());
        exceptions.add(e);
      } catch (Exception e) {
        exceptions.add(e);
      } finally {
        BARRIER.countDown();
      }
    }
  }

  private class ClientEndpoint extends AbstractEndpoint {
    private final Client client;
    private final RequestPath remoteAddress;
    private final int nRounds;

    ClientEndpoint(Client client, int id, RequestPath remoteAddress, int nRounds) {
      super(id);
      this.client = client;
      this.remoteAddress = remoteAddress;
      this.nRounds = nRounds;
    }

    @Override
    public final void runImpl(DatagramChannel channel) throws IOException {
      InetAddress inetAddress = InetAddress.getByAddress(remoteAddress.getDestinationAddress());
      InetSocketAddress iSAddress =
          new InetSocketAddress(inetAddress, remoteAddress.getDestinationPort());
      channel.connect(iSAddress);
      for (int i = 0; i < nRounds; i++) {
        client.run(channel, remoteAddress, id);
        nRoundsClient.incrementAndGet();
      }
      channel.disconnect();
    }
  }

  private class ServerEndpoint extends AbstractEndpoint {
    private final Server server;
    private final InetSocketAddress localAddress;
    private final int nRounds;

    ServerEndpoint(Server server, int id, InetSocketAddress localAddress, int nRounds) {
      super(id);
      this.server = server;
      this.localAddress = localAddress;
      this.nRounds = nRounds;
    }

    @Override
    public final void runImpl(DatagramChannel channel) throws IOException {
      channel.bind(localAddress);
      for (int i = 0; i < nRounds; i++) {
        server.run(channel);
        nRoundsServer.incrementAndGet();
      }
    }
  }

  public interface Client {
    void run(DatagramChannel channel, RequestPath path, int id) throws IOException;
  }

  public interface Server {
    void run(DatagramChannel channel) throws IOException;
  }

  public void runPingPong(Server serverFn, Client clientFn) {
    runPingPong(serverFn, clientFn, true);
  }

  public void runPingPong(Server serverFn, Client clientFn, boolean reset) {
    try {
      MockNetwork.startTiny();

      InetSocketAddress serverAddress = MockNetwork.getTinyServerAddress();
      RequestPath scionAddress = Scion.defaultService().getPaths(serverAddress).get(0);
      Thread[] servers = new Thread[nServers];
      for (int i = 0; i < servers.length; i++) {
        servers[i] = new Thread(new ServerEndpoint(serverFn, i, serverAddress, nRounds * nClients));
        servers[i].setName("Server-thread-" + i);
        servers[i].start();
      }

      Thread[] clients = new Thread[nClients];
      for (int i = 0; i < clients.length; i++) {
        clients[i] = new Thread(new ClientEndpoint(clientFn, i, scionAddress, nRounds));
        clients[i].setName("Client-thread-" + i);
        clients[i].start();
      }

      // This enables shutdown in case of an error.
      // Wait for all threads to finish.
      if (!BARRIER.await(TIMEOUT, TimeUnit.SECONDS)) {
        for (Thread client : clients) {
          client.interrupt();
        }
        for (Thread server : servers) {
          server.interrupt();
        }
        if (!BARRIER.await(1, TimeUnit.SECONDS)) {
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
    assertEquals(nRounds * nClients, nRoundsServer.get());
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

  public static void defaultClient(DatagramChannel channel, Path serverAddress, int id)
      throws IOException {
    String message = PingPongHelper.MSG + "-" + id;
    ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());
    channel.send(sendBuf, serverAddress);

    // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
    ByteBuffer response = ByteBuffer.allocate(512);
    Path address = channel.receive(response);
    assertNotNull(address);
    assertArrayEquals(serverAddress.getDestinationAddress(), address.getDestinationAddress());
    assertEquals(serverAddress.getDestinationPort(), address.getDestinationPort());

    response.flip();
    String pong = Charset.defaultCharset().decode(response).toString();
    assertEquals(message, pong);
  }

  public static void defaultServer(DatagramChannel channel) throws IOException {
    ByteBuffer request = ByteBuffer.allocate(512);
    Path address = channel.receive(request);

    request.flip();
    String msg = Charset.defaultCharset().decode(request).toString();
    assertTrue(msg.startsWith(MSG), msg);
    assertTrue(MSG.length() + 3 >= msg.length());

    request.flip();
    channel.send(request, address);
  }
}
