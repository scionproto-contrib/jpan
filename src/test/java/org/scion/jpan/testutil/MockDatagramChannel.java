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

package org.scion.jpan.testutil;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.scion.jpan.demo.inspector.ScionPacketInspector;

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
  private MockSelectionKey selectionKey = null;

  private boolean throwOnConnect = false;

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

  public void setThrowOnConnect(boolean flag) {
    this.throwOnConnect = flag;
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
    if (throwOnConnect) {
      throw new IOException();
    }
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

  /**
   * Configures the mock as a simple echo system. Packets from send() are reversed and added to a
   * queue that is consumed by receive().
   */
  public void configureSimpleEcho() {
    ConcurrentLinkedDeque<ByteBuffer> packets = new ConcurrentLinkedDeque<>();
    ConcurrentLinkedDeque<InetSocketAddress> addresses = new ConcurrentLinkedDeque<>();
    setSendCallback(
        (byteBuffer, address) -> {
          ScionPacketInspector spi = ScionPacketInspector.readPacket(byteBuffer);
          spi.reversePath();
          ByteBuffer buf2 = ByteBuffer.allocate(1000);
          spi.writePacket(buf2, new byte[] {64});
          buf2.flip();
          packets.add(buf2);
          addresses.add((InetSocketAddress) address);
          return 1;
        });
    setReceiveCallback(
        byteBuffer -> {
          byteBuffer.put(packets.pop());
          return addresses.pop();
        });
  }

  public static class MockSelector extends AbstractSelector {
    private final ConcurrentHashMap<SelectionKey, Object> keys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SelectionKey, Object> selectedKeys = new ConcurrentHashMap<>();

    private Callable<Integer> selectCallback = null;

    public void setSelectCallback(Callable<Integer> callback) {
      this.selectCallback = callback;
    }

    public static MockSelector open() {
      return new MockSelector(new MockSelectorProvider());
    }

    /**
     * Initializes a new instance of this class.
     *
     * @param provider The provider that created this selector
     */
    protected MockSelector(SelectorProvider provider) {
      super(provider);
    }

    @Override
    protected void implCloseSelector() throws IOException {
      for (SelectionKey key : keys.keySet()) {
        key.cancel();
      }
    }

    @Override
    protected SelectionKey register(AbstractSelectableChannel ch, int ops, Object att) {
      MockSelectionKey key = new MockSelectionKey(ch, ops, this);
      key.attach(att);
      keys.put(key, new Object());
      return key;
    }

    @Override
    public Set<SelectionKey> keys() {
      return keys.keySet();
    }

    @Override
    public Set<SelectionKey> selectedKeys() {
      return selectedKeys.keySet();
    }

    @Override
    public int selectNow() throws IOException {
      return 0;
    }

    @Override
    public int select(long timeout) throws IOException {
      return 0;
    }

    @Override
    public int select() throws IOException {
      try {
        if (selectCallback != null) {
          return selectCallback.call();
        }
        while (true) {
          synchronized (selectedKeys) {
            if (selectedKeys.isEmpty()) {
              selectedKeys.wait();
            }
            return selectedKeys().size();
          }
        }
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    @Override
    public Selector wakeup() {
      synchronized (selectedKeys) {
        selectedKeys.notifyAll(); // TODO ensure return even if isEmpty
      }
      return this;
    }

    public void mockActivateKey(SelectionKey key) {
      synchronized(selectedKeys) {
        selectedKeys.put(key, new Object());
        selectedKeys.notifyAll();
      }
    }
  }

  public static class MockSelectorProvider extends SelectorProvider {

    @Override
    public DatagramChannel openDatagramChannel() throws IOException {
      return null;
    }

    @Override
    public DatagramChannel openDatagramChannel(ProtocolFamily family) throws IOException {
      return null;
    }

    @Override
    public Pipe openPipe() throws IOException {
      return null;
    }

    @Override
    public AbstractSelector openSelector() throws IOException {
      return null;
    }

    @Override
    public ServerSocketChannel openServerSocketChannel() throws IOException {
      return null;
    }

    @Override
    public SocketChannel openSocketChannel() throws IOException {
      return null;
    }
  }

  public static class MockSelectionKey extends SelectionKey {
    private final AbstractSelectableChannel channel;
    private int ops;
    private boolean isValid = true;
    private Selector selector;

    MockSelectionKey(AbstractSelectableChannel ch, int ops, Selector selector) {
      super();
      this.channel = ch;
      this.ops = ops;
      this.selector = selector;
    }

    @Override
    public SelectableChannel channel() {
      return channel;
    }

    @Override
    public Selector selector() {
      return selector;
    }

    @Override
    public boolean isValid() {
      return isValid;
    }

    @Override
    public void cancel() {
      isValid = false;
    }

    @Override
    public int interestOps() {
      return ops;
    }

    @Override
    public SelectionKey interestOps(int ops) {
      this.ops = ops;
      return this;
    }

    @Override
    public int readyOps() {
      int op = 0;
      op |= OP_READ;
      op |= OP_WRITE;
      op |= OP_ACCEPT;
      op |= OP_CONNECT;
      return op;
    }
  }
}
