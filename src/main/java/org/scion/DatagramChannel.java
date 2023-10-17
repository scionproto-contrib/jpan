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
  private ScionPacketHelper.PathState pathState = ScionPacketHelper.PathState.NO_PATH;
  private InetSocketAddress localAddress = null;

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
    byte[] bytes = new byte[65536];
    ByteBuffer buffer = ByteBuffer.wrap(bytes); // TODO allocate direct?
    SocketAddress srcAddr = channel.receive(buffer);
    if (srcAddr == null) {
      // this indicates nothing is available
      return null;
    }
    buffer.flip();

    ScionPacketHelper helper = new ScionPacketHelper();
    int headerLength = helper.readScionHeader(bytes);
    userBuffer.put(bytes, headerLength, helper.getPayloadLength());
    pathState = ScionPacketHelper.PathState.RCV_PATH;
    // We assume the outgoing router will be the same as the incoming router
    helper.setUnderlayAddress((InetSocketAddress) srcAddr);
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
   * @param destinationAddress Destination address. This should contain a host name known to the DNS so that
   *                           the ISD/AS information can be retrieved.
   * @throws IOException if an error occurs, e.g. if the destinationAddress is an IP address that cannot be resolved
   * to an ISD/AS. TODO test this
   * @see java.nio.channels.DatagramChannel#send(ByteBuffer, SocketAddress)
   */
  public synchronized void send(ByteBuffer buffer, SocketAddress destinationAddress)
          throws IOException {
    if (destinationAddress instanceof ScionSocketAddress) {
      send(buffer, (ScionSocketAddress) destinationAddress); // This is weird, clean up with additional internal method
    } else {
      InetSocketAddress dstAddress = checkAddress(destinationAddress);
      ScionPacketHelper helper = new ScionPacketHelper();
      // find a path
      ScionPath path = helper.getDefaultPath(dstAddress);
      pathState = ScionPacketHelper.PathState.NO_PATH; // TODO this is weird, why do we look up a path and then ignore it?
      ScionSocketAddress addr = ScionSocketAddress.create(helper, path.getDestinationCode(), dstAddress.getHostString(), dstAddress.getPort());
      addr.setPath(path); // TODO this is awkward
      send(buffer, addr);
    }
//    ScionPath path = null;
//    if (dstAddress.getAddress().equals(helper.getSourceAddress())) {
//      // We are just sending back to last IP. We can use the reversed path. No need to lookup a
//      // path.
//      // path = helper.getLastIncomingPath(destinationAddress);
//      if (pathState != ScionPacketHelper.PathState.RCV_PATH) {
//        throw new IllegalStateException(
//                "state=" + pathState); // TODO remove this check and possibly the path state alltogether
//      }
//    } else {
//      // find a path
//      path = helper.getDefaultPath(dstAddress);
//      routerAddress = null;
//    }
//    send(buffer, destinationAddress, path);
  }

  public synchronized void send(
      ByteBuffer buffer, SocketAddress destinationAddress, ScionPath path) throws IOException {
    if (destinationAddress instanceof ScionSocketAddress) {
      throw new IllegalArgumentException(); // TODO should we handle this and set the path?
    }
    InetSocketAddress dstAddress = checkAddress(destinationAddress);
    ScionSocketAddress addr = ScionSocketAddress.create(dstAddress.getHostString(), dstAddress.getPort(), path);
    //send(buffer, dstAddress.getAddress().getAddress(), dstAddress.getPort(), path);
    send(buffer, addr);
  }

  public synchronized void send(ByteBuffer buffer, ScionSocketAddress dstAddress) throws IOException {
    // TODO do we need to create separate channels for each border router or can we "connect" to
    //  different ones from a single channel? Do we need to connect explicitly?
    //  What happens if, for the same path, we suddenly get a different border router recommended,
    //  do we need to create a new channel and reconnect?

    // get local IP
    if (!channel.isConnected() && localAddress == null) {
      // TODO ? dstAddress.setHelper(new ScionPacketHelper()); // TODO createHelper()?
      InetSocketAddress borderRouterAddr = dstAddress.getHelper().getFirstHopAddress(dstAddress.getPath());
      channel.connect(borderRouterAddr);
      localAddress = (InetSocketAddress) channel.getLocalAddress();
    }

    byte[] buf = new byte[1000]; // / TODO ????  1000?
    int payloadLength = buffer.limit() - buffer.position();
    int headerLength =
            dstAddress.getHelper().writeHeader(
                    buf, pathState, localAddress.getAddress().getAddress(), localAddress.getPort(),
                    // TODO dstAddress.getAddress().getAddress() -> store bytes in ScionAddress
                    dstAddress.getAddress().getAddress(), dstAddress.getPort(), payloadLength);

    ByteBuffer output =
            ByteBuffer.allocate(payloadLength + headerLength); // TODO reuse, or allocate direct??? Capacity?
    System.arraycopy(buf, 0, output.array(), output.arrayOffset(), headerLength);
    System.arraycopy(
            buffer.array(),
            buffer.arrayOffset() + buffer.position(),
            output.array(),
            output.arrayOffset() + headerLength,
            payloadLength);

    // send packet
    channel.send(output, dstAddress.getHelper().getFirstHopAddress());
    buffer.position(buffer.limit());
  }

  public DatagramChannel bind(InetSocketAddress address) throws IOException {
    // bind() is called java.net.DatagramSocket even for clients (with 0.0.0.0:0). We need to avoid this.
    if (address.getPort() != 0) {
      channel.bind(address);
      localAddress = (InetSocketAddress) channel.getLocalAddress();
    }
    return this;
  }

  public DatagramChannel configureBlocking(boolean block) throws IOException {
    channel.configureBlocking(block);
    return this;
  }

  @Deprecated
  public void setDstIsdAs(String isdAs) {
    throw new UnsupportedOperationException();
  }

  public SocketAddress getLocalAddress() {
    return localAddress;
  }

  public void disconnect() throws IOException {
    channel.disconnect();
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

//  public SocketAddress getRemoteAddress() {
//    return helper.get;
//  }
}
