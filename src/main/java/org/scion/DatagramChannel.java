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
  private ScionSocketAddress localScionAddress;
  private ScionSocketAddress remoteScionAddress;
  private final ByteBuffer buffer = ByteBuffer.allocate(66000); // TODO allocate direct?
  private boolean cfgReportFailedValidation = false;

  public static DatagramChannel open() throws IOException {
    return new DatagramChannel();
  }

  protected DatagramChannel() throws IOException {
    channel = java.nio.channels.DatagramChannel.open();
  }

  public synchronized ScionSocketAddress receive(ByteBuffer userBuffer) throws IOException {
    // We cannot read directly into the user buffer because the user buffer may be too small.
    // TODO Is it okay to use the user-buffer here and later "move" the payload forward?
    //    Probably not, the user may not have enough byte allocated. Check API!
    //    -> TODO make configurable: USE_USER_BUFFER_FOR_DECODING

    SocketAddress srcAddress;
    String validationResult;
    do {
      buffer.clear();
      srcAddress = channel.receive(buffer);
      if (srcAddress == null) {
        // this indicates nothing is available
        // TODO test this.
        return null;
      }
      buffer.flip();

      validationResult = ScionHeaderParser.validate(buffer.asReadOnlyBuffer());
      if (validationResult != null && cfgReportFailedValidation) {
        throw new ScionException(validationResult);
      }
    } while (validationResult != null);

    // TODO ScionPacketHelper2.verifyPacketHeader(buffer)   -> abort (or send SCMP) if check fails.
    ScionPacketHelper.getUserData(buffer, userBuffer);
    ScionSocketAddress addr =
        ScionPacketHelper.getRemoteAddressAndPath(buffer, (InetSocketAddress) srcAddress);
    buffer.clear();
    return addr;
  }

  private InetSocketAddress checkAddress(SocketAddress address) {
    if (!(address instanceof InetSocketAddress)) {
      throw new IllegalArgumentException("Address must be of type InetSocketAddress");
    }
    return (InetSocketAddress) address;
  }

  /**
   * Attempts to send the content of the buffer to the destinationAddress.
   *
   * @param buffer Data to send
   * @param destination Destination address. This should contain a host name known to the DNS so
   *     that the ISD/AS information can be retrieved.
   * @throws IOException if an error occurs, e.g. if the destinationAddress is an IP address that
   *     cannot be resolved to an ISD/AS. TODO test this
   * @see java.nio.channels.DatagramChannel#send(ByteBuffer, SocketAddress)
   */
  public synchronized void send(ByteBuffer buffer, SocketAddress destination) throws IOException {
    if (destination instanceof ScionSocketAddress) {
      send(buffer, (ScionSocketAddress) destination);
    } else {
      InetSocketAddress dstAddress = checkAddress(destination);
      send(buffer, ScionSocketAddress.create(dstAddress));
    }
  }

  public synchronized void send(ByteBuffer buffer, SocketAddress destinationAddress, ScionPath path)
      throws IOException {
    if (destinationAddress instanceof ScionSocketAddress) {
      throw new IllegalArgumentException(); // TODO should we handle this and set the path?
    }
    InetSocketAddress dstAddress = checkAddress(destinationAddress);
    ScionSocketAddress addr =
        ScionSocketAddress.create(dstAddress.getHostString(), dstAddress.getPort(), path);
    send(buffer, addr);
  }

  private void send(ByteBuffer buffer, ScionSocketAddress dstAddress) throws IOException {
    // TODO do we need to create separate channels for each border router or can we "connect" to
    //  different ones from a single channel? Do we need to connect explicitly?
    //  What happens if, for the same path, we suddenly get a different border router recommended,
    //  do we need to create a new channel and reconnect?

    // get local IP
    if (!channel.isConnected() && !isBound) {
      InetSocketAddress underlayAddress = dstAddress.getPath().getFirstHopAddress();
      channel.connect(underlayAddress);
    }

    int payloadLength = buffer.limit() - buffer.position();
    // TODO reuse, or allocate direct??? Capacity?
    ByteBuffer output = ByteBuffer.allocate(payloadLength + 1000);
    ScionPacketHelper.writeHeader(output, getLocalScionAddress(), dstAddress, payloadLength);
    output.put(buffer);
    output.flip();

    // send packet
    channel.send(output, dstAddress.getPath().getFirstHopAddress());
    buffer.position(buffer.limit());
  }

  private InetSocketAddress getLocalScionAddress() throws IOException {
    if (localScionAddress == null) {
      localScionAddress = ScionSocketAddress.create((InetSocketAddress) channel.getLocalAddress());
    }
    return localScionAddress;
    //    return ScionSocketAddress.create((InetSocketAddress) channel.getLocalAddress());
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

  public SocketAddress getLocalAddress() throws IOException {
    return channel.getLocalAddress(); // TODO solve with inheritance
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

  public DatagramChannel connect(SocketAddress addr) throws IOException {
    if (addr instanceof ScionSocketAddress) {
      remoteScionAddress = (ScionSocketAddress) addr;
    } else if (addr instanceof InetSocketAddress) {
      InetSocketAddress inetAddress = (InetSocketAddress) addr;
      remoteScionAddress = ScionSocketAddress.create(inetAddress);
    } else {
      throw new IllegalArgumentException(
          "connect() requires an InetSocketAddress or a ScionSocketAddress.");
    }
    channel.connect(remoteScionAddress.getPath().getFirstHopAddress());
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
    // TODO test these
    // If there are more bytes in the datagram than remain in the given buffer then the
    // remainder of the datagram is silently discarded. Otherwise this method behaves exactly
    // as specified in the ReadableByteChannel interface.

    // TODO test these
    // Returns:
    // The number of bytes read, possibly zero, or -1 if the channel has reached end-of-stream

    buffer.clear();
    int bytesRead = channel.read(buffer);
    if (bytesRead == -1) {
      return -1;
    }
    int len = dst.position();
    buffer.flip();
    ScionPacketHelper.getUserData(buffer, dst);
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
    if (localScionAddress == null) {
      localScionAddress = ScionSocketAddress.create((InetSocketAddress) channel.getLocalAddress());
    }

    buffer.clear();
    int len = src.limit() - src.position();
    ScionPacketHelper.writeHeader(buffer, localScionAddress, remoteScionAddress, len);
    buffer.put(src);
    buffer.flip();

    channel.write(buffer);

    // TODO ? What is this function? Can we use it?       channel.socket();
    //    We have to overwrite it if it is in one of the interfaces!

    buffer.clear();
    return len;
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
      if (ScionSocketOptions.SSO_THROW_PARSER_FAILURE.equals(option)) {
        cfgReportFailedValidation = (Boolean) t;
      } else if (ScionSocketOptions.SSO_WRITE_TO_USER_BUFFER.equals(option)) {
        throw new UnsupportedOperationException(); // TODO
      }
    } else {
      channel.setOption(option, t);
    }
    return this;
  }
}
