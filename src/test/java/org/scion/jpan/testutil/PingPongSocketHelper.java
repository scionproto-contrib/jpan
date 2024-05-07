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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.scion.jpan.RequestPath;
import org.scion.jpan.socket.DatagramSocket;

public class PingPongSocketHelper extends PingPongHelperBase {

  public PingPongSocketHelper(int nServers, int nClients, int nRounds) {
    super(nServers, nClients, nRounds, false);
  }

  private abstract class AbstractSocketEndpoint extends AbstractEndpoint {

    AbstractSocketEndpoint(int id) {
      super(id);
    }

    abstract void runImpl() throws IOException;

    @Override
    public final void run() {
      try {
        runImpl();
      } catch (IOException e) {
        System.out.println(
            "ENDPOINT " + Thread.currentThread().getName() + ": I/O error: " + e.getMessage());
        exceptions.add(e);
      } catch (Exception e) {
        exceptions.add(e);
      } finally {
        shutDownBarrier.countDown();
      }
    }
  }

  private class ClientEndpoint extends AbstractSocketEndpoint {
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
    public final void runImpl() throws IOException {
      try (DatagramSocket socket = new DatagramSocket()) {
        registerStartUpClient();
        InetAddress ipAddress = remoteAddress.getRemoteAddress();
        InetSocketAddress iSAddress =
            new InetSocketAddress(ipAddress, remoteAddress.getRemotePort());
        socket.connect(iSAddress);
        for (int i = 0; i < nRounds; i++) {
          client.run(socket, remoteAddress, id);
          nRoundsClient.incrementAndGet();
        }
        socket.disconnect();
      }
    }
  }

  private class ServerEndpoint extends AbstractSocketEndpoint {
    private final Server server;
    private final int nRounds;

    ServerEndpoint(Server server, int id, int nRounds) {
      super(id);
      this.server = server;
      this.nRounds = nRounds;
    }

    @Override
    public final void runImpl() throws IOException {
      try (DatagramSocket socket = new DatagramSocket()) {
        registerStartUpServer((InetSocketAddress) socket.getLocalSocketAddress());
        for (int i = 0; i < nRounds; i++) {
          server.run(socket);
          nRoundsServer.incrementAndGet();
        }
      }
    }
  }

  /** Multi-threaded server endpoint. */
  private class ServerEndpointMT extends AbstractSocketEndpoint {
    private final Server server;
    private final int nRounds;
    private final DatagramSocket socket;

    ServerEndpointMT(Server server, DatagramSocket socket, int id, int nRounds) {
      super(id);
      this.server = server;
      this.socket = socket;
      this.nRounds = nRounds;
    }

    @Override
    public final void runImpl() throws IOException {
      registerStartUpServer((InetSocketAddress) socket.getLocalSocketAddress());
      for (int i = 0; i < nRounds; i++) {
        server.run(socket);
        nRoundsServer.incrementAndGet();
      }
    }
  }

  public interface Client {
    void run(DatagramSocket socket, RequestPath path, int id) throws IOException;
  }

  public interface Server {
    void run(DatagramSocket socket) throws IOException;
  }

  public void runPingPong(Server serverFn, Client clientFn) {
    runPingPong(serverFn, clientFn, true);
  }

  public void runPingPong(Server serverFn, Client clientFn, boolean reset) {
    runPingPong(
        (id, nRounds) -> new ServerEndpoint(serverFn, id, nRounds),
        (id, path, nRounds) -> new ClientEndpoint(clientFn, id, path, nRounds),
        reset);
  }

  public void runPingPongSharedServerSocket(
      Server receiverFn, Server senderFn, Client clientFn, boolean reset) throws IOException {
    if (nServers != 2) {
      throw new IllegalStateException();
    }
    try (DatagramSocket socket = new DatagramSocket(MockNetwork.getTinyServerAddress())) {
      runPingPong(
          (id, nRounds) ->
              new ServerEndpointMT((id % 2 == 0) ? receiverFn : senderFn, socket, id, nRounds),
          (id, path, nRounds) -> new ClientEndpoint(clientFn, id, path, nRounds),
          reset);
    }
  }

  public static void defaultServer(DatagramSocket channel) throws IOException {
    DatagramPacket packet = new DatagramPacket(new byte[512], 512);
    channel.receive(packet);

    ByteBuffer buffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
    String msg = Charset.defaultCharset().decode(buffer).toString();
    assertTrue(msg.startsWith(MSG), msg);
    assertTrue(MSG.length() + 3 >= msg.length());

    channel.send(packet);
  }
}
