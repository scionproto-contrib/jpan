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
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.time.Instant;
import org.scion.internal.ScionHeaderParser;

public class DatagramChannel implements ByteChannel, Closeable {

  private final java.nio.channels.DatagramChannel channel;
  private boolean isBound = false;
  private Path path;
  private final ByteBuffer buffer = ByteBuffer.allocate(66000); // TODO allocate direct?
  private boolean cfgReportFailedValidation = false;
  private PathPolicy pathPolicy = PathPolicy.DEFAULT;
  private ScionService service;
  private int cfgExpirationSafetyMargin =
      ScionUtil.getPropertyOrEnv(
          ScionConstants.PROPERTY_PATH_EXPIRY_MARGIN,
          ScionConstants.ENV_PATH_EXPIRY_MARGIN,
          ScionConstants.DEFAULT_PATH_EXPIRY_MARGIN);

  public static DatagramChannel open() throws IOException {
    return new DatagramChannel();
  }

  protected DatagramChannel() throws IOException {
    channel = java.nio.channels.DatagramChannel.open();
  }

  /**
   * Set the path policy. The default path policy is set in PathPolicy.DEFAULT, which currently
   * means to use the first path returned by the daemon or control service.
   *
   * @param pathPolicy the new path policy
   * @see PathPolicy#DEFAULT
   */
  public void setPathPolicy(PathPolicy pathPolicy) {
    this.pathPolicy = pathPolicy;
  }

  public PathPolicy getPathPolicy() {
    return this.pathPolicy;
  }

  public void setService(ScionService service) {
    this.service = service;
  }

  public ScionService getService() {
    if (service == null) {
      service = Scion.defaultService();
    }
    return this.service;
  }

  public synchronized Path receive(ByteBuffer userBuffer) throws IOException {
    SocketAddress srcAddress;
    String validationResult;
    do {
      buffer.clear();
      srcAddress = channel.receive(buffer);
      if (srcAddress == null) {
        // this indicates nothing is available
        return null;
      }
      buffer.flip();

      // validateResult != null indicates a problem with the packet
      validationResult = ScionHeaderParser.validate(buffer.asReadOnlyBuffer());
      if (validationResult != null && cfgReportFailedValidation) {
        throw new ScionException(validationResult);
      }
    } while (validationResult != null);

    ScionHeaderParser.readUserData(buffer, userBuffer);
    Path addr = ScionHeaderParser.readRemoteSocketAddress(buffer, (InetSocketAddress) srcAddress);
    buffer.clear();
    return addr;
  }

  /**
   * Attempts to send the content of the buffer to the destinationAddress. This method will request
   * a new path for each call.
   *
   * @param buffer Data to send
   * @param destination Destination address. This should contain a host name known to the DNS so
   *     that the ISD/AS information can be retrieved.
   * @throws IOException if an error occurs, e.g. if the destinationAddress is an IP address that
   *     cannot be resolved to an ISD/AS.
   * @see java.nio.channels.DatagramChannel#send(ByteBuffer, SocketAddress)
   */
  public synchronized void send(ByteBuffer buffer, SocketAddress destination) throws IOException {
    send(buffer, findPath(destination));
  }

  /**
   * Attempts to send the content of the buffer to the destinationAddress.
   *
   * @param srcBuffer Data to send
   * @param path Path to destination. If this path is expired it will automatically be replaced with
   *     a new path.
   * @return either the path argument or a new path if the path was an expired RequestPath. Note
   *     that ResponsePaths are not checked for expiration.
   * @throws IOException if an error occurs, e.g. if the destinationAddress is an IP address that
   *     cannot be resolved to an ISD/AS.
   * @see java.nio.channels.DatagramChannel#send(ByteBuffer, SocketAddress)
   */
  public synchronized Path send(ByteBuffer srcBuffer, Path path) throws IOException {
    // TODO do we need to create separate channels for each border router or can we "connect" to
    //  different ones from a single channel? Do we need to connect explicitly?
    //  What happens if, for the same path, we suddenly get a different border router recommended,
    //  do we need to create a new channel and reconnect?

    // get local IP

    // TODO why connect??????
    if (!channel.isConnected() && !isBound) {
      InetSocketAddress underlayAddress = path.getFirstHopAddress();
      channel.connect(underlayAddress);
    }

    // build and send packet
    Path actualPath = buildPacket(path, srcBuffer);
    channel.send(buffer, path.getFirstHopAddress());
    return actualPath;
  }

  public DatagramChannel bind(InetSocketAddress address) throws IOException {
    // bind() is called by java.net.DatagramSocket even for clients (with 0.0.0.0:0).
    // We need to avoid this.
    if (address != null && address.getPort() != 0) {
      channel.bind(address);
      isBound = true;
    } else {
      channel.bind(null);
    }
    return this;
  }

  // TODO we return `void` here. If we implement SelectableChannel
  //  this can be changed to return SelectableChannel.
  public void configureBlocking(boolean block) throws IOException {
    channel.configureBlocking(block);
  }

  public boolean isBlocking() {
    return channel.isBlocking();
  }

  public InetSocketAddress getLocalAddress() throws IOException {
    return (InetSocketAddress) channel.getLocalAddress();
  }

  public void disconnect() throws IOException {
    channel.disconnect();
  }

  @Override
  public boolean isOpen() {
    return channel.isOpen();
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  private Path findPath(SocketAddress addr) throws IOException {
    if (addr instanceof SSocketAddress) {
      throw new UnsupportedOperationException(); // TODO implement
    } else if (addr instanceof InetSocketAddress) {
      path = pathPolicy.filter(getService().getPaths((InetSocketAddress) addr));
    } else {
      throw new IllegalArgumentException("Address must be of type InetSocketAddress.");
    }
    return path;
  }

  /**
   * Connect to a destination host. Note: - A SCION channel will internally connect to the next
   * border router (first hop) instead of the remote host.
   *
   * @param addr Address of remote host.
   * @return This channel.
   * @throws IOException for example when the first hop (border router) cannot be connected.
   */
  public DatagramChannel connect(SocketAddress addr) throws IOException {
    if (addr instanceof InetSocketAddress) {
      path = pathPolicy.filter(getService().getPaths((InetSocketAddress) addr));
    } else {
      throw new IllegalArgumentException(
          "connect() requires an InetSocketAddress or a ScionSocketAddress.");
    }
    channel.connect(path.getFirstHopAddress());
    return this;
  }

  /**
   * Connect to a destination host. Note: - A SCION channel will internally connect to the next
   * border router (first hop) instead of the remote host. - The path will be replaced with a new
   * path once it is expired.
   *
   * @param path PAth to the remote host.
   * @return This channel.
   * @throws IOException for example when the first hop (border router) cannot be connected.
   */
  public DatagramChannel connect(Path path) throws IOException {
    this.path = path;
    channel.connect(path.getFirstHopAddress());
    return this;
  }

  /**
   * Get the currently connected path.
   *
   * @return the current Path or `null` if not path is connected.
   */
  public Path getCurrentPath() {
    return path;
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
   */
  @Override
  public int read(ByteBuffer dst) throws IOException {
    checkOpen();
    checkConnected();

    int oldPos = dst.position();
    String validationResult;
    do {
      buffer.clear();
      int bytesRead = channel.read(buffer);
      if (bytesRead <= 0) {
        return bytesRead;
      }
      buffer.flip();

      // validateResult != null indicates a problem with the packet
      validationResult = ScionHeaderParser.validate(buffer.asReadOnlyBuffer());
      if (validationResult != null && cfgReportFailedValidation) {
        throw new ScionException(validationResult);
      }
    } while (validationResult != null);

    ScionHeaderParser.readUserData(buffer, dst);
    buffer.clear();
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
  public int write(ByteBuffer src) throws IOException {
    checkOpen();
    checkConnected();

    buildPacket(path, src);
    return channel.write(buffer);
  }

  private void checkOpen() throws ClosedChannelException {
    if (!channel.isOpen()) {
      throw new ClosedChannelException();
    }
  }

  private void checkConnected() {
    if (!channel.isConnected()) {
      throw new NotYetConnectedException();
    }
  }

  public boolean isConnected() {
    return channel.isConnected();
  }

  public <T> DatagramChannel setOption(SocketOption<T> option, T t) throws IOException {
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
   * @param srcBuffer src buffer
   * @return argument path or a new path if the argument path was expired
   * @throws IOException in case of IOException.
   */
  private Path buildPacket(Path path, ByteBuffer srcBuffer) throws IOException {
    // TODO request new path after a while? Yes! respect path expiry! -> Do that in ScionService!
    buffer.clear();
    int payloadLength = srcBuffer.remaining();

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
      // check path expiration
      path = ensureUpToDate((RequestPath) path);
      srcIA = getService().getLocalIsdAs();
      InetSocketAddress srcSocketAddress = getLocalAddress();
      srcAddress = srcSocketAddress.getAddress().getAddress();
      srcPort = srcSocketAddress.getPort();
    }

    long dstIA = path.getDestinationIsdAs();
    byte[] dstAddress = path.getDestinationAddress();
    int dstPort = path.getDestinationPort();

    byte[] rawPath = path.getRawPath();
    ScionHeaderParser.write(
        buffer, payloadLength, rawPath.length, srcIA, srcAddress, dstIA, dstAddress);
    ScionHeaderParser.writePath(buffer, rawPath);
    ScionHeaderParser.writeUdpOverlayHeader(buffer, payloadLength, srcPort, dstPort);

    buffer.put(srcBuffer);
    buffer.flip();
    return path;
  }

  private Path ensureUpToDate(RequestPath path) throws IOException {
    if (Instant.now().getEpochSecond() + cfgExpirationSafetyMargin <= path.getExpiration()) {
      return path;
    }
    // expired, get new path
    Path newPath =
        pathPolicy.filter(
            getService()
                .getPaths(
                    path.getDestinationIsdAs(),
                    path.getDestinationAddress(),
                    path.getDestinationPort()));

    if (this.path != null) {
      // TODO THis is brittle, do this on channel.isConnected() i.o. path != null?
      channel.disconnect();
      channel.connect(newPath.getFirstHopAddress());
      this.path = newPath;
    }

    return newPath;
  }
}
