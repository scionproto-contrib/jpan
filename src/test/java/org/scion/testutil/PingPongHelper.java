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
import java.nio.charset.Charset;
import org.scion.DatagramChannel;
import org.scion.Path;
import org.scion.RequestPath;

public class PingPongHelper extends PingPongHelperBase {

  public PingPongHelper(int nServers, int nClients, int nRounds) {
    this(nServers, nClients, nRounds, true);
  }

  public PingPongHelper(int nServers, int nClients, int nRounds, boolean connect) {
    super(nServers, nClients, nRounds, connect);
  }

  private abstract class AbstractChannelEndpoint extends AbstractEndpoint {
    AbstractChannelEndpoint(int id) {
      super(id);
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
    public final void runImpl(DatagramChannel channel) throws IOException {
      if (connect) {
        InetAddress inetAddress = InetAddress.getByAddress(path.getDestinationAddress());
        InetSocketAddress iSAddress = new InetSocketAddress(inetAddress, path.getDestinationPort());
        channel.connect(iSAddress);
      }
      for (int i = 0; i < nRounds; i++) {
        client.run(channel, path, id);
        nRoundsClient.incrementAndGet();
      }
      channel.disconnect();
    }
  }

  private class ServerEndpoint extends AbstractChannelEndpoint {
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
    runPingPong(
        (id, address, nRounds) -> new Thread(new ServerEndpoint(serverFn, id, address, nRounds)),
        (id, path, nRounds) ->
            new Thread(new ClientEndpoint(clientFn, id, path, nRounds, connectClients)),
        reset);
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
    // System.out.println("SERVER: Receiving ... (" + channel.getLocalAddress() + ")");
    Path address = channel.receive(request);

    request.flip();
    String msg = Charset.defaultCharset().decode(request).toString();
    assertTrue(msg.startsWith(MSG), msg);
    assertTrue(MSG.length() + 3 >= msg.length());

    request.flip();
    channel.send(request, address);
    // System.out.println("SERVER: Sent: " + address);
  }
}
