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

package org.scion;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.scion.internal.ExtensionHeader;
import org.scion.internal.InternalConstants;
import org.scion.internal.ScionHeaderParser;
import org.scion.internal.ScmpParser;

abstract class AbstractDatagramChannel<C extends AbstractDatagramChannel<?>> implements Closeable {

  private final java.nio.channels.DatagramChannel channel;
  private ByteBuffer bufferReceive;
  private ByteBuffer bufferSend;

  private final Object stateLock = new Object();
  private final ReentrantLock readLock = new ReentrantLock();
  private final ReentrantLock writeLock = new ReentrantLock();

  // This path is only used for write() after connect(), not for send().
  // Whether we have a connectionPath is independent of whether the underlying channel is connected.
  private RequestPath connectionPath;
  private InetAddress localAddress;
  private boolean cfgReportFailedValidation = false;
  private PathPolicy pathPolicy = PathPolicy.DEFAULT;
  private ScionService service;
  private int cfgExpirationSafetyMargin =
      ScionUtil.getPropertyOrEnv(
          Constants.PROPERTY_PATH_EXPIRY_MARGIN,
          Constants.ENV_PATH_EXPIRY_MARGIN,
          Constants.DEFAULT_PATH_EXPIRY_MARGIN);
  private int cfgTrafficClass;
  private Consumer<Scmp.Message> errorListener;

  protected AbstractDatagramChannel(ScionService service) throws IOException {
    this(service, DatagramChannel.open());
  }

  protected AbstractDatagramChannel(
      ScionService service, java.nio.channels.DatagramChannel channel) {
    this.channel = channel;
    this.service = service;
    this.bufferReceive = ByteBuffer.allocateDirect(2000);
    this.bufferSend = ByteBuffer.allocateDirect(2000);
  }

  protected void configureBlocking(boolean block) throws IOException {
    synchronized (stateLock) {
      channel.configureBlocking(block);
    }
  }

  // `protected` because it should not be visible in ScmpChannel API.
  protected boolean isBlocking() {
    synchronized (stateLock) {
      return channel.isBlocking();
    }
  }

  public PathPolicy getPathPolicy() {
    synchronized (stateLock) {
      return this.pathPolicy;
    }
  }

  /**
   * Set the path policy. The default path policy is set in {@link PathPolicy#DEFAULT} If the
   * channel is connected, this method will request a new path using the new policy.
   *
   * <p>After initially setting the path policy, it is used to request a new path during write() and
   * send() whenever a path turns out to be close to expiration.
   *
   * @param pathPolicy the new path policy
   * @see PathPolicy#DEFAULT
   */
  public void setPathPolicy(PathPolicy pathPolicy) throws IOException {
    synchronized (stateLock) {
      this.pathPolicy = pathPolicy;
      if (isConnected()) {
        connectionPath = pathPolicy.filter(getOrCreateService().getPaths(connectionPath));
        updateConnection(connectionPath, true);
      }
    }
  }

  public ScionService getOrCreateService() {
    synchronized (stateLock) {
      if (service == null) {
        service = ScionService.defaultService();
      }
      return this.service;
    }
  }

  public ScionService getService() {
    synchronized (stateLock) {
      return this.service;
    }
  }

  protected DatagramChannel channel() {
    synchronized (stateLock) {
      return channel;
    }
  }

  @SuppressWarnings("unchecked")
  public C bind(InetSocketAddress address) throws IOException {
    synchronized (stateLock) {
      channel.bind(address);
      localAddress = ((InetSocketAddress) channel.getLocalAddress()).getAddress();
      return (C) this;
    }
  }

  private void ensureBound() throws IOException {
    synchronized (stateLock) {
      if (localAddress == null) {
        bind(null);
      }
    }
  }

  /**
   * Returns the local address. Note that this may change as the path changes, e.g. if we connect to
   * a new border router on a different network interface.
   *
   * @see DatagramChannel#getLocalAddress()
   * @return The local address.
   * @throws IOException If an I/O error occurs
   */
  public InetSocketAddress getLocalAddress() throws IOException {
    synchronized (stateLock) {
      if (localAddress == null) {
        return null;
      }
      int port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
      return new InetSocketAddress(localAddress, port);
    }
  }

  public SocketAddress getRemoteAddress() throws UnknownHostException {
    Path path = getConnectionPath();
    if (path != null) {
      InetAddress ip = InetAddress.getByAddress(path.getDestinationAddress());
      return new InetSocketAddress(ip, path.getDestinationPort());
    }
    return null;
  }

  public void disconnect() throws IOException {
    synchronized (stateLock) {
      channel.disconnect();
      connectionPath = null;
    }
  }

  public boolean isOpen() {
    synchronized (stateLock) {
      return channel.isOpen();
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (stateLock) {
      channel.disconnect();
      channel.close();
      connectionPath = null;
    }
  }

  /**
   * Connect to a destination host. Note: - A SCION channel will internally connect to the next
   * border router (first hop) instead of the remote host.
   *
   * <p>NB: This method does internally no call {@link java.nio.channels.DatagramChannel}.connect(),
   * instead it calls bind(). That means this method does NOT perform any additional security checks
   * associated with connect(), only those associated with bind().
   *
   * @param addr Address of remote host.
   * @return This channel.
   * @throws IOException for example when the first hop (border router) cannot be connected.
   */
  public C connect(SocketAddress addr) throws IOException {
    synchronized (stateLock) {
      checkConnected(false);
      if (!(addr instanceof InetSocketAddress)) {
        throw new IllegalArgumentException(
            "connect() requires an InetSocketAddress or a ScionSocketAddress.");
      }
      return connect(pathPolicy.filter(getOrCreateService().getPaths((InetSocketAddress) addr)));
    }
  }

  /**
   * Connect to a destination host. Note:<br>
   * - A SCION channel will internally connect to the next border router (first hop) instead of the
   * remote host. <br>
   * - The path will be replaced with a new path once it is expired.<br>
   *
   * <p>NB: This method does internally not call {@link
   * java.nio.channels.DatagramChannel}.connect(). That means this method does NOT perform any
   * additional security checks associated with connect(). It will however perform a `bind(null)`
   * unless the channel is already bound.
   *
   * <p>"connect()" is understood to provide connect to a destination address (IP+port).<br>
   * - send()ing packet to another destination will cause an Exception.<br>
   * - packets received from a different destination will be dropped.<br>
   * - connecting to a given Path only connects to the destination address, the path (route) itself
   * may change, i.e. different border routers may be used.
   *
   * @param path Path to the remote host.
   * @return This channel.
   * @throws IOException for example when the first hop (border router) cannot be connected.
   */
  @SuppressWarnings("unchecked")
  public C connect(RequestPath path) throws IOException {
    // For reference: Java DatagramChannel behavior:
    // - fresh channel has getLocalAddress() == null
    // - connect() and send() cause internal bind()
    //   -> bind() after connect() or send() causes AlreadyBoundException
    //   - send(), receive() and bind(null) bind to ANY
    // - connect() and bind() have lock conflict with concurrent call to receiver()
    // - connect() after bind() is fine, but it changes the local address from ANY to specific IF

    // We have two manage two connection states, internal (state of the internallly used channel)
    // and external (as reported to API users).

    // Externally, for an API user:
    // Our policy is that a connection only determines the _address_ of the remote host,
    // the _route_ to the remote host (i.e. border routers) may change.
    //
    // Internally:
    // However, internally, we do _not_ connect() to the first hop.
    // Usually, connections prevent receiving/sending packets to other IPs.
    // Since this IP (which is the first hop) may change, which would require us to disconnect() and
    // re-connect(), which causes two problems:
    // a) connect() may block infinitely if a receive() is in progress and
    // b) disconnect() sets the local port to 0
    //    (before JDK 14, see https://bugs.openjdk.org/browse/JDK-8231880).
    //
    // Again, externally:
    // We still need to make getLocalAddress() return a local IP after connect() so
    // we call bind(null). We have to do it here, and not lazily during getLocalAddress(),
    // because bind() may block when a concurrent receive() is on progress.
    synchronized (stateLock) {
      checkConnected(false);
      ensureBound();
      updateConnection(path, false);
      return (C) this;
    }
  }

  /**
   * Get the currently connected path. The connected path is set during {@link
   * #connect(RequestPath)} and may be refreshed when expired.
   *
   * @return the current Path or `null` if not path is connected.
   */
  public Path getConnectionPath() {
    synchronized (stateLock) {
      return connectionPath;
    }
  }

  protected ResponsePath receiveFromChannel(
      ByteBuffer buffer, InternalConstants.HdrTypes expectedHdrType) throws IOException {
    ensureBound();
    while (true) {
      buffer.clear();
      InetSocketAddress srcAddress = (InetSocketAddress) channel.receive(buffer);
      if (srcAddress == null) {
        // this indicates nothing is available - non-blocking mode
        return null;
      }
      buffer.flip();

      if (!validate(buffer.asReadOnlyBuffer())) {
        continue;
      }

      InternalConstants.HdrTypes hdrType = ScionHeaderParser.extractNextHeader(buffer);
      // From here on we use linear reading using the buffer's position() mechanism
      buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
      // Check for extension headers.
      // This should be mostly unnecessary, however we sometimes saw SCMP error headers wrapped
      // in extensions headers.
      hdrType = receiveExtensionHeader(buffer, hdrType);

      ResponsePath path = ScionHeaderParser.extractResponsePath(buffer, srcAddress);
      if (hdrType == expectedHdrType) {
        return path;
      }
      receiveScmp(buffer, path);
    }
  }

  protected InternalConstants.HdrTypes receiveExtensionHeader(
      ByteBuffer buffer, InternalConstants.HdrTypes hdrType) {
    if (hdrType == InternalConstants.HdrTypes.END_TO_END
        || hdrType == InternalConstants.HdrTypes.HOP_BY_HOP) {
      ExtensionHeader extHdr = ExtensionHeader.consume(buffer);
      // Currently we are not doing much here except hoping for an SCMP header
      hdrType = extHdr.nextHdr();
      if (hdrType != InternalConstants.HdrTypes.SCMP) {
        throw new UnsupportedOperationException("Extension header not supported: " + hdrType);
      }
    }
    return hdrType;
  }

  protected void receiveScmp(ByteBuffer buffer, Path path) {
    Scmp.Type type = ScmpParser.extractType(buffer);
    Scmp.Message msg = Scmp.createMessage(type, path);
    ScmpParser.consume(buffer, msg);
    checkListeners(msg);
  }

  protected void checkListeners(Scmp.Message scmpMsg) {
    synchronized (stateLock) {
      if (errorListener != null && scmpMsg.getTypeCode().isError()) {
        errorListener.accept(scmpMsg);
      }
    }
  }

  protected void sendRaw(ByteBuffer buffer, InetSocketAddress address) throws IOException {
    channel.send(buffer, address);
  }

  public Consumer<Scmp.Message> setScmpErrorListener(Consumer<Scmp.Message> listener) {
    synchronized (stateLock) {
      Consumer<Scmp.Message> old = errorListener;
      errorListener = listener;
      return old;
    }
  }

  protected void checkOpen() throws ClosedChannelException {
    synchronized (stateLock) {
      if (!channel.isOpen()) {
        throw new ClosedChannelException();
      }
    }
  }

  protected void checkConnected(boolean requiredState) {
    synchronized (stateLock) {
      boolean isConnected = connectionPath != null;
      if (requiredState != isConnected) {
        if (isConnected) {
          throw new AlreadyConnectedException();
        } else {
          throw new NotYetConnectedException();
        }
      }
    }
  }

  public boolean isConnected() {
    synchronized (stateLock) {
      return connectionPath != null;
    }
  }

  @SuppressWarnings({"unchecked", "deprecation"})
  public <T> T getOption(SocketOption<T> option) throws IOException {
    checkOpen();
    synchronized (stateLock) {
      if (option instanceof ScionSocketOptions.SciSocketOption) {
        if (ScionSocketOptions.SN_API_THROW_PARSER_FAILURE.equals(option)) {
          return (T) (Boolean) cfgReportFailedValidation;
        } else if (ScionSocketOptions.SN_PATH_EXPIRY_MARGIN.equals(option)) {
          return (T) (Integer) cfgExpirationSafetyMargin;
        } else if (ScionSocketOptions.SN_TRAFFIC_CLASS.equals(option)) {
          return (T) (Integer) cfgTrafficClass;
        } else {
          throw new UnsupportedOperationException();
        }
      }

      if (StandardSocketOptions.SO_BROADCAST.equals(option)) {
        throw new UnsupportedOperationException();
      }
      return channel.getOption(option);
    }
  }

  @SuppressWarnings({"unchecked", "deprecation"})
  public <T> C setOption(SocketOption<T> option, T t) throws IOException {
    checkOpen();
    synchronized (stateLock) {
      if (option instanceof ScionSocketOptions.SciSocketOption) {
        if (ScionSocketOptions.SN_API_THROW_PARSER_FAILURE.equals(option)) {
          cfgReportFailedValidation = (Boolean) t;
        } else if (ScionSocketOptions.SN_PATH_EXPIRY_MARGIN.equals(option)) {
          cfgExpirationSafetyMargin = (Integer) t;
        } else if (ScionSocketOptions.SN_TRAFFIC_CLASS.equals(option)) {
          int trafficClass = (Integer) t;
          if (trafficClass < 0 || trafficClass > 255) {
            throw new IllegalArgumentException("trafficClass is not in range 0 -- 255");
          }
          cfgTrafficClass = trafficClass;
        } else {
          throw new UnsupportedOperationException();
        }
      } else {
        if (StandardSocketOptions.SO_BROADCAST.equals(option)) {
          throw new UnsupportedOperationException();
        }
        channel.setOption(option, t);
      }
      return (C) this;
    }
  }

  protected final ByteBuffer bufferSend() {
    return bufferSend;
  }

  protected final ByteBuffer bufferReceive() {
    return bufferReceive;
  }

  private void resizeBuffers(InetAddress address) throws SocketException {
    int mtu;
    mtu = NetworkInterface.getByInetAddress(address).getMTU();
    readLock().lock();
    try {
      if (bufferReceive.capacity() < mtu) {
        bufferReceive = ByteBuffer.allocateDirect(mtu);
      }
    } finally {
      readLock().unlock();
    }

    writeLock().lock();
    try {
      if (bufferSend.capacity() < mtu) {
        bufferSend = ByteBuffer.allocateDirect(mtu);
      }
    } finally {
      writeLock().unlock();
    }
  }

  /**
   * @param path path
   * @param payloadLength payload length
   * @return argument path or a new path if the argument path was expired
   * @throws IOException in case of IOException.
   */
  protected Path checkPathAndBuildHeader(
      ByteBuffer buffer, Path path, int payloadLength, InternalConstants.HdrTypes hdrType)
      throws IOException {
    synchronized (stateLock) {
      if (path instanceof RequestPath) {
        path = ensureUpToDate((RequestPath) path);
      }
      buildHeader(buffer, path, payloadLength, hdrType);
      return path;
    }
  }

  /**
   * @param buffer The output buffer
   * @param path path
   * @param payloadLength payload length
   * @param hdrType Header type e.g. SCMP
   * @throws IOException in case of IOException.
   */
  protected void buildHeader(
      ByteBuffer buffer, Path path, int payloadLength, InternalConstants.HdrTypes hdrType)
      throws IOException {
    synchronized (stateLock) {
      ensureBound();
      buffer.clear();
      long srcIA;
      byte[] srcAddress;
      int srcPort;
      if (path instanceof ResponsePath) {
        // We could get source IA, address and port locally, but it seems cleaner
        // to get these from the inverted header.
        ResponsePath rPath = (ResponsePath) path;
        srcIA = rPath.getSourceIsdAs();
        srcAddress = rPath.getSourceAddress();
        srcPort = rPath.getSourcePort();
      } else {
        srcIA = getOrCreateService().getLocalIsdAs();
        // Get external host address. This must be done *after* refreshing the path!
        if (localAddress.isAnyLocalAddress()) {
          // For sending request path we need to have a valid local external address.
          // If the local address is a wildcard address then we get the external IP
          // elsewhere (from the service).

          // TODO cache this or add it to path object?
          srcAddress = getOrCreateService().getExternalIP(path.getFirstHopAddress()).getAddress();
        } else {
          srcAddress = localAddress.getAddress();
        }
        srcPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        if (srcPort == 0) {
          // This has apparently been fixed in Java 14: https://bugs.openjdk.org/browse/JDK-8231880
          throw new IllegalStateException(
              "Local port is 0. This happens after calling "
                  + "disconnect(). Please connect() or bind() before send() or write().");
        }
      }

      long dstIA = path.getDestinationIsdAs();
      byte[] dstAddress = path.getDestinationAddress();
      int dstPort = path.getDestinationPort();

      byte[] rawPath = path.getRawPath();
      ScionHeaderParser.write(
          buffer,
          payloadLength,
          rawPath.length,
          srcIA,
          srcAddress,
          dstIA,
          dstAddress,
          hdrType,
          cfgTrafficClass);
      ScionHeaderParser.writePath(buffer, rawPath);

      if (hdrType == InternalConstants.HdrTypes.UDP) {
        ScionHeaderParser.writeUdpOverlayHeader(buffer, payloadLength, srcPort, dstPort);
      }
    }
  }

  protected RequestPath ensureUpToDate(RequestPath path) throws IOException {
    synchronized (stateLock) {
      if (Instant.now().getEpochSecond() + cfgExpirationSafetyMargin <= path.getExpiration()) {
        return path;
      }
      // expired, get new path
      RequestPath newPath = pathPolicy.filter(getOrCreateService().getPaths(path));
      if (isConnected()) {
        updateConnection(newPath, true);
      }
      return newPath;
    }
  }

  private void updateConnection(RequestPath newPath, boolean mustBeConnected) throws IOException {
    if (mustBeConnected && !isConnected()) {
      throw new IllegalStateException();
    }
    // update connected path
    connectionPath = newPath;
    // update local address
    InetAddress oldLocalAddress = localAddress;
    // TODO we should not change the local address if bind() was called with an explicit address!
    // API: returning the localAddress should return non-ANY if we have a connection
    //     I.e. getExternalIP() is fine if we have a connection.
    //     It is NOT fine if we are bound to an explicit IP/port
    localAddress = getOrCreateService().getExternalIP(newPath.getFirstHopAddress());
    if (!Objects.equals(localAddress, oldLocalAddress)) {
      resizeBuffers(localAddress);
    }
  }

  protected boolean validate(ByteBuffer buffer) throws ScionException {
    synchronized (stateLock) {
      String validationResult = ScionHeaderParser.validate(buffer.asReadOnlyBuffer());
      if (validationResult != null && cfgReportFailedValidation) {
        throw new ScionException(validationResult);
      }
      return validationResult == null;
    }
  }

  protected ReentrantLock readLock() {
    return this.readLock;
  }

  protected ReentrantLock writeLock() {
    return writeLock;
  }
}
