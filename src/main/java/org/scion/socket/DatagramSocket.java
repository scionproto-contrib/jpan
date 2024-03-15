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

package org.scion.socket;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.net.DatagramSocketImpl;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.IllegalBlockingModeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.scion.*;
import org.scion.internal.SimpleCache;

/**
 * A DatagramSocket that is SCION path aware. It can send and receive SCION packets.
 *
 * <p>Note: use of this class is discouraged in favor of org.scion.{@link
 * org.scion.DatagramChannel}. The reason is that this class' API (InetAddress and DatagramPacket)
 * cannot be extended to support SCION paths. As a consequence, a server needs to cache paths
 * internally which requires memory and may cause exceptions if more connections (=paths) are
 * managed than the configured thresholds allows.
 */
public class DatagramSocket extends java.net.DatagramSocket {

  private static final Set<SocketOption<?>> supportedOptions = new HashSet<>();

  static {
    supportedOptions.add(ScionSocketOptions.SN_RESPONSE_PATH_CACHE_MIN);
    supportedOptions.add(ScionSocketOptions.SN_RESPONSE_PATH_CACHE_MAX);
    supportedOptions.add(ScionSocketOptions.SN_API_THROW_PARSER_FAILURE);
    supportedOptions.add(ScionSocketOptions.SN_PATH_EXPIRY_MARGIN);
    supportedOptions.add(StandardSocketOptions.SO_SNDBUF);
    supportedOptions.add(StandardSocketOptions.SO_RCVBUF);
  }

  private final SelectingDatagramChannel channel;
  private boolean isBound = false;
  private final SimpleCache<InetAddress, Path> pathCache = new SimpleCache<>(100);

  public DatagramSocket() throws SocketException {
    this(new InetSocketAddress(0), null);
  }

  public DatagramSocket(int port) throws SocketException {
    this(port, null);
  }

  public DatagramSocket(int port, InetAddress bindAddress) throws SocketException {
    this(new InetSocketAddress(bindAddress, port));
  }

  public DatagramSocket(SocketAddress bindAddress) throws SocketException {
    this(bindAddress, null);
  }

  // "private" to avoid ambiguity with DatagramSocket((SockeAddress) null) -> use create()
  private DatagramSocket(ScionService service) throws SocketException {
    super(new DummyDatagramSocketImpl());
    try {
      channel = new SelectingDatagramChannel(service);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  // "private" for consistency, all non-standard constructors are private -> use create()
  private DatagramSocket(SocketAddress bindAddress, ScionService service) throws SocketException {
    this(service);
    // DatagramSockets always immediately bind unless the bindAddress is null.
    if (bindAddress != null) {
      try {
        channel.bind(checkAddress(bindAddress));
      } catch (IOException e) {
        throw new SocketException(e.getMessage());
      }
    }
  }

  public static DatagramSocket create(ScionService service) throws SocketException {
    return new DatagramSocket(service);
  }

  public static DatagramSocket create(SocketAddress bindAddress, ScionService service)
      throws SocketException {
    return new DatagramSocket(bindAddress, service);
  }

  public static synchronized void setDatagramSocketImplFactory(DatagramSocketImplFactory factory)
      throws IOException {
    throw new UnsupportedOperationException(); // TODO?
  }

  private void checkOpen() throws SocketException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
  }

  private void checkBlockingMode() {
    if (channel.isBlocking()) {
      throw new IllegalBlockingModeException();
    }
  }

  private static InetSocketAddress checkAddress(SocketAddress address) {
    if (!(address instanceof InetSocketAddress)) {
      throw new IllegalArgumentException("Address must be an InetSocketAddress");
    }
    return (InetSocketAddress) address;
  }

  @Override
  public synchronized void bind(SocketAddress address) throws SocketException {
    if (isBound()) {
      throw new SocketException("already bound");
    }
    try {
      channel.bind(checkAddress(address));
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  public synchronized void connect(RequestPath path) {
    try {
      channel.connect(path);
    } catch (IOException e) {
      throw new UncheckedIOException("connect failed", e);
    }
  }

  @Override
  public synchronized void connect(InetAddress address, int port) {
    try {
      connect(new InetSocketAddress(address, port));
    } catch (IOException e) {
      throw new UncheckedIOException("connect failed", e);
    }
  }

  @Override
  public synchronized void connect(SocketAddress address) throws SocketException {
    try {
      if (channel.isConnected()) {
        channel.disconnect();
      }
      channel.connect(checkAddress(address));
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized void disconnect() {
    try {
      channel.disconnect();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public synchronized boolean isBound() {
    return isBound;
  }

  @Override
  public synchronized boolean isConnected() {
    return channel.isConnected();
  }

  @Override
  public synchronized void close() {
    try {
      channel.close();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
    isBound = false;
  }

  @Override
  public synchronized boolean isClosed() {
    return !channel.isOpen();
  }

  @Override
  public synchronized InetAddress getInetAddress() {
    try {
      return channel.getLocalAddress().getAddress();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public synchronized int getPort() {
    try {
      return channel.getLocalAddress().getPort();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public synchronized SocketAddress getRemoteSocketAddress() {
    try {
      return channel.getRemoteAddress();
    } catch (UnknownHostException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public synchronized SocketAddress getLocalSocketAddress() {
    try {
      return channel.getLocalAddress();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public synchronized void send(DatagramPacket packet) throws IOException {
    checkOpen();
    checkBlockingMode();
    if (isConnected() && !channel.getRemoteAddress().equals(packet.getSocketAddress())) {
      throw new IllegalArgumentException();
    }

    Path path;
    if (channel.isConnected()) {
      path = channel.getConnectionPath();
    } else {
      InetSocketAddress addr = (InetSocketAddress) packet.getSocketAddress();
      synchronized (pathCache) {
        path = pathCache.get(packet.getAddress());
        if (path == null) {
          path = channel.getPathPolicy().filter(channel.getOrCreateService().getPaths(addr));
        } else if (path instanceof RequestPath
            && ((RequestPath) path).getExpiration() > Instant.now().getEpochSecond()) {
          // check expiration only for RequestPaths
          RequestPath request = (RequestPath) path;
          path = channel.getPathPolicy().filter(channel.getOrCreateService().getPaths(request));
        } else {
          if (path.getDestinationPort() != packet.getPort()) {
            throw new IllegalArgumentException("Illegal port, must be the same as received port.");
          }
        }
        if (path == null) {
          throw new IOException("Address is not resolvable in SCION: " + packet.getAddress());
        }
        pathCache.put(addr.getAddress(), path);
      }
    }
    ByteBuffer buf = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
    channel.send(buf, path);
  }

  @Override
  public synchronized void receive(DatagramPacket datagramPacket) throws IOException {
    checkOpen();
    checkBlockingMode();

    ByteBuffer receiveBuffer =
        ByteBuffer.wrap(
            datagramPacket.getData(), datagramPacket.getOffset(), datagramPacket.getLength());
    ResponsePath path = channel.receive(receiveBuffer);
    if (path == null) {
      // timeout occurred
      throw new SocketTimeoutException();
    }
    if (!channel.isConnected()) {
      synchronized (pathCache) {
        pathCache.put(InetAddress.getByAddress(path.getDestinationAddress()), path);
      }
    }
    receiveBuffer.flip();
    datagramPacket.setLength(receiveBuffer.limit());
    datagramPacket.setAddress(InetAddress.getByAddress(path.getDestinationAddress()));
    datagramPacket.setPort(path.getDestinationPort());
  }

  @Override
  public synchronized InetAddress getLocalAddress() {
    try {
      InetSocketAddress address = channel.getLocalAddress();
      if (address == null) {
        return null;
      }
      return address.getAddress();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public synchronized int getLocalPort() {
    try {
      return channel.getLocalAddress().getPort();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public synchronized boolean getBroadcast() throws SocketException {
    checkOpen();
    try {
      return channel.getOption(StandardSocketOptions.SO_BROADCAST);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized void setBroadcast(boolean flag) throws SocketException {
    checkOpen();
    try {
      channel.setOption(StandardSocketOptions.SO_BROADCAST, flag);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized boolean getReuseAddress() throws SocketException {
    checkOpen();
    try {
      return channel.getOption(StandardSocketOptions.SO_REUSEADDR);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized void setReuseAddress(boolean flag) throws SocketException {
    checkOpen();
    try {
      channel.setOption(StandardSocketOptions.SO_REUSEADDR, flag);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized int getReceiveBufferSize() throws SocketException {
    checkOpen();
    try {
      return channel.getOption(StandardSocketOptions.SO_RCVBUF);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized void setReceiveBufferSize(int size) throws SocketException {
    checkOpen();
    try {
      channel.setOption(StandardSocketOptions.SO_RCVBUF, size);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized int getSendBufferSize() throws SocketException {
    checkOpen();
    try {
      return channel.getOption(StandardSocketOptions.SO_SNDBUF);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized void setSendBufferSize(int size) throws SocketException {
    checkOpen();
    try {
      channel.setOption(StandardSocketOptions.SO_SNDBUF, size);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized int getSoTimeout() throws SocketException {
    checkOpen();
    return channel.getTimeOut();
  }

  @Override
  public synchronized void setSoTimeout(int timeOut) throws SocketException {
    checkOpen();
    channel.setTimeOut(timeOut);
  }

  @SuppressWarnings("deprecation")
  @Override
  public synchronized int getTrafficClass() throws SocketException {
    checkOpen();
    try {
      return channel.getOption(ScionSocketOptions.SN_TRAFFIC_CLASS);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @SuppressWarnings("deprecation")
  @Override
  public synchronized void setTrafficClass(int trafficClass) throws SocketException {
    checkOpen();
    try {
      channel.setOption(ScionSocketOptions.SN_TRAFFIC_CLASS, trafficClass);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @Override
  public synchronized DatagramChannel getChannel() {
    throw new UnsupportedOperationException();
    // TODO return channel once we implement it properly
    // return channel;
  }

  public synchronized <T> DatagramSocket setOption(SocketOption<T> name, T value)
      throws IOException {
    channel.setOption(name, value);
    return this;
  }

  public synchronized <T> T getOption(SocketOption<T> name) throws IOException {
    return channel.getOption(name);
  }

  public synchronized Set<SocketOption<?>> supportedOptions() {
    // see https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/DatagramSocket.html
    // for a list of supported options
    return supportedOptions;
  }

  public synchronized RequestPath getConnectionPath() {
    return (RequestPath) channel.getConnectionPath();
  }

  public synchronized Path getCachedPath(InetAddress address) {
    synchronized (pathCache) {
      return pathCache.get(address);
    }
  }

  public synchronized void setPathCacheCapacity(int capacity) {
    synchronized (pathCache) {
      pathCache.setCapacity(capacity);
    }
  }

  public synchronized int getPathCacheCapacity() {
    synchronized (pathCache) {
      return pathCache.getCapacity();
    }
  }

  /**
   * @return The associated DatagramChannel
   * @deprecated This method will be removed in a future version once {@link #getChannel()} is
   *     implemented.
   */
  @Deprecated
  public synchronized org.scion.DatagramChannel getScionChannel() {
    return channel;
  }

  public synchronized ScionService getService() {
    return channel.getService();
  }

  public synchronized PathPolicy getPathPolicy() {
    return channel.getPathPolicy();
  }

  public synchronized void setPathPolicy(PathPolicy pathPolicy) throws IOException {
    channel.setPathPolicy(pathPolicy);
  }

  private static class DummyDatagramSocketImpl extends DatagramSocketImpl {

    @Override
    protected void create() {
      // empty
    }

    @Override
    protected void bind(int i, InetAddress inetAddress) {
      // empty
    }

    @Override
    protected void send(DatagramPacket datagramPacket) {
      // empty
    }

    @Override
    protected int peek(InetAddress inetAddress) {
      return 0;
    }

    @Override
    protected int peekData(DatagramPacket datagramPacket) {
      return 0;
    }

    @Override
    protected void receive(DatagramPacket datagramPacket) {
      // empty
    }

    @Override
    protected void setTTL(byte b) {
      // empty
    }

    @Override
    protected byte getTTL() {
      return 0;
    }

    @Override
    protected void setTimeToLive(int i) {
      // empty
    }

    @Override
    protected int getTimeToLive() {
      return 0;
    }

    @Override
    protected void join(InetAddress inetAddress) {
      // empty
    }

    @Override
    protected void leave(InetAddress inetAddress) {
      // empty
    }

    @Override
    protected void joinGroup(SocketAddress socketAddress, NetworkInterface networkInterface) {
      // empty
    }

    @Override
    protected void leaveGroup(SocketAddress socketAddress, NetworkInterface networkInterface) {
      // empty
    }

    @Override
    protected void close() {
      // empty
    }

    @Override
    public void setOption(int i, Object o) {
      // empty
    }

    @Override
    public Object getOption(int i) {
      return null;
    }
  }
}
