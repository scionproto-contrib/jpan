// Copyright 2024 ETH Zurich
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

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.MembershipKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public class MockDatagramChannel extends java.nio.channels.DatagramChannel {

  private static final InetAddress BIND_ANY_IP;
  private static final InetSocketAddress BIND_ANY_SOCKET;

  static {
    try {
      BIND_ANY_IP = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
      BIND_ANY_SOCKET = new InetSocketAddress(BIND_ANY_IP, 33333);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean isOpen = true;
  private boolean isConnected = false;
  private boolean isBlocking = false;
  private SocketAddress bindAddress;
  private SocketAddress connectAddress;

  private Function<ByteBuffer, SocketAddress> receiveCallback =
      byteBuffer -> {
        throw new UnsupportedOperationException();
      };

  private BiFunction<ByteBuffer, SocketAddress, Integer> sendCallback =
      (byteBuffer, path) -> {
        throw new UnsupportedOperationException();
      };

  public static MockDatagramChannel open() throws IOException {
    return new MockDatagramChannel();
  }

  protected MockDatagramChannel() {
    super(SelectorProvider.provider());
  }

  public void setReceiveCallback(Function<ByteBuffer, SocketAddress> cb) {
    receiveCallback = cb;
  }

  public void setSendCallback(BiFunction<ByteBuffer, SocketAddress, Integer> cb) {
    sendCallback = cb;
  }

  @Override
  public java.nio.channels.DatagramChannel bind(SocketAddress socketAddress) throws IOException {
    if (socketAddress == null) {
      bindAddress = BIND_ANY_SOCKET;
    } else {
      bindAddress = socketAddress;
    }
    return this;
  }

  @Override
  public <T> java.nio.channels.DatagramChannel setOption(SocketOption<T> option, T t) {
    //    if (StandardSocketOptions.SO_RCVBUF.equals(option)) {
    //      cfgRCVBUF = (Integer) t;
    //    } else if (StandardSocketOptions.SO_SNDBUF.equals(option)) {
    //      cfgSNDBUF = (Integer) t;
    //    }
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T getOption(SocketOption<T> option) {
    if (StandardSocketOptions.SO_RCVBUF.equals(option)) {
      return (T) (Integer) 10000;
    } else if (StandardSocketOptions.SO_SNDBUF.equals(option)) {
      return (T) (Integer) 10000;
    }
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
  public java.nio.channels.DatagramChannel connect(SocketAddress socketAddress) throws IOException {
    connectAddress = socketAddress;
    isConnected = true;
    if (bindAddress == null) {
      bindAddress = BIND_ANY_SOCKET;
    }
    return this;
  }

  @Override
  public java.nio.channels.DatagramChannel disconnect() {
    connectAddress = null;
    isConnected = false;
    return this;
  }

  @Override
  public SocketAddress getRemoteAddress() {
    return connectAddress;
  }

  @Override
  public SocketAddress receive(ByteBuffer byteBuffer) throws IOException {
    if (bindAddress == null) {
      bindAddress = BIND_ANY_SOCKET;
    }
    return receiveCallback.apply(byteBuffer);
  }

  @Override
  public int send(ByteBuffer byteBuffer, SocketAddress socketAddress) throws IOException {
    if (bindAddress == null) {
      bindAddress = BIND_ANY_SOCKET;
    }
    return sendCallback.apply(byteBuffer, socketAddress);
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
  public MembershipKey join(InetAddress inetAddress, NetworkInterface networkInterface) {
    throw new UnsupportedOperationException();
  }

  @Override
  public MembershipKey join(
      InetAddress inetAddress, NetworkInterface networkInterface, InetAddress inetAddress1) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected void implCloseSelectableChannel() {
    isConnected = false;
    isOpen = false;
    connectAddress = null;
    bindAddress = null;
  }

  @Override
  protected void implConfigureBlocking(boolean b) {
    this.isBlocking = b;
  }
}
