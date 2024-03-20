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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.scion.internal.ExtensionHeader;
import org.scion.internal.InternalConstants;
import org.scion.internal.ScionHeaderParser;
import org.scion.internal.ScmpParser;

abstract class AbstractDatagramChannel<C extends AbstractDatagramChannel<?>> implements Closeable {

  private final java.nio.channels.DatagramChannel channel;
  private final Object stateLock = new Object();
  private final ReentrantLock readLock = new ReentrantLock();
  private final ReentrantLock writeLock = new ReentrantLock();

  // This path is only used for write() after connect(), not for send().
  // Whether we have a connectionPath is independent of whether the underlying channel is connected.
  private RequestPath connectionPath;
  private boolean cfgReportFailedValidation = false;
  private PathPolicy pathPolicy = PathPolicy.DEFAULT;
  private ScionService service;
  private int cfgExpirationSafetyMargin =
      ScionUtil.getPropertyOrEnv(
          Constants.PROPERTY_PATH_EXPIRY_MARGIN,
          Constants.ENV_PATH_EXPIRY_MARGIN,
          Constants.DEFAULT_PATH_EXPIRY_MARGIN);
  private Consumer<Scmp.Message> errorListener;

  protected AbstractDatagramChannel(ScionService service) throws IOException {
    this(service, DatagramChannel.open());
  }

  protected AbstractDatagramChannel(
      ScionService service, java.nio.channels.DatagramChannel channel) {
    this.channel = channel;
    this.service = service;
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
      if (connectionPath != null) {
        updatePath(connectionPath);
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
      return (C) this;
    }
  }

  public InetSocketAddress getLocalAddress() throws IOException {
    synchronized (stateLock) {
      return (InetSocketAddress) channel.getLocalAddress();
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
   * java.nio.channels.DatagramChannel}.connect(), instead it calls bind(). That means this method
   * does NOT perform any additional security checks associated with connect(), only those
   * associated with bind().
   *
   * @param path Path to the remote host.
   * @return This channel.
   * @throws IOException for example when the first hop (border router) cannot be connected.
   */
  @SuppressWarnings("unchecked")
  public C connect(RequestPath path) throws IOException {
    synchronized (stateLock) {
      checkConnected(false);
      this.connectionPath = path;
      if (!channel.isConnected()) {
        // We must connect the underlying channel in order to get a local address.
        channel.connect(path.getFirstHopAddress());
      }
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

  protected void setConnectionPath(RequestPath path) {
    synchronized (stateLock) {
      this.connectionPath = path;
    }
  }

  protected ResponsePath receiveFromChannel(
      ByteBuffer buffer, InternalConstants.HdrTypes expectedHdrType) throws IOException {
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
    synchronized (stateLock) {
      if (option instanceof ScionSocketOptions.SciSocketOption) {
        if (ScionSocketOptions.SN_API_THROW_PARSER_FAILURE.equals(option)) {
          return (T) (Boolean) cfgReportFailedValidation;
        } else if (ScionSocketOptions.SN_PATH_EXPIRY_MARGIN.equals(option)) {
          return (T) (Integer) cfgExpirationSafetyMargin;
        } else if (ScionSocketOptions.SN_TRAFFIC_CLASS.equals(option)) {
          throw new UnsupportedOperationException();
        } else {
          throw new UnsupportedOperationException();
        }
      }
      return channel.getOption(option);
    }
  }

  @SuppressWarnings({"unchecked", "deprecation"})
  public <T> C setOption(SocketOption<T> option, T t) throws IOException {
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
          throw new UnsupportedOperationException();
        } else {
          throw new UnsupportedOperationException();
        }
      } else if (StandardSocketOptions.SO_RCVBUF.equals(option)
          || StandardSocketOptions.SO_SNDBUF.equals(option)) {
        channel.setOption(option, t);
        resizeBuffers(
            channel.getOption(StandardSocketOptions.SO_RCVBUF),
            channel.getOption(StandardSocketOptions.SO_SNDBUF));
      } else {
        channel.setOption(option, t);
      }
      return (C) this;
    }
  }

  protected abstract void resizeBuffers(int sizeReceive, int sizeSend);

  /**
   * @param path path
   * @param payloadLength payload length
   * @return argument path or a new path if the argument path was expired
   * @throws IOException in case of IOException.
   */
  protected Path buildHeader(
      ByteBuffer buffer, Path path, int payloadLength, InternalConstants.HdrTypes hdrType)
      throws IOException {
    synchronized (stateLock) {
      if (path instanceof RequestPath) {
        path = ensureUpToDate((RequestPath) path);
      }
      buildHeaderNoRefresh(buffer, path, payloadLength, hdrType);
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
  protected void buildHeaderNoRefresh(
      ByteBuffer buffer, Path path, int payloadLength, InternalConstants.HdrTypes hdrType)
      throws IOException {
    synchronized (stateLock) {
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
        InetSocketAddress srcSocketAddress = (InetSocketAddress) channel.getLocalAddress();
        if (srcSocketAddress == null) {
          // We need to bind in order to have a return port.
          // This is also what the Java channel does internally before send().
          channel.bind(null);
          srcSocketAddress = (InetSocketAddress) channel.getLocalAddress();
        }
        if (srcSocketAddress.getAddress().isAnyLocalAddress()) {
          // For sending request path we need to have a valid local external address.
          // For a valid local external address we need to be connected.
          // TODO use bind() i.o. connect????

//          channel.connect(path.getFirstHopAddress());
//          srcSocketAddress = (InetSocketAddress) channel.getLocalAddress();
//          System.out.println("SEND: SRV " + srcSocketAddress);
//          srcAddress = srcSocketAddress.getAddress().getAddress();

          srcAddress = getOrCreateService().getExternalIP(path.getFirstHopAddress());

          // TODO we should only do this if the path does not alread have a valid IP.
          //   In fact, the should probably done during path creation.
          //          srcSocketAddress = findSrcAddress(path.getFirstHopAddress());
        } else {
          srcAddress = srcSocketAddress.getAddress().getAddress();
        }
        srcPort = srcSocketAddress.getPort();
        if (srcPort == 0) {
          // This has apparently been fixed in Java 14: https://bugs.openjdk.org/browse/JDK-8231880
          // TODO -> maybe we should adopt this fix for the DatagramChannel in Java 8, i.e. rebind!
          throw new IllegalStateException("Local port is 0. This happens after calling " +
                  "disconnect(). Please connect() or bind() before send() or write().");
        }
      }

      long dstIA = path.getDestinationIsdAs();
      byte[] dstAddress = path.getDestinationAddress();
      int dstPort = path.getDestinationPort();

      byte[] rawPath = path.getRawPath();
      ScionHeaderParser.write(
          buffer, payloadLength, rawPath.length, srcIA, srcAddress, dstIA, dstAddress, hdrType);
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
      return updatePath(path);
    }
  }

  private RequestPath updatePath(RequestPath path) throws IOException {
    // expired, get new path
    RequestPath newPath = pathPolicy.filter(getOrCreateService().getPaths(path));

    if (connectionPath != null) { // equal to !isBound at this point
      if (!newPath.getFirstHopAddress().equals(path.getFirstHopAddress())) {
        channel.disconnect();
        channel.connect(newPath.getFirstHopAddress());
      }
      connectionPath = newPath;
    }
    return newPath;
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
