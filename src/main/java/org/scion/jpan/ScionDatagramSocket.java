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

package org.scion.jpan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.net.DatagramSocketImpl;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.IllegalBlockingModeException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.scion.jpan.internal.SelectingDatagramChannel;
import org.scion.jpan.internal.SimpleCache;

/**
 * A DatagramSocket that is SCION path aware. It can send and receive SCION packets.
 *
 * <p>Note: use of this class is discouraged in favor of org.scion.{@link ScionDatagramChannel}. The
 * reason is that this class' API (InetAddress and DatagramPacket) cannot be extended to support
 * SCION paths. As a consequence, a server needs to cache paths internally which requires memory and
 * may cause exceptions if more connections (=paths) are managed than the configured thresholds
 * allows.
 */
public class ScionDatagramSocket extends java.net.DatagramSocket {

  private static final Set<SocketOption<?>> supportedOptions;

  static {
    HashSet<SocketOption<?>> options = new HashSet<>();
    options.add(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE);
    options.add(ScionSocketOptions.SCION_PATH_EXPIRY_MARGIN);

    options.add(StandardSocketOptions.SO_SNDBUF);
    options.add(StandardSocketOptions.SO_RCVBUF);
    options.add(StandardSocketOptions.SO_REUSEADDR);
    options.add(StandardSocketOptions.IP_TOS);
    supportedOptions = Collections.unmodifiableSet(options);
  }

  private final SelectingDatagramChannel channel;
  private boolean isBound = false;
  private final SimpleCache<InetSocketAddress, Path> pathCache = new SimpleCache<>(100);
  private final Object closeLock = new Object();

  public ScionDatagramSocket() throws SocketException {
    this(new InetSocketAddress(0), Scion.defaultService());
  }

  public ScionDatagramSocket(int port) throws SocketException {
    this(port, null);
  }

  public ScionDatagramSocket(int port, InetAddress bindAddress) throws SocketException {
    this(new InetSocketAddress(bindAddress, port));
  }

  public ScionDatagramSocket(SocketAddress bindAddress) throws SocketException {
    this(bindAddress, Scion.defaultService());
  }

  // "private" to avoid ambiguity with DatagramSocket((SocketAddress) null) -> use create()
  protected ScionDatagramSocket(ScionService service, DatagramChannel channel)
      throws SocketException {
    super(new DummyDatagramSocketImpl());
    try {
      this.channel = new SelectingDatagramChannel(service, channel);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  // "private" for consistency, all non-standard constructors are private -> use create()
  protected ScionDatagramSocket(SocketAddress bindAddress, ScionService service)
      throws SocketException {
    this(service, null);
    // DatagramSockets always immediately bind unless the bindAddress is null.
    if (bindAddress != null) {
      try {
        channel.bind(checkAddress(bindAddress));
        isBound = true;
      } catch (IOException e) {
        throw new SocketException(e.getMessage());
      }
    }
  }

  /**
   * Creates a ScionDatagramSocket with a specific ScionService instance. Under some circumstances
   * the ScionService may be 'null', however, this is not recommended. See also {@link
   * ScionDatagramChannel#open(ScionService)}.
   *
   * @param service A ScionService or 'null'.
   * @return a new socket.
   */
  public static ScionDatagramSocket create(ScionService service) throws SocketException {
    return new ScionDatagramSocket(service, null);
  }

  public static ScionDatagramSocket create(ScionService service, DatagramChannel channel)
      throws SocketException {
    return new ScionDatagramSocket(service, channel);
  }

  public static ScionDatagramSocket create(SocketAddress bindAddress, ScionService service)
      throws SocketException {
    return new ScionDatagramSocket(bindAddress, service);
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

  private static InetSocketAddress checkAddressOrNull(SocketAddress address) {
    if (address != null && !(address instanceof InetSocketAddress)) {
      throw new IllegalArgumentException("Address must be an InetSocketAddress");
    }
    return (InetSocketAddress) address;
  }

  private static void checkAddress(InetAddress address) {
    if (address == null) {
      throw new IllegalArgumentException("Address must not be null");
    }
  }

  private static void checkPort(int port) {
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("Port out of range");
    }
  }

  @Override
  public synchronized void bind(SocketAddress address) throws SocketException {
    try {
      channel.bind(checkAddressOrNull(address));
      isBound = true;
    } catch (AlreadyBoundException e) {
      throw new SocketException("already bound");
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  /**
   * Connect to a destination using a specific path. See {@link ScionDatagramChannel#connect(Path)}
   * for details.
   *
   * @param path path to destination
   * @see ScionDatagramChannel#connect(Path)
   */
  public synchronized void connect(Path path) {
    try {
      channel.connect(path);
    } catch (IOException e) {
      throw new UncheckedIOException("connect failed", e);
    }
  }

  @Override
  public synchronized void connect(InetAddress address, int port) {
    checkAddress(address);
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
  public boolean isBound() {
    return isBound;
  }

  @Override
  public boolean isConnected() {
    return channel.isConnected();
  }

  @Override
  public synchronized void close() {
    synchronized (closeLock) {
      try {
        channel.close();
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
    }
  }

  @Override
  public boolean isClosed() {
    synchronized (closeLock) {
      return !channel.isOpen();
    }
  }

  @Override
  public InetAddress getInetAddress() {
    try {
      return channel.getLocalAddress().getAddress();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public int getPort() {
    try {
      return channel.getLocalAddress().getPort();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    try {
      return channel.getRemoteAddress();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    try {
      return channel.getLocalAddress();
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  @Override
  public void send(DatagramPacket packet) throws IOException {
    checkOpen();
    checkBlockingMode();
    checkAddress(packet.getAddress());
    checkPort(packet.getPort());

    // Synchronize on packet because this is what the Java DatagramSocket does.
    synchronized (packet) {
      // TODO synchronize also on writeLock()!
      if (isConnected() && !channel.getRemoteAddress().equals(packet.getSocketAddress())) {
        throw new IllegalArgumentException("Packet address does not match connected address");
      }

      Path path;
      if (channel.isConnected()) {
        path = channel.getConnectionPath();
      } else {
        InetSocketAddress addr = (InetSocketAddress) packet.getSocketAddress();
        synchronized (pathCache) {
          path = pathCache.get(addr);
          if (path == null) {
            path = channel.applyFilter(channel.getService().lookupPaths(addr), addr).get(0);
          } else if (path instanceof RequestPath
              && path.getMetadata().getExpiration() > Instant.now().getEpochSecond()) {
            // check expiration only for RequestPaths
            RequestPath request = (RequestPath) path;
            path = channel.applyFilter(channel.getService().getPaths(request), addr).get(0);
          }
          if (path == null) {
            throw new IOException("Address is not resolvable in SCION: " + addr.getAddress());
          }
          pathCache.put(addr, path);
        }
      }
      ByteBuffer buf = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
      channel.send(buf, path);
    }
  }

  @Override
  public synchronized void receive(DatagramPacket packet) throws IOException {
    checkOpen();
    checkBlockingMode();

    // We synchronize on the packet because that is what the Java socket does.
    synchronized (packet) {
      ByteBuffer receiveBuffer =
          ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
      ScionSocketAddress responseAddress = channel.receive(receiveBuffer);
      if (responseAddress == null) {
        // timeout occurred
        throw new SocketTimeoutException();
      }
      Path path = responseAddress.getPath();
      // TODO this is not ideal, a client may not be connected. Use getService()==null?
      if (!channel.isConnected()) {
        synchronized (pathCache) {
          InetAddress ip = path.getRemoteAddress();
          pathCache.put(new InetSocketAddress(ip, path.getRemotePort()), path);
        }
      }
      receiveBuffer.flip();
      packet.setLength(receiveBuffer.limit());
      packet.setAddress(path.getRemoteAddress());
      packet.setPort(path.getRemotePort());
    }
  }

  @Override
  public InetAddress getLocalAddress() {
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
  public int getLocalPort() {
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
      return channel.getOption(ScionSocketOptions.SCION_TRAFFIC_CLASS);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  @SuppressWarnings("deprecation")
  @Override
  public synchronized void setTrafficClass(int trafficClass) throws SocketException {
    checkOpen();
    try {
      channel.setOption(ScionSocketOptions.SCION_TRAFFIC_CLASS, trafficClass);
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

  public synchronized <T> ScionDatagramSocket setOption(SocketOption<T> name, T value)
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

  /**
   * This method is currently not supported in SCION. It is public because some JDKs make this
   * method public.
   *
   * @param mcastaddr multicast address
   * @param netIf network interface
   * @throws IOException in case of IO error
   */
  public void joinGroup(SocketAddress mcastaddr, NetworkInterface netIf) {
    throw new UnsupportedOperationException();
  }

  /**
   * This method is currently not supported in SCION. It is public because some JDKs make this
   * method public.
   *
   * @param mcastaddr multicast address
   * @param netIf network interface
   * @throws IOException in case of IO error
   */
  public void leaveGroup(SocketAddress mcastaddr, NetworkInterface netIf) {
    throw new UnsupportedOperationException();
  }

  /**
   * Get the currently connected path. The connected path is set during {@link #connect(Path)} and
   * may be refreshed when expired.
   *
   * @return the current Path or `null` if not path is connected.
   * @see ScionDatagramChannel#getConnectionPath()
   */
  public Path getConnectionPath() {
    return channel.getConnectionPath();
  }

  /**
   * The DatagramSocket caches paths from received packets. These are used to send responses to
   * these packets. The method getCachedPath() looks up the path (if any) for the given address.
   * Note that the cache size is limited, see {@link #setPathCacheCapacity(int)}.
   *
   * @return the cached Path or `null` if not path is found.
   * @see #setPathCacheCapacity
   */
  public synchronized Path getCachedPath(InetSocketAddress address) {
    synchronized (pathCache) {
      return pathCache.get(address);
    }
  }

  /**
   * The DatagramSocket caches paths from received packets. These are used to send responses to
   * these packets. The method setPathCacheCapacity() sets the size of the path cache. The default
   * size is 100.
   *
   * @see #getCachedPath
   * @see #getPathCacheCapacity()
   */
  public synchronized void setPathCacheCapacity(int capacity) {
    synchronized (pathCache) {
      pathCache.setCapacity(capacity);
    }
  }

  /**
   * The DatagramSocket caches paths from received packets. These are used to send responses to
   * these packets. The method getPathCacheCapacity() gets the size of the path cache.
   *
   * @return the size of the path cache.
   * @see #setPathCacheCapacity
   */
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
  public synchronized ScionDatagramChannel getScionChannel() {
    return channel;
  }

  /**
   * @return The currently associated ScionService for this socket. This usually returns 'null' for
   *     server side sockets because the service is only created for looking up SCION addresses and
   *     ISD/AS codes, which should not be necessary for a server.
   * @see ScionDatagramChannel#getService()
   */
  public synchronized ScionService getService() {
    return channel.getService();
  }

  public synchronized PathPolicy getPathPolicy() {
    return channel.getPathPolicy();
  }

  /**
   * Set the path policy. The default path policy is set in {@link PathPolicy#DEFAULT}. If the
   * socket is connected, this method will request a new path using the new policy.
   *
   * <p>After initially setting the path policy, it is used to request a new path during write() and
   * send() whenever a path turns out to be close to expiration.
   *
   * @param pathPolicy the new path policy
   * @see PathPolicy#DEFAULT
   * @see ScionDatagramChannel#setPathPolicy(PathPolicy)
   */
  public synchronized void setPathPolicy(PathPolicy pathPolicy) throws IOException {
    channel.setPathPolicy(pathPolicy);
  }

  /**
   * Assume that the destination host uses a dispatcher.
   *
   * <p>See {@link ScionDatagramChannel#configureRemoteDispatcher(boolean)}.
   *
   * @param hasDispatcher Set to 'true' if remote end-host uses a dispatcher and requires using port
   *     30041.
   * @see ScionDatagramChannel#configureRemoteDispatcher(boolean)
   * @deprecated Not required anymore, will be removed for 0.5.0
   */
  @Deprecated // TODO remove for 0.5.0
  public synchronized ScionDatagramSocket setRemoteDispatcher(boolean hasDispatcher) {
    channel.configureRemoteDispatcher(hasDispatcher);
    return this;
  }

  /**
   * Specify an source address override. See {@link
   * ScionDatagramChannel#setOverrideSourceAddress(InetSocketAddress)}.
   *
   * @param overrideSourceAddress Override address
   */
  public synchronized void setOverrideSourceAddress(InetSocketAddress overrideSourceAddress) {
    channel.setOverrideSourceAddress(overrideSourceAddress);
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
