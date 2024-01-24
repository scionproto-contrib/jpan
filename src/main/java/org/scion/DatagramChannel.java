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
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.time.Instant;
import java.util.function.Consumer;
import org.scion.internal.ExtensionHeader;
import org.scion.internal.InternalConstants;
import org.scion.internal.PathHeaderParser;
import org.scion.internal.ScionHeaderParser;
import org.scion.internal.ScmpParser;

public class DatagramChannel implements ByteChannel, Closeable {

  private final java.nio.channels.DatagramChannel channel;
  private boolean isConnected = false;
  private InetSocketAddress connection;
  private RequestPath path;
  private final ByteBuffer buffer = ByteBuffer.allocateDirect(66000);
  private boolean cfgReportFailedValidation = false;
  private PathPolicy pathPolicy = PathPolicy.DEFAULT;
  private ScionService service;
  private int cfgExpirationSafetyMargin =
      ScionUtil.getPropertyOrEnv(
          Constants.PROPERTY_PATH_EXPIRY_MARGIN,
          Constants.ENV_PATH_EXPIRY_MARGIN,
          Constants.DEFAULT_PATH_EXPIRY_MARGIN);

  private Consumer<Scmp.ScmpEcho> pingListener;
  private Consumer<Scmp.ScmpTraceroute> traceListener;
  private Consumer<Scmp.ScmpMessage> errorListener;

  public static DatagramChannel open() throws IOException {
    return new DatagramChannel();
  }

  public static DatagramChannel open(ScionService service) throws IOException {
    return new DatagramChannel(service);
  }

  protected DatagramChannel() throws IOException {
    this.channel = java.nio.channels.DatagramChannel.open();
  }

  protected DatagramChannel(ScionService service) throws IOException {
    this.channel = java.nio.channels.DatagramChannel.open();
    this.service = service;
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

  public synchronized DatagramChannel bind(InetSocketAddress address) throws IOException {
    channel.bind(address);
    return this;
  }

  // TODO we return `void` here. If we implement SelectableChannel
  //  this can be changed to return SelectableChannel.
  public synchronized void configureBlocking(boolean block) throws IOException {
    channel.configureBlocking(block);
  }

  public synchronized boolean isBlocking() {
    return channel.isBlocking();
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

  @Override
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
  public synchronized DatagramChannel connect(SocketAddress addr) throws IOException {
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
  public synchronized DatagramChannel connect(RequestPath path) throws IOException {
    checkConnected(false);
    this.path = path;
    isConnected = true;
    connection = path.getFirstHopAddress();
    channel.connect(connection);
    return this;
  }

  /**
   * Get the currently connected path.
   *
   * @return the current Path or `null` if not path is connected.
   */
  public synchronized Path getCurrentPath() {
    return path;
  }

  public synchronized ResponsePath receive(ByteBuffer userBuffer) throws IOException {
    ResponsePath path = receiveFromChannel(InternalConstants.HdrTypes.UDP);
    if (path == null) {
      return null; // non-blocking, nothing available
    }
    ScionHeaderParser.extractUserPayload(buffer, userBuffer);
    buffer.clear();
    return path;
  }

  synchronized Scmp.ScmpMessage receiveScmp() throws IOException {
    ResponsePath path = receiveFromChannel(InternalConstants.HdrTypes.SCMP);
    if (path == null) {
      return null; // non-blocking, nothing available
    }
    return receiveScmp(path);
  }

  private ResponsePath receiveFromChannel(InternalConstants.HdrTypes expectedHdrType)
      throws IOException {
    while (true) {
      buffer.clear();
      InetSocketAddress srcAddress = (InetSocketAddress) channel.receive(buffer);
      if (srcAddress == null) {
        // this indicates nothing is available - non-blocking mode
        return null;
      }
      buffer.flip();

      String validationResult = ScionHeaderParser.validate(buffer.asReadOnlyBuffer());
      if (validationResult != null && cfgReportFailedValidation) {
        throw new ScionException(validationResult);
      }
      if (validationResult != null) {
        continue;
      }

      InternalConstants.HdrTypes hdrType = ScionHeaderParser.extractNextHeader(buffer);
      if (hdrType == InternalConstants.HdrTypes.UDP && expectedHdrType == hdrType) {
        return ScionHeaderParser.extractRemoteSocketAddress(buffer, srcAddress);
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
        return ScionHeaderParser.extractRemoteSocketAddress(buffer, srcAddress);
      }
      receiveScmp(path);
    }
  }

  private Object receiveNonDataPacket(InternalConstants.HdrTypes hdrType, ResponsePath path)
      throws ScionException {
    switch (hdrType) {
      case HOP_BY_HOP:
      case END_TO_END:
        return receiveExtension(path);
        // break;
      case SCMP:
        return receiveScmp(path);
        // break;
      default:
        if (cfgReportFailedValidation) {
          throw new ScionException("Unknown nextHdr: " + hdrType);
        }
    }
    return null;
  }

  private Object receiveExtension(ResponsePath path) throws ScionException {
    ExtensionHeader extHdr = ExtensionHeader.consume(buffer);
    // Currently we are not doing much here except hoping for an SCMP header
    return receiveNonDataPacket(extHdr.nextHdr(), path);
  }

  private Scmp.ScmpMessage receiveScmp(Path path) {
    Scmp.ScmpMessage scmpMsg = ScmpParser.consume(buffer, path);
    if (scmpMsg instanceof Scmp.ScmpEcho) {
      if (pingListener != null) {
        pingListener.accept((Scmp.ScmpEcho) scmpMsg);
      }
    } else if (scmpMsg instanceof Scmp.ScmpTraceroute) {
      if (traceListener != null) {
        traceListener.accept((Scmp.ScmpTraceroute) scmpMsg);
      }
    } else {
      if (errorListener != null) {
        errorListener.accept(scmpMsg);
      }
    }
    return scmpMsg;
  }

  /**
   * Attempts to send the content of the buffer to the destinationAddress. This method will request
   * a new path for each call.
   *
   * @param srcBuffer Data to send
   * @param destination Destination address. This should contain a host name known to the DNS so
   *     that the ISD/AS information can be retrieved.
   * @throws IOException if an error occurs, e.g. if the destinationAddress is an IP address that
   *     cannot be resolved to an ISD/AS.
   * @see java.nio.channels.DatagramChannel#send(ByteBuffer, SocketAddress)
   */
  public synchronized void send(ByteBuffer srcBuffer, SocketAddress destination)
      throws IOException {
    if (!(destination instanceof InetSocketAddress)) {
      throw new IllegalArgumentException("Address must be of type InetSocketAddress.");
    }
    send(srcBuffer, pathPolicy.filter(getService().getPaths((InetSocketAddress) destination)));
  }

  /**
   * Attempts to send the content of the buffer to the destinationAddress.
   *
   * @param srcBuffer Data to send
   * @param path Path to destination. If this is a Request path and it is expired then it will
   *     automatically be replaced with a new path. Expiration of ResponsePaths is not checked
   * @return either the path argument or a new path if the path was an expired RequestPath. Note
   *     that ResponsePaths are not checked for expiration.
   * @throws IOException if an error occurs, e.g. if the destinationAddress is an IP address that
   *     cannot be resolved to an ISD/AS.
   * @see java.nio.channels.DatagramChannel#send(ByteBuffer, SocketAddress)
   */
  public synchronized Path send(ByteBuffer srcBuffer, Path path) throws IOException {
    // + 8 for UDP overlay header length
    Path actualPath = buildHeader(path, srcBuffer.remaining() + 8, InternalConstants.HdrTypes.UDP);
    buffer.put(srcBuffer);
    buffer.flip();
    channel.send(buffer, actualPath.getFirstHopAddress());
    return actualPath;
  }

  public synchronized void sendEchoRequest(Path path, int sequenceNumber, ByteBuffer data)
      throws IOException {
    // EchoHeader = 8 + data
    int len = 8 + data.remaining();
    Path actualPath = buildHeader(path, len, InternalConstants.HdrTypes.SCMP);
    ScmpParser.buildScmpPing(buffer, getLocalAddress().getPort(), sequenceNumber, data);
    buffer.flip();
    channel.send(buffer, actualPath.getFirstHopAddress());
  }

  void sendTracerouteRequest(Path path, int interfaceNumber, PathHeaderParser.Node node)
      throws IOException {
    // TracerouteHeader = 24
    int len = 24;
    // TODO we are modifying the raw path here, this is bad! It breaks concurrent usage.
    //   we should only modify the outgoing packet.
    byte[] raw = path.getRawPath();
    raw[node.posHopFlags] = node.hopFlags;
    Path actualPath = buildHeader(path, len, InternalConstants.HdrTypes.SCMP);
    ScmpParser.buildScmpTraceroute(buffer, getLocalAddress().getPort(), interfaceNumber);
    buffer.flip();
    channel.send(buffer, actualPath.getFirstHopAddress());
    // Clean up!  // TODO this is really bad!
    raw[node.posHopFlags] = 0;
  }

  public synchronized Consumer<Scmp.ScmpEcho> setEchoListener(Consumer<Scmp.ScmpEcho> listener) {
    Consumer<Scmp.ScmpEcho> old = pingListener;
    pingListener = listener;
    return old;
  }

  public synchronized Consumer<Scmp.ScmpTraceroute> setTracerouteListener(
      Consumer<Scmp.ScmpTraceroute> listener) {
    Consumer<Scmp.ScmpTraceroute> old = traceListener;
    traceListener = listener;
    return old;
  }

  public synchronized Consumer<Scmp.ScmpMessage> setScmpErrorListener(
      Consumer<Scmp.ScmpMessage> listener) {
    Consumer<Scmp.ScmpMessage> old = errorListener;
    errorListener = listener;
    return old;
  }

  /**
   * Read data from the connected stream.
   *
   * @param dst The ByteBuffer that should contain data from the stream.
   * @return The number of bytes that were read into the buffer or -1 if end of stream was reached.
   * @throws NotYetConnectedException If the channel is not connected.
   * @throws java.nio.channels.ClosedChannelException If the channel is closed, e.g. by calling
   *     interrupt during read().
   * @throws IOException If some IOError occurs.
   * @see java.nio.channels.DatagramChannel#read(ByteBuffer)
   * @see ByteChannel#read(ByteBuffer)
   */
  @Override
  public synchronized int read(ByteBuffer dst) throws IOException {
    checkOpen();
    checkConnected(true);

    int oldPos = dst.position();
    receive(dst);
    return dst.position() - oldPos;
  }

  /**
   * Write the content of a ByteBuffer to a connection. This method uses the path that was provided
   * or looked up during `connect()`. The path will automatically be refreshed when expired.
   *
   * @param src The data to send
   * @return The number of bytes written.
   * @throws NotYetConnectedException If the channel is not connected.
   * @throws java.nio.channels.ClosedChannelException If the channel is closed.
   * @throws IOException If some IOError occurs.
   * @see java.nio.channels.DatagramChannel#write(ByteBuffer[])
   */
  @Override
  public synchronized int write(ByteBuffer src) throws IOException {
    checkOpen();
    checkConnected(true);

    int len = src.remaining();
    // + 8 for UDP overlay header length
    buildHeader(path, len + 8, InternalConstants.HdrTypes.UDP);
    buffer.put(src);
    buffer.flip();

    int sent = channel.write(buffer);
    if (sent < buffer.limit() || buffer.remaining() > 0) {
      throw new ScionException("Failed to send all data.");
    }
    return len - buffer.remaining();
  }

  private void checkOpen() throws ClosedChannelException {
    if (!channel.isOpen()) {
      throw new ClosedChannelException();
    }
  }

  private void checkConnected(boolean requiredState) {
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

  public synchronized <T> DatagramChannel setOption(SocketOption<T> option, T t)
      throws IOException {
    if (option instanceof ScionSocketOptions.SciSocketOption) {
      if (ScionSocketOptions.SN_API_THROW_PARSER_FAILURE.equals(option)) {
        cfgReportFailedValidation = (Boolean) t;
      } else if (ScionSocketOptions.SN_PATH_EXPIRY_MARGIN.equals(option)) {
        cfgExpirationSafetyMargin = (Integer) t;
      } else {
        throw new UnsupportedOperationException();
      }
    } else {
      channel.setOption(option, t);
    }
    return this;
  }

  /**
   * @param path path
   * @param payloadLength payload length
   * @return argument path or a new path if the argument path was expired
   * @throws IOException in case of IOException.
   */
  private Path buildHeader(Path path, int payloadLength, InternalConstants.HdrTypes hdrType)
      throws IOException {
    buffer.clear();
    long srcIA;
    byte[] srcAddress;
    int srcPort;
    if (path instanceof ResponsePath) {
      // We could get source IA, address and port locally but it seems cleaner to
      // to get the from the inverted header.
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
