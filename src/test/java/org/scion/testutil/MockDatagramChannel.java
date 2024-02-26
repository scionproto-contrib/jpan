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
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.scion.Path;
import org.scion.ScionSocketOptions;

public class MockDatagramChannel extends java.nio.channels.DatagramChannel {
  private boolean isOpen = true;
  private boolean isConnected = false;
  private boolean isBlocking = false;
  private SocketAddress bindAddress;
  private SocketAddress connectAddress;

  private Function<ByteBuffer, SocketAddress> receiveCallback =
      byteBuffer -> {
        throw new UnsupportedOperationException();
      };

  private BiConsumer<ByteBuffer, Path> sendCallback =
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

  public void setSendCallback(BiConsumer<ByteBuffer, Path> cb) {
    sendCallback = cb;
  }

  @Override
  public java.nio.channels.DatagramChannel bind(SocketAddress socketAddress) throws IOException {
    bindAddress = socketAddress;
    return this;
  }

  @Override
  public <T> java.nio.channels.DatagramChannel setOption(SocketOption<T> socketOption, T t)
      throws IOException {
    //        if (ScionSocketOptions.SN_API_THROW_PARSER_FAILURE.equals(option)) {
    //            cfgReportFailedValidation = (Boolean) t;
    //        } else if (ScionSocketOptions.SN_PATH_EXPIRY_MARGIN.equals(option)) {
    //            cfgExpirationSafetyMargin = (Integer) t;
    //        } else if (StandardSocketOptions.SO_RCVBUF.equals(option)) {
    //            // TODO resize buf
    //            channel.setOption(option, t);
    //        } else if (StandardSocketOptions.SO_SNDBUF.equals(option)) {
    //            // TODO resize buf
    //            channel.setOption(option, t);
    //        } else {
    //            channel.setOption(option, t);
    //        }
    //        return (C) this;
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T getOption(SocketOption<T> option) throws IOException {
    if (ScionSocketOptions.SN_API_THROW_PARSER_FAILURE.equals(option)) {
      return (T) (Boolean) false; // cfgReportFailedValidation;
    } else if (ScionSocketOptions.SN_PATH_EXPIRY_MARGIN.equals(option)) {
      return (T) (Integer) 10; // cfgExpirationSafetyMargin;
    } else if (StandardSocketOptions.SO_RCVBUF.equals(option)) {
      return (T) (Integer) 1000;
    } else if (StandardSocketOptions.SO_SNDBUF.equals(option)) {
      return (T) (Integer) 1000;
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
    return receiveCallback.apply(byteBuffer);
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
