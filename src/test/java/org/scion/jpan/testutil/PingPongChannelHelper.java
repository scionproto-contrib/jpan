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
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.scion.jpan.Path;
import org.scion.jpan.RequestPath;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionResponseAddress;

public class PingPongChannelHelper extends PingPongHelperBase {

  public PingPongChannelHelper(int nServers, int nClients, int nRounds) {
    this(nServers, nClients, nRounds, true);
  }

  public PingPongChannelHelper(int nServers, int nClients, int nRounds, boolean connect) {
    super(nServers, nClients, nRounds, connect);
  }

  private abstract class AbstractChannelEndpoint extends AbstractEndpoint {
    AbstractChannelEndpoint(int id) {
      super(id);
    }

    abstract void runImpl(ScionDatagramChannel channel) throws IOException;

    @Override
    public final void run() {
      try (ScionDatagramChannel channel = ScionDatagramChannel.open()) {
        channel.configureBlocking(true);
        runImpl(channel);
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

  private class ClientEndpoint extends AbstractChannelEndpoint {
    private final Client client;
    private final RequestPath path;
    private final int nRounds;
    private final boolean connect;

    ClientEndpoint(Client client, int id, RequestPath path, int nRounds, boolean connect) {
      super(id);
      this.client = client;
      this.path = path;
      this.nRounds = nRounds;
      this.connect = connect;
    }

    @Override
    public final void runImpl(ScionDatagramChannel channel) throws IOException {
      if (connect) {
        InetAddress inetAddress = path.getRemoteAddress();
        InetSocketAddress iSAddress = new InetSocketAddress(inetAddress, path.getRemotePort());
        channel.connect(iSAddress);
      }
      registerStartUpClient();
      for (int i = 0; i < nRounds; i++) {
        client.run(channel, path, id);
        nRoundsClient.incrementAndGet();
      }
      channel.disconnect();
    }
  }

  private class ServerEndpoint extends AbstractChannelEndpoint {
    private final Server server;
    private final int nRounds;

    ServerEndpoint(Server server, int id, int nRounds) {
      super(id);
      this.server = server;
      this.nRounds = nRounds;
    }

    @Override
    public final void runImpl(ScionDatagramChannel channel) throws IOException {
      channel.bind(null);
      registerStartUpServer(channel.getLocalAddress());
      for (int i = 0; i < nRounds; i++) {
        server.run(channel);
        nRoundsServer.incrementAndGet();
      }
    }
  }

  public interface Client {
    void run(ScionDatagramChannel channel, RequestPath path, int id) throws IOException;
  }

  public interface Server {
    void run(ScionDatagramChannel channel) throws IOException;
  }

  public void runPingPong(Server serverFn, Client clientFn) {
    runPingPong(serverFn, clientFn, true);
  }

  public void runPingPong(Server serverFn, Client clientFn, boolean reset) {
    runPingPong(
        (id, nRounds) -> new ServerEndpoint(serverFn, id, nRounds),
        (id, path, nRounds) -> new ClientEndpoint(clientFn, id, path, nRounds, connectClients),
        reset);
  }

  public static void defaultClient(ScionDatagramChannel channel, Path serverAddress, int id)
      throws IOException {
    String message = PingPongChannelHelper.MSG + "-" + id;
    ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());
    channel.send(sendBuf, serverAddress);

    // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
    ByteBuffer response = ByteBuffer.allocate(512);
    ScionResponseAddress address = channel.receive(response);
    assertNotNull(address);
    assertEquals(serverAddress.getRemoteAddress(), address.getAddress());
    assertEquals(serverAddress.getRemotePort(), address.getPort());

    response.flip();
    String pong = Charset.defaultCharset().decode(response).toString();
    assertEquals(message, pong);
  }

  public static void defaultServer(ScionDatagramChannel channel) throws IOException {
    ByteBuffer request = ByteBuffer.allocate(512);
    // System.out.println("SERVER: Receiving ... (" + channel.getLocalAddress() + ")");
    ScionResponseAddress responseAddress = channel.receive(request);

    request.flip();
    String msg = Charset.defaultCharset().decode(request).toString();
    assertTrue(msg.startsWith(MSG), msg);
    assertTrue(MSG.length() + 3 >= msg.length());

    request.flip();
    channel.send(request, responseAddress);
    // System.out.println("SERVER: Sent: " + address);
  }
}
