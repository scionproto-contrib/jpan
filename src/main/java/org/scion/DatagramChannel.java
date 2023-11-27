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
import org.scion.internal.ScionHeaderParser;

public class DatagramChannel implements ByteChannel, Closeable {

  private final java.nio.channels.DatagramChannel channel;
  private boolean isBound = false;
  private Path path;
  private final ByteBuffer buffer = ByteBuffer.allocate(66000); // TODO allocate direct?
  private boolean cfgReportFailedValidation = false;
  private PathPolicy pathPolicy = PathPolicy.DEFAULT;

  public static DatagramChannel open() throws IOException {
    return new DatagramChannel();
  }

  protected DatagramChannel() throws IOException {
    channel = java.nio.channels.DatagramChannel.open();
  }

  public void setPathPolicy(PathPolicy pathPolicy) {
    this.pathPolicy = pathPolicy;
  }

  public PathPolicy getPathPolicy() {
    return this.pathPolicy;
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
   * Attempts to send the content of the buffer to the destinationAddress.
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

  public synchronized void send(ByteBuffer srcBuffer, Path path) throws IOException {
    // TODO do we need to create separate channels for each border router or can we "connect" to
    //  different ones from a single channel? Do we need to connect explicitly?
    //  What happens if, for the same path, we suddenly get a different border router recommended,
    //  do we need to create a new channel and reconnect?

    // get local IP
    if (!channel.isConnected() && !isBound) {
      InetSocketAddress underlayAddress = path.getFirstHopAddress();
      channel.connect(underlayAddress);
    }

    // build and send packet
    buildPacket(path, srcBuffer);
    channel.send(buffer, path.getFirstHopAddress());
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
      InetSocketAddress inetAddress = (InetSocketAddress) addr;
      // TODO hostName vs hostString
//      if (IPAddressUtil.isIPv4LiteralAddress(inetAddress.getHostString())
//              || IPAddressUtil.isIPv6LiteralAddress(inetAddress.getHostString())) {
//        // If the address is a literal address we can only assume that it is correct and
//        // hasn't been looked up via normal DNS. We don;t have a choice anyway.
//        // Problem: how do we get the AS info?
//        throw new UnsupportedOperationException();
//      } else {
//        // Look it up via DNS/TXT entry
//        // TODO special treatment of 'localhost'?
//
//      }

      // path = Path.create(inetAddress);
      // TODO optimize this, we ony need the IA here, not the full ScionAddress.
      //   ... well, how do we know the dstIP?
      //   - if "addr" is a ScionAddress, then we have the correct IP
      //   - if it is an InetAddress based on hostname, then it was (definitely?) looked up with
      //     normal DNS and is likely to be the wrong IP
      //   - if it is an InetAddress based on IP, then it is probably the correct IP, in any case.
      //     we don;t have a choice because we cannot look up another IP (reverse lookup???)
      path = Scion.defaultService().getPath(inetAddress, pathPolicy);
    } else {
      throw new IllegalArgumentException("Address must be of type InetSocketAddress.");
    }
    return path;
  }

  public DatagramChannel connect(SocketAddress addr) throws IOException {
    if (addr instanceof InetSocketAddress) {
      InetSocketAddress inetAddress = (InetSocketAddress) addr;
      // path = Path.create(inetAddress);
      // TODO optimize this, we ony need the IA here, not the full ScionAddress.
      //   ... well, how do we know the dstIP?
      //   - if "addr" is a ScionAddress, then we have the correct IP
      //   - if it is an InetAdress based on hostname, then it was (definitely?) looked up with
      //     normal DNS and is likely to be the wrong IP
      //   - if it is an InetAdress based on IP, then it is probably the correct IP, in any case.
      //     we don;t have a choice because we cannot look up another IP (reverse lookup???)
      ScionAddress sa = Scion.defaultService().getScionAddress(inetAddress.getHostName());
      path = Scion.defaultService().getPath(sa.getIsdAs(), pathPolicy);
    } else {
      throw new IllegalArgumentException(
          "connect() requires an InetSocketAddress or a ScionSocketAddress.");
    }
    channel.connect(path.getFirstHopAddress());
    return this;
  }

  @Deprecated // TODO remove, we should not connect with a Fixed path object.
  public DatagramChannel connect(Path path) throws IOException {
    this.path = path;
    channel.connect(path.getFirstHopAddress());
    return this;
  }

  /**
   * Read data from the connected stream.
   *
   * @param dst The ByteBuffer that should contain data from the stream.
   * @return The number of bytes that were read into the buffer or -1 if end of stream was reached.
   * @throws NotYetConnectedException If the channel is not connected.
   * @throws java.nio.channels.ClosedChannelException If the channel is closed.
   * @throws IOException If some IOError occurs.
   * @see java.nio.channels.DatagramChannel#read(ByteBuffer)
   */
  @Override
  public int read(ByteBuffer dst) throws IOException {
    checkOpen();
    checkConnected();

    buffer.clear();
    int bytesRead = channel.read(buffer);
    if (bytesRead == -1) {
      return -1;
    }
    int len = dst.position();
    buffer.flip();
    ScionHeaderParser.readUserData(buffer, dst);
    buffer.clear();
    return dst.position() - len;
  }

  /**
   * Write the content of a ByteBuffer to a connection.
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
      if (ScionSocketOptions.API_THROW_PARSER_FAILURE.equals(option)) {
        cfgReportFailedValidation = (Boolean) t;
      } else if (ScionSocketOptions.API_WRITE_TO_USER_BUFFER.equals(option)) {
        // TODO This would allow reading directly into the user buffer. Advantages:
        //     a) Omit copying step buffer-userBuffer; b) user can see SCION header.
        throw new UnsupportedOperationException();
      }
    } else {
      channel.setOption(option, t);
    }
    return this;
  }

  private void buildPacket(Path dstSocketAddress, ByteBuffer srcBuffer) throws IOException {
    // TODO request new path after a while? Yes! respect path expiry! -> Do that in ScionService!
    buffer.clear();
    int payloadLength = srcBuffer.remaining();

    InetSocketAddress srcSocketAddress = getLocalAddress();
    long srcIA = Scion.defaultService().getLocalIsdAs(); // TODO do not use default()
    long dstIA = dstSocketAddress.getDestinationIsdAs();
    int srcPort = srcSocketAddress.getPort();
    int dstPort = dstSocketAddress.getDestinationPort();
    byte[] srcAddress = srcSocketAddress.getAddress().getAddress();
    byte[] dstAddress = dstSocketAddress.getDestinationAddress();

    byte[] path = dstSocketAddress.getRawPath();
    ScionHeaderParser.write(
        buffer, payloadLength, path.length, srcIA, srcAddress, dstIA, dstAddress);
    ScionHeaderParser.writePath(buffer, path);
    ScionHeaderParser.writeUdpOverlayHeader(buffer, payloadLength, srcPort, dstPort);

    buffer.put(srcBuffer);
    buffer.flip();
  }
}
