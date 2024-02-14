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
import java.util.function.Consumer;
import org.scion.internal.ExtensionHeader;
import org.scion.internal.InternalConstants;
import org.scion.internal.ScionHeaderParser;
import org.scion.internal.ScmpParser;

abstract class AbstractDatagramChannel<C extends AbstractDatagramChannel<?>> implements Closeable {

  private final java.nio.channels.DatagramChannel channel;
  private boolean isConnected = false;
  private InetSocketAddress connection;
  private RequestPath path;
  private boolean cfgReportFailedValidation = false;
  private PathPolicy pathPolicy = PathPolicy.DEFAULT;
  private ScionService service;
  private int cfgExpirationSafetyMargin =
      ScionUtil.getPropertyOrEnv(
          Constants.PROPERTY_PATH_EXPIRY_MARGIN,
          Constants.ENV_PATH_EXPIRY_MARGIN,
          Constants.DEFAULT_PATH_EXPIRY_MARGIN);

  private Consumer<Scmp.EchoResult> pingListener;
  private Consumer<Scmp.TracerouteResult> traceListener;
  private Consumer<Scmp.Message> errorListener;

  protected AbstractDatagramChannel(ScionService service) throws IOException {
    this.channel = java.nio.channels.DatagramChannel.open();
    this.service = service;
  }

  protected synchronized void configureBlocking(boolean block) throws IOException {
    channel.configureBlocking(block);
  }

  protected synchronized boolean isBlocking() {
    return channel.isBlocking();
  }

  /**
   * Set the path policy. The default path policy is set in PathPolicy.DEFAULT, which currently
   * means to use the first path returned by the daemon or control service. If the channel is
   * connected, this method will request a new path using the new policy.
   *
   * @param pathPolicy the new path policy
   * @see PathPolicy#DEFAULT
   */
  public synchronized void setPathPolicy(PathPolicy pathPolicy) throws IOException {
    this.pathPolicy = pathPolicy;
    if (path != null) {
      updatePath(path);
    }
  }

  public synchronized PathPolicy getPathPolicy() {
    return this.pathPolicy;
  }

  public synchronized void setService(ScionService service) {
    this.service = service;
  }

  public synchronized ScionService getService() {
    if (service == null) {
      service = Scion.defaultService();
    }
    return this.service;
  }

  protected DatagramChannel channel() {
    return channel;
  }

  @SuppressWarnings("unchecked")
  public synchronized C bind(InetSocketAddress address) throws IOException {
    channel.bind(address);
    return (C) this;
  }

  public synchronized InetSocketAddress getLocalAddress() throws IOException {
    return (InetSocketAddress) channel.getLocalAddress();
  }

  public synchronized void disconnect() throws IOException {
    channel.disconnect();
    connection = null;
    isConnected = false;
    path = null;
  }

  public synchronized boolean isOpen() {
    return channel.isOpen();
  }

  @Override
  // TODO not synchronized yet, think about a more fine grained lock (see JDK Channel impl)
  public void close() throws IOException {
    channel.disconnect();
    channel.close();
    isConnected = false;
    connection = null;
    path = null;
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
  public synchronized C connect(SocketAddress addr) throws IOException {
    checkConnected(false);
    if (!(addr instanceof InetSocketAddress)) {
      throw new IllegalArgumentException(
          "connect() requires an InetSocketAddress or a ScionSocketAddress.");
    }
    return connect(pathPolicy.filter(getService().getPaths((InetSocketAddress) addr)));
  }

  /**
   * Connect to a destination host. Note: - A SCION channel will internally connect to the next
   * border router (first hop) instead of the remote host. - The path will be replaced with a new
   * path once it is expired.
   *
   * <p>NB: This method does internally no call {@link java.nio.channels.DatagramChannel}.connect(),
   * instead it calls bind(). That means this method does NOT perform any additional security checks
   * associated with connect(), only those associated with bind().
   *
   * @param path Path to the remote host.
   * @return This channel.
   * @throws IOException for example when the first hop (border router) cannot be connected.
   */
  @SuppressWarnings("unchecked")
  public synchronized C connect(RequestPath path) throws IOException {
    checkConnected(false);
    this.path = path;
    isConnected = true;
    connection = path.getFirstHopAddress();
    channel.connect(connection);
    return (C) this;
  }

  /**
   * Get the currently connected path.
   *
   * @return the current Path or `null` if not path is connected.
   */
  public synchronized Path getCurrentPath() {
    return path;
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

      String validationResult = ScionHeaderParser.validate(buffer.asReadOnlyBuffer());
      if (validationResult != null) {
        if (cfgReportFailedValidation) {
          throw new ScionException(validationResult);
        }
        continue;
      }

      InternalConstants.HdrTypes hdrType = ScionHeaderParser.extractNextHeader(buffer);
      if (expectedHdrType == hdrType && hdrType == InternalConstants.HdrTypes.UDP) {
        return ScionHeaderParser.extractResponsePath(buffer, srcAddress);
      }

      // From here on we use linear reading using the buffer's position() mechanism
      buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
      if (hdrType == InternalConstants.HdrTypes.END_TO_END
          || hdrType == InternalConstants.HdrTypes.HOP_BY_HOP) {
        ExtensionHeader extHdr = ExtensionHeader.consume(buffer);
        // Currently we are not doing much here except hoping for an SCMP header
        hdrType = extHdr.nextHdr();
        if (hdrType != InternalConstants.HdrTypes.SCMP) {
          throw new UnsupportedOperationException("Extension header not supported: " + hdrType);
        }
      }

      if (hdrType == expectedHdrType) {
        return ScionHeaderParser.extractResponsePath(buffer, srcAddress);
      }
      receiveScmp(buffer, path);
    }
  }

  private Object receiveNonDataPacket(
      ByteBuffer buffer, InternalConstants.HdrTypes hdrType, ResponsePath path)
      throws ScionException {
    switch (hdrType) {
      case HOP_BY_HOP:
      case END_TO_END:
        return receiveExtension(buffer, path);
      case SCMP:
        return receiveScmp(buffer, path);
      default:
        if (cfgReportFailedValidation) {
          throw new ScionException("Unknown nextHdr: " + hdrType);
        }
    }
    return null;
  }

  private Object receiveExtension(ByteBuffer buffer, ResponsePath path) throws ScionException {
    ExtensionHeader extHdr = ExtensionHeader.consume(buffer);
    // Currently we are not doing much here except hoping for an SCMP header
    return receiveNonDataPacket(buffer, extHdr.nextHdr(), path);
  }

  private Scmp.Message receiveScmp(ByteBuffer buffer, Path path) {
    Scmp.Message scmpMsg = ScmpParser.consume(buffer, path);
    checkListeners(scmpMsg);
    return scmpMsg;
  }

  protected void checkListeners(Scmp.Message scmpMsg) {
    if (scmpMsg instanceof Scmp.EchoResult && !scmpMsg.getTypeCode().isError()) {
      if (pingListener != null) {
        pingListener.accept((Scmp.EchoResult) scmpMsg);
      }
    } else if (scmpMsg instanceof Scmp.TracerouteResult && !scmpMsg.getTypeCode().isError()) {
      if (traceListener != null) {
        traceListener.accept((Scmp.TracerouteResult) scmpMsg);
      }
    } else {
      if (errorListener != null) {
        errorListener.accept(scmpMsg);
      }
    }
  }

  void sendRaw(ByteBuffer buffer, InetSocketAddress address) throws IOException {
    channel.send(buffer, address);
  }

  public synchronized Consumer<Scmp.EchoResult> setEchoListener(
      Consumer<Scmp.EchoResult> listener) {
    Consumer<Scmp.EchoResult> old = pingListener;
    pingListener = listener;
    return old;
  }

  public synchronized Consumer<Scmp.TracerouteResult> setTracerouteListener(
      Consumer<Scmp.TracerouteResult> listener) {
    Consumer<Scmp.TracerouteResult> old = traceListener;
    traceListener = listener;
    return old;
  }

  public synchronized Consumer<Scmp.Message> setScmpErrorListener(Consumer<Scmp.Message> listener) {
    Consumer<Scmp.Message> old = errorListener;
    errorListener = listener;
    return old;
  }

  protected void checkOpen() throws ClosedChannelException {
    if (!channel.isOpen()) {
      throw new ClosedChannelException();
    }
  }

  protected void checkConnected(boolean requiredState) {
    if (requiredState != isConnected) {
      if (isConnected) {
        throw new AlreadyConnectedException();
      } else {
        throw new NotYetConnectedException();
      }
    }
  }

  public synchronized boolean isConnected() {
    return isConnected;
  }

  @SuppressWarnings("unchecked")
  public synchronized <T> T getOption(SocketOption<T> option) throws IOException {
    if (option instanceof ScionSocketOptions.SciSocketOption) {
      if (ScionSocketOptions.SN_API_THROW_PARSER_FAILURE.equals(option)) {
        return (T) (Boolean) cfgReportFailedValidation;
      } else if (ScionSocketOptions.SN_PATH_EXPIRY_MARGIN.equals(option)) {
        return (T) (Integer) cfgExpirationSafetyMargin;
      } else {
        throw new UnsupportedOperationException();
      }
    }
    return channel.getOption(option);
  }

  @SuppressWarnings("unchecked")
  public synchronized <T> C setOption(SocketOption<T> option, T t) throws IOException {
    if (option instanceof ScionSocketOptions.SciSocketOption) {
      if (ScionSocketOptions.SN_API_THROW_PARSER_FAILURE.equals(option)) {
        cfgReportFailedValidation = (Boolean) t;
      } else if (ScionSocketOptions.SN_PATH_EXPIRY_MARGIN.equals(option)) {
        cfgExpirationSafetyMargin = (Integer) t;
      } else {
        throw new UnsupportedOperationException();
      }
    } else if (StandardSocketOptions.SO_RCVBUF.equals(option)) {
      // TODO resize buf
      channel.setOption(option, t);
    } else if (StandardSocketOptions.SO_SNDBUF.equals(option)) {
      // TODO resize buf
      channel.setOption(option, t);
    } else {
      channel.setOption(option, t);
    }
    return (C) this;
  }

  /**
   * @param path path
   * @param payloadLength payload length
   * @return argument path or a new path if the argument path was expired
   * @throws IOException in case of IOException.
   */
  protected Path buildHeader(
      ByteBuffer buffer, Path path, int payloadLength, InternalConstants.HdrTypes hdrType)
      throws IOException {
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
      // For sending request path we need to have a valid local external address.
      // For a valid local external address we need to be connected.
      if (!isConnected) {
        isConnected = true;
        connection = path.getFirstHopAddress();
        channel.connect(connection);
      }

      // check path expiration
      path = ensureUpToDate((RequestPath) path);
      srcIA = getService().getLocalIsdAs();
      // Get external host address. This must be done *after* refreshing the path!
      InetSocketAddress srcSocketAddress = (InetSocketAddress) channel.getLocalAddress();
      srcAddress = srcSocketAddress.getAddress().getAddress();
      srcPort = srcSocketAddress.getPort();
    }

    long dstIA = path.getDestinationIsdAs();
    byte[] dstAddress = path.getDestinationAddress();
    int dstPort = path.getDestinationPort();

    byte[] rawPath = path.getRawPath();
    ScionHeaderParser.write(
        buffer, payloadLength, rawPath.length, srcIA, srcAddress, dstIA, dstAddress, hdrType);
    ScionHeaderParser.writePath(buffer, rawPath);

    // TODO move this outside of this method
    if (hdrType == InternalConstants.HdrTypes.UDP) {
      ScionHeaderParser.writeUdpOverlayHeader(buffer, payloadLength, srcPort, dstPort);
    }

    return path;
  }

  private Path ensureUpToDate(RequestPath path) throws IOException {
    if (Instant.now().getEpochSecond() + cfgExpirationSafetyMargin <= path.getExpiration()) {
      return path;
    }
    return updatePath(path);
  }

  private Path updatePath(RequestPath path) throws IOException {
    // expired, get new path
    RequestPath newPath = pathPolicy.filter(getService().getPaths(path));

    if (isConnected) { // equal to !isBound at this point
      if (!newPath.getFirstHopAddress().equals(this.connection)) {
        // TODO only reconnect if firstHop is on different interface....?!
        channel.disconnect();
        this.connection = newPath.getFirstHopAddress();
        channel.connect(this.connection);
      }
      this.path = newPath;
    }
    return newPath;
  }
}
