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
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.scion.jpan.Path;
import org.scion.jpan.RequestPath;
import org.scion.jpan.ScionDatagramSocket;

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
      try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
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
      try (ScionDatagramSocket socket = new ScionDatagramSocket()) {
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
    private final ScionDatagramSocket socket;

    ServerEndpointMT(Server server, ScionDatagramSocket socket, int id, int nRounds) {
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
    void run(ScionDatagramSocket socket, RequestPath path, int id) throws IOException;
  }

  public interface Server {
    void run(ScionDatagramSocket socket) throws IOException;
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
    try (ScionDatagramSocket socket = new ScionDatagramSocket(MockNetwork.TINY_SRV_PORT_1)) {
      runPingPong(
          (id, nRounds) ->
              new ServerEndpointMT((id % 2 == 0) ? receiverFn : senderFn, socket, id, nRounds),
          (id, path, nRounds) -> new ClientEndpoint(clientFn, id, path, nRounds),
          reset);
    }
  }

  public static void defaultClient(ScionDatagramSocket socket, Path path, int id)
      throws IOException {
    String message = PingPongChannelHelper.MSG + "-" + id;
    InetSocketAddress dst = new InetSocketAddress(path.getRemoteAddress(), path.getRemotePort());

    DatagramPacket packetOut = new DatagramPacket(message.getBytes(), message.length(), dst);
    socket.send(packetOut);

    // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
    DatagramPacket packetIn = new DatagramPacket(new byte[100], 100);
    socket.receive(packetIn);

    ByteBuffer response = ByteBuffer.wrap(packetIn.getData(), 0, packetIn.getLength());
    String pong = Charset.defaultCharset().decode(response).toString();
    assertEquals(message, pong);
  }

  public static void defaultServer(ScionDatagramSocket socket) throws IOException {
    DatagramPacket packet = new DatagramPacket(new byte[512], 512);
    socket.receive(packet);

    ByteBuffer buffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
    String msg = Charset.defaultCharset().decode(buffer).toString();
    assertTrue(msg.startsWith(MSG), msg);
    assertTrue(MSG.length() + 3 >= msg.length());

    socket.send(packet);
  }
}
