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

package org.scion.jpan;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.scion.jpan.internal.*;

abstract class AbstractDatagramChannel<C extends AbstractDatagramChannel<?>> implements Closeable {

  protected static final int DEFAULT_BUFFER_SIZE = 2000;
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
  private boolean isBoundToAddress = false;
  private boolean cfgReportFailedValidation = false;
  private PathPolicy pathPolicy = PathPolicy.DEFAULT;
  private final ScionService service;
  private int cfgExpirationSafetyMargin =
      ScionUtil.getPropertyOrEnv(
          Constants.PROPERTY_PATH_EXPIRY_MARGIN,
          Constants.ENV_PATH_EXPIRY_MARGIN,
          Constants.DEFAULT_PATH_EXPIRY_MARGIN);
  private int cfgTrafficClass;
  private Consumer<Scmp.ErrorMessage> errorListener;
  private boolean cfgRemoteDispatcher = false;
  private InetSocketAddress overrideExternalAddress = null;
  private NatMapping natMapping = null;

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

  public boolean isBlocking() {
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
   * Set the path policy. The default path policy is set in {@link PathPolicy#DEFAULT}. If the
   * channel is connected, this method will request a new path using the new policy.
   *
   * <p>After initially setting the path policy, it is used to request a new path during write() and
   * send() whenever a path turns out to be close to expiration.
   *
   * @param pathPolicy the new path policy
   * @see PathPolicy#DEFAULT
   */
  public void setPathPolicy(PathPolicy pathPolicy) {
    synchronized (stateLock) {
      this.pathPolicy = pathPolicy;
      if (isConnected()) {
        connectionPath = (RequestPath) pathPolicy.filter(getService().getPaths(connectionPath));
        updateConnection(connectionPath, true);
      }
    }
  }

  /**
   * Return the ScionService used by this channel. A ScionService is, for example, required for
   * lookup up a path from a daemon or control server. See also {@link
   * ScionDatagramChannel#open(ScionService)}.
   *
   * @return the service or 'null'.
   */
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
      isBoundToAddress = address != null;
      localAddress = ((InetSocketAddress) channel.getLocalAddress()).getAddress();
      if (service != null) {
        if (natMapping == null) {
          natMapping = getService().getNatMapping(channel);
        }
      }
      return (C) this;
    }
  }

  protected void ensureBound() throws IOException {
    synchronized (stateLock) {
      if (localAddress == null) {
        LocalTopology.DispatcherPortRange ports = getService().getLocalPortRange();
        if (ports.hasPortRange()) {
          // This is a bit ugly, we iterate through all ports to find a free one.
          int min = ports.getPortMin();
          int max = ports.getPortMax();
          for (int port = min; port <= max; port++) {
            try {
              bind(new InetSocketAddress(port));
              return;
            } catch (IOException e) {
              // ignore and try next port
            }
          }
          throw new IOException("No free port found in SCION port range: " + min + "-" + max);
        } else {
          bind(null);
        }
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

  /**
   * Returns the remote address.
   *
   * @return The remote address or 'null' if this channel is not connected.
   * @see DatagramChannel#getRemoteAddress()
   * @see #connect(SocketAddress)
   * @see #connect(Path)
   * @throws IOException If an I/O error occurs
   */
  public InetSocketAddress getRemoteAddress() throws IOException {
    Path path = getConnectionPath();
    if (path != null) {
      return new InetSocketAddress(path.getRemoteAddress(), path.getRemotePort());
    }
    return null;
  }

  public void disconnect() throws IOException {
    synchronized (stateLock) {
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
   * Connect to a destination host.
   *
   * <p>NB: A SCION channel will internally connect to the next border router (first hop) instead of
   * the remote host. <br>
   * If the address is an instance of {@link ScionSocketAddress} then connect will use the path
   * associated with the address, see {@link #connect(Path)}.
   *
   * <p>NB: This method does internally not call {@link
   * java.nio.channels.DatagramChannel}.connect(), instead it calls bind(). That means this method
   * does NOT perform any additional security checks associated with connect(), only those
   * associated with bind().
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
      if (addr instanceof ScionSocketAddress) {
        return connect(((ScionSocketAddress) addr).getPath());
      }
      Path path = getService().lookupAndGetPath((InetSocketAddress) addr, pathPolicy);
      return connect(path);
    }
  }

  /**
   * Connect to a destination host. Note:<br>
   * - A SCION channel will internally connect to the next border router (first hop) instead of the
   * remote host. <br>
   * - The path will be replaced with a new path once it is expired.<br>
   *
   * <p>NB: This method does internally not call {@link
   * java.nio.channels.DatagramChannel#connect(SocketAddress)}. That means this method does NOT
   * perform any additional security checks associated with connect(). It will however perform a
   * `bind(null)` unless the channel is already bound.
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
  public C connect(Path path) throws IOException {
    if (!(path instanceof RequestPath)) {
      // Technically we could probably allow this, but it feels like an abuse of the API,
      throw new IllegalStateException("The path must be a request path.");
    }
    // For reference: Java DatagramChannel behavior:
    // - fresh channel has getLocalAddress() == null
    // - connect() and send() cause internal bind()
    //   -> bind() after connect() or send() causes AlreadyBoundException
    //   - send(), receive() and bind(null) bind to ANY
    // - connect() and bind() have lock conflict with concurrent call to receiver()
    // - connect() after bind() is fine, but it changes the local address from ANY to specific IF

    // We have two manage two connection states, internal (state of the internally used channel)
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
      if (localAddress.isAnyLocalAddress()) {
        // Do we really need this?
        // - It ensures that after connect we have a proper local address for getLocalAddress(),
        //   this is what connect() should do.
        // - It allows us to have an ANY address underneath which could help with interface
        //   switching.
        localAddress = getService().getExternalIP(path);
      }
      updateConnection((RequestPath) path, false);
      return (C) this;
    }
  }

  /**
   * Get the currently connected path. The connected path is set during {@link #connect(Path)} and
   * may be refreshed when expired.
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

      InetSocketAddress firstHopAddress = getFirstHopAddress(buffer, srcAddress);
      ResponsePath path = ScionHeaderParser.extractResponsePath(buffer, firstHopAddress);
      if (hdrType == expectedHdrType) {
        return path;
      }
      // Must be an error...
      receiveScmp(buffer, path);
    }
  }

  protected InetSocketAddress getFirstHopAddress(ByteBuffer buffer, InetSocketAddress srcAddress) {
    if (getService() != null) {
      int oldPos = buffer.position();
      int pathPos = ScionHeaderParser.extractPathHeaderPosition(buffer);
      // If we have a path we need to use it to get the return address.
      // If we don't have a path (local AS) we need to use the underlay IP address.
      if (pathPos > 0) {
        buffer.position(pathPos);
        int segCount = PathRawParserLight.extractSegmentCount(buffer);
        buffer.position(pathPos + 4 + segCount * 8);
        int interfaceId1 = PathRawParserLight.extractHopFieldEgress(buffer, segCount, 0);
        int interfaceId2 = PathRawParserLight.extractHopFieldIngress(buffer, segCount, 0);
        buffer.position(oldPos);
        int interfaceId = interfaceId2 == 0 ? interfaceId1 : interfaceId2;
        return service.getBorderRouterAddress(interfaceId);
      }
    }
    // With path length == 0 or without ScionService we just use the IP source address.
    return srcAddress;
  }

  protected static InternalConstants.HdrTypes receiveExtensionHeader(
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
        errorListener.accept((Scmp.ErrorMessage) scmpMsg);
      }
    }
  }

  /**
   * Assume that the destination host uses a dispatcher.
   *
   * <p>Calling this method sets an internal flag that forces the destination port of intra-AS
   * packets to be 30041 independent of the UDP-overlay port. This flag has no effect for inter-AS
   * packets or if the overlay port is already 30041.
   *
   * @param hasDispatcher Set to 'true' if remote end-host uses a dispatcher and requires using port
   *     30041.
   * @deprecated Not required anymore, will be removed for 0.5.0
   */
  @Deprecated // TODO remove for 0.5.0
  public void configureRemoteDispatcher(boolean hasDispatcher) {
    this.cfgRemoteDispatcher = hasDispatcher;
  }

  /**
   * This allows overriding the source address in SCION headers. This can be useful when a host is
   * located behind a NAT. The specified source address should in this case be the external address
   * of the NAT.
   *
   * @param address The external source address
   */
  public void setOverrideSourceAddress(InetSocketAddress address) {
    this.overrideExternalAddress = address;
  }

  public InetSocketAddress getOverrideSourceAddress() {
    return this.overrideExternalAddress;
  }

  private InetSocketAddress getSourceAddress(Path path) {
    // Externally visible address
    if (overrideExternalAddress != null) {
      return overrideExternalAddress;
    }
    // TODO we should have a variable that caches getExtrnalIP() or, better
    //   just bind() to getExternal()_after/during getExternbalIP()
    return natMapping.getMappedAddress(path);
  }

  protected int sendRaw(ByteBuffer buffer, Path path) throws IOException {
    // For inter-AS connections we need to send directly to the destination address.
    // We also need to respect the port range and use 30041 when applicable.
    if (getService() != null && path.getRawPath().length == 0) {
      InetSocketAddress remoteHostIP = path.getFirstHopAddress();
      remoteHostIP = getService().getLocalPortRange().mapToLocalPort(remoteHostIP);
      return channel.send(buffer, remoteHostIP);
    }
    if (cfgRemoteDispatcher && path.getRawPath().length == 0) {
      InetAddress remoteHostIP = path.getFirstHopAddress().getAddress();
      return channel.send(buffer, new InetSocketAddress(remoteHostIP, Constants.DISPATCHER_PORT));
    }
    return channel.send(buffer, path.getFirstHopAddress());
  }

  /**
   * The listener will be called for every SCMP error message that is received.
   *
   * @param listener A consumer for error messages. Use 'null' to deregister the listener.
   * @return The previous registered listener, may be 'null'.
   */
  public Consumer<Scmp.ErrorMessage> setScmpErrorListener(Consumer<Scmp.ErrorMessage> listener) {
    synchronized (stateLock) {
      Consumer<Scmp.ErrorMessage> old = errorListener;
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

  @SuppressWarnings("unchecked")
  public <T> T getOption(SocketOption<T> option) throws IOException {
    checkOpen();
    synchronized (stateLock) {
      if (option instanceof ScionSocketOptions.SciSocketOption) {
        if (ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE.equals(option)) {
          return (T) (Boolean) cfgReportFailedValidation;
        } else if (ScionSocketOptions.SCION_PATH_EXPIRY_MARGIN.equals(option)) {
          return (T) (Integer) cfgExpirationSafetyMargin;
        } else if (ScionSocketOptions.SCION_TRAFFIC_CLASS.equals(option)) {
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

  @SuppressWarnings("unchecked")
  public <T> C setOption(SocketOption<T> option, T t) throws IOException {
    checkOpen();
    synchronized (stateLock) {
      if (option instanceof ScionSocketOptions.SciSocketOption) {
        if (ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE.equals(option)) {
          cfgReportFailedValidation = (Boolean) t;
        } else if (ScionSocketOptions.SCION_PATH_EXPIRY_MARGIN.equals(option)) {
          cfgExpirationSafetyMargin = (Integer) t;
        } else if (ScionSocketOptions.SCION_TRAFFIC_CLASS.equals(option)) {
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

  protected int getCfgExpirationSafetyMargin() {
    return cfgExpirationSafetyMargin;
  }

  private void checkLockedForRead() {
    if (!readLock().isLocked()) {
      throw new IllegalStateException("Access must be READ locked!");
    }
  }

  private void checkLockedForWrite() {
    if (!writeLock().isLocked()) {
      throw new IllegalStateException("Access must be WRITE locked!");
    }
  }

  /**
   * @param requiredSize minimum required buffer size
   * @return ByteBuffer usable for sending data.
   */
  protected final ByteBuffer getBufferSend(int requiredSize) {
    checkLockedForWrite();
    if (bufferSend.capacity() < requiredSize) {
      bufferSend = ByteBuffer.allocateDirect(requiredSize);
    }
    return bufferSend;
  }

  /**
   * @param requiredSize minimum required buffer size
   * @return ByteBuffer usable for receiving data.
   */
  protected final ByteBuffer getBufferReceive(int requiredSize) {
    checkLockedForRead();
    if (bufferReceive.capacity() < requiredSize) {
      bufferReceive = ByteBuffer.allocateDirect(requiredSize);
    }
    return bufferReceive;
  }

  /**
   * @param buffer The output buffer
   * @param path path
   * @param payloadLength payload length
   * @param hdrType Header type e.g. SCMP
   * @throws IOException in case of IOException.
   */
  protected void buildHeader(
      ByteBuffer buffer,
      Path path,
      int payloadLength,
      InternalConstants.HdrTypes hdrType,
      ByteUtil.MutInt port)
      throws IOException {
    synchronized (stateLock) {
      // We need to be bound to a local port in order to have a valid local address.
      // This may be necessary for getSourceAddress(), but it is definitely necessary for
      // consistent API behavior that getLocalAddress() should return an address after send().
      ensureBound();
      buffer.clear();
      long srcIsdAs;
      InetAddress srcAddress;
      if (path instanceof ResponsePath) {
        // We get the source ISD/AS and IP from the path because ScionService may be null.
        // Also, we may be behind a NAT, so the path's address is known to be correct.
        ResponsePath rPath = (ResponsePath) path;
        srcIsdAs = rPath.getLocalIsdAs();
        srcAddress = rPath.getLocalAddress();
        port.set(rPath.getLocalPort());
      } else {
        srcIsdAs = getService().getLocalIsdAs();
        InetSocketAddress src = getSourceAddress(path);
        srcAddress = src.getAddress();
        port.set(src.getPort());
      }

      byte[] rawPath = path.getRawPath();
      ScionHeaderParser.write(
          buffer,
          payloadLength,
          rawPath.length,
          srcIsdAs,
          srcAddress.getAddress(),
          path.getRemoteIsdAs(),
          path.getRemoteAddress().getAddress(),
          hdrType,
          cfgTrafficClass);
      ScionHeaderParser.writePath(buffer, rawPath);
    }
  }

  protected void updateConnection(RequestPath newPath, boolean mustBeConnected) {
    if (mustBeConnected && !isConnected()) {
      return;
    }
    // update connected path
    connectionPath = newPath;
    // update local address except if bind() was called with an explicit address!
    if (!isBoundToAddress) {
      // API: returning the localAddress should return non-ANY if we have a connection
      //     I.e. getExternalIP() is fine if we have a connection.
      //     It is NOT fine if we are bound to an explicit IP/port
      InetAddress oldLocalAddress = localAddress;
      // TODO should we do this only if localAddress = null || ANY? Should we set isBound=true?
      localAddress = getService().getExternalIP(newPath);
      if (!Objects.equals(localAddress, oldLocalAddress)) {
        // TODO check CS / bootstrapping
        throw new UnsupportedOperationException();
      }
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

  protected Object stateLock() {
    return stateLock;
  }
}
