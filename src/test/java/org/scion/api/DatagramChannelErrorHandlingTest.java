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

package org.scion.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.channels.MembershipKey;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.testutil.PingPongHelper;

/** Test path switching (changing first hop) on DatagramChannel. */
class DatagramChannelErrorHandlingTest {

  private final PathPolicy alternatingPolicy =
      new PathPolicy() {
        private int count = 0;

        @Override
        public RequestPath filter(List<RequestPath> paths) {
          return paths.get(count++ % 2);
        }
      };

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void testDummyChannel() throws IOException {
    //    java.nio.channels.DatagramChannel channel2 = new TestChannel();
    //    DatagramChannel channel = null;
    //    try {
    //      channel = Scion.defaultService().openChannel(channel2);
    //    } finally {
    //      channel.close();
    //    }
  }

  @Test
  void test() {
    //    PingPongHelper.Server serverFn = PingPongHelper::defaultServer;
    //    PingPongHelper.Client clientFn = this::client;
    //    PingPongHelper pph = new PingPongHelper(1, 2, 10);
    //    pph.runPingPong(serverFn, clientFn, false);
    //    assertEquals(2 * 10, MockNetwork.getForwardCount(0));
    //    assertEquals(2 * 10, MockNetwork.getForwardCount(1));
    //    assertEquals(2 * 2 * 10, MockNetwork.getAndResetForwardCount());
  }

  private void client(DatagramChannel channel, Path serverAddress, int id) throws IOException {
    String message = PingPongHelper.MSG + "-" + id;
    ByteBuffer sendBuf = ByteBuffer.wrap(message.getBytes());

    // Use a path policy that alternates between 1st and 2nd path
    // -> setPathPolicy() sets a new path!
    channel.setPathPolicy(alternatingPolicy);
    channel.write(sendBuf);

    // System.out.println("CLIENT: Receiving ... (" + channel.getLocalAddress() + ")");
    ByteBuffer response = ByteBuffer.allocate(512);
    int len = channel.read(response);
    assertEquals(message.length(), len);

    response.flip();
    String pong = Charset.defaultCharset().decode(response).toString();
    assertEquals(message, pong);
  }

  private static class TestChannel extends java.nio.channels.DatagramChannel {
    private boolean isOpen = true;
    private boolean isConnected = false;
    private boolean isBlocking = false;
    private SocketAddress bindAddress;
    private SocketAddress connectAddress;

    protected TestChannel() {
      super(SelectorProvider.provider());
    }

    @Override
    public java.nio.channels.DatagramChannel bind(SocketAddress socketAddress) throws IOException {
      bindAddress = socketAddress;
      return this;
    }

    @Override
    public <T> java.nio.channels.DatagramChannel setOption(SocketOption<T> socketOption, T t)
        throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T getOption(SocketOption<T> socketOption) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
      throw new UnsupportedOperationException();
    }

    @Override
    public DatagramSocket socket() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isConnected() {
      return isConnected;
    }

    @Override
    public java.nio.channels.DatagramChannel connect(SocketAddress socketAddress)
        throws IOException {
      connectAddress = socketAddress;
      isConnected = true;
      return this;
    }

    @Override
    public java.nio.channels.DatagramChannel disconnect() throws IOException {
      connectAddress = null;
      isConnected = false;
      return this;
    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
      return connectAddress;
    }

    @Override
    public SocketAddress receive(ByteBuffer byteBuffer) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int send(ByteBuffer byteBuffer, SocketAddress socketAddress) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int read(ByteBuffer byteBuffer) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public long read(ByteBuffer[] byteBuffers, int i, int i1) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int write(ByteBuffer byteBuffer) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public long write(ByteBuffer[] byteBuffers, int i, int i1) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
      return bindAddress;
    }

    @Override
    public MembershipKey join(InetAddress inetAddress, NetworkInterface networkInterface)
        throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public MembershipKey join(
        InetAddress inetAddress, NetworkInterface networkInterface, InetAddress inetAddress1)
        throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
      isConnected = false;
      isOpen = false;
      connectAddress = null;
      bindAddress = null;
    }

    @Override
    protected void implConfigureBlocking(boolean b) throws IOException {
      this.isBlocking = b;
    }
  }
}
