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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

public class DatagramChannel implements Closeable {

  private final java.nio.channels.DatagramChannel channel;
  boolean isBound = false;

  public static DatagramChannel open() throws IOException {
    return new DatagramChannel();
  }

  protected DatagramChannel() throws IOException {
    channel = java.nio.channels.DatagramChannel.open();
  }

  public synchronized ScionSocketAddress receive(ByteBuffer userBuffer) throws IOException {
    // We cannot read directly into the user buffer because the user buffer may be too small.
    // TODO However, we may want to use read() i.o. receive(),
    //      this may allow us to use a smaller buffer here
    byte[] bytes = new byte[65536]; // Do not allocate here...? -> part of ScionSocketAddress/Helper?
    // TODO Is it okay to use the user-buffer here and later "move" the payload forward?
    //    Probably not, the user may not have enough byte allocated. Check API!
    //    -> TODO make configurable: USE_USER_BUFFER_FOR_DECODING
    ByteBuffer buffer = ByteBuffer.wrap(bytes); // TODO allocate direct?
    SocketAddress srcAddress = channel.receive(buffer);
    if (srcAddress == null) {
      // this indicates nothing is available
      // TODO test this.
      return null;
    }
    buffer.flip();

    // TODO pass bytes{} instead of remoteAddress -> make ScionPacketHelper.remoteAddress final
    ScionPacketHelper helper = new ScionPacketHelper(null, ScionPacketHelper.PathState.RCV_PATH);
    int headerLength = helper.readScionHeader(bytes);
    userBuffer.put(bytes, headerLength, helper.getPayloadLength());
    // We assume the outgoing router will be the same as the incoming router
    System.out.println(
        Thread.currentThread().getName() + " is setting: " + srcAddress + "    " + this); // TODO
    helper.setUnderlayAddress((InetSocketAddress) srcAddress);
    return helper.getReceivedSrcAddress();
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
    ScionPacketHelper context = dstAddress.getHelper();

    // get local IP
    if (!channel.isConnected() && !isBound) {
      // TODO why are we not setting the underlay here?
      InetSocketAddress underlayAddress = context.getFirstHopAddress(dstAddress.getPath());
      channel.connect(underlayAddress);
    }

    byte[] buf = new byte[1000]; // / TODO ????  1000?
    int payloadLength = buffer.limit() - buffer.position();
    int headerLength =
        context.writeHeader(
            buf, (InetSocketAddress) channel.getLocalAddress(), dstAddress, payloadLength);

    ByteBuffer output =
        ByteBuffer.allocate(
            payloadLength + headerLength); // TODO reuse, or allocate direct??? Capacity?
    System.arraycopy(buf, 0, output.array(), output.arrayOffset(), headerLength);
    System.arraycopy(
        buffer.array(),
        buffer.arrayOffset() + buffer.position(),
        output.array(),
        output.arrayOffset() + headerLength,
        payloadLength);

    // send packet
    if (dstAddress.getHelper().getFirstHopAddress() == null) {
      throw new IllegalStateException(Thread.currentThread().getName() + "  FAILED"); // TODO remove
    }
    channel.send(output, dstAddress.getHelper().getFirstHopAddress());
    buffer.position(buffer.limit());
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

  public DatagramChannel configureBlocking(boolean block) throws IOException {
    channel.configureBlocking(block);
    return this;
  }

  public SocketAddress getLocalAddress() throws IOException {
    return channel.getLocalAddress(); // TODO solve with inheritance
  }

  public void disconnect() throws IOException {
    channel.disconnect();
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
