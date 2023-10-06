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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

public class ScionDatagramChannel {

  private final DatagramChannel channel;
  private final ScionPacketHelper helper = new ScionPacketHelper();
  private ScionPacketHelper.PathState pathState = ScionPacketHelper.PathState.NO_PATH;
  private InetSocketAddress localAddress = null;
  private InetSocketAddress routerAddress = null;

  public static ScionDatagramChannel open() throws IOException {
    return new ScionDatagramChannel();
  }

  protected ScionDatagramChannel() throws IOException {
    channel = DatagramChannel.open();
  }

  public synchronized SocketAddress receive(ByteBuffer userBuffer) throws IOException {
    byte[] bytes = new byte[65536];
    ByteBuffer buffer = ByteBuffer.wrap(bytes); // TODO allocate direct?
    SocketAddress srcAddr = channel.receive(buffer);
    if (srcAddr == null) {
      // this indicates nothing is available
      return null;
    }
    routerAddress = (InetSocketAddress) srcAddr;
    buffer.flip();

    int headerLength = helper.readScionHeader(bytes);
    userBuffer.put(bytes, headerLength, helper.getPayloadLength());
    pathState = ScionPacketHelper.PathState.RCV_PATH;
    return helper.getReceivedSrcAddress();
  }

  private InetSocketAddress checkAddress(SocketAddress address) {
    if (!(address instanceof InetSocketAddress)) {
      throw new IllegalArgumentException("Address must be of type InetSocketAddress");
    }
    return (InetSocketAddress) address;
  }

  public synchronized void send(ByteBuffer buffer, SocketAddress destinationAddress)
      throws IOException {
    InetSocketAddress dstAddress = checkAddress(destinationAddress);
    ScionPath path = null;
    if (dstAddress.getAddress().equals(helper.getSourceAddress())) {
      // We are just sending back to last IP. We can use the reversed path. No need to lookup a
      // path.
      // path = helper.getLastIncomingPath(destinationAddress);
      if (pathState != ScionPacketHelper.PathState.RCV_PATH) {
        throw new IllegalStateException(
            "state=" + pathState); // TODO remove this check and possibly the path state alltogether
      }
    } else {
      // find a path
      path = helper.getDefaultPath(dstAddress);
      routerAddress = null;
    }
    send(buffer, destinationAddress, path);
  }

  public synchronized void send(
      ByteBuffer buffer, SocketAddress destinationAddress, ScionPath path) throws IOException {
    InetSocketAddress dstAddress = checkAddress(destinationAddress);
    // TODO do we need to create separate channels for each border router or can we "connect" to
    //  different ones from a single channel? Do we need to connect explicitly?
    //  What happens if, for the same path, we suddenly get a different border router recommended,
    //  do we need to create a new channel and reconnect?

    // get local IP
    if (!channel.isConnected() && localAddress == null) {
      InetSocketAddress borderRouterAddr = helper.getFirstHopAddress(path);
      channel.connect(borderRouterAddr);
      localAddress = (InetSocketAddress) channel.getLocalAddress();
    }

    byte[] buf = new byte[1000]; // / TODO ????  1000?
    int payloadLength = buffer.limit() - buffer.position();
    int headerLength =
        helper.writeHeader(buf, pathState, localAddress, dstAddress, payloadLength);

    ByteBuffer output =
        ByteBuffer.allocate(payloadLength + headerLength); // TODO reuse, or allocate direct??? Capacity?
    System.arraycopy(buf, 0, output.array(), output.arrayOffset(), headerLength);
    System.arraycopy(
        buffer.array(),
        buffer.arrayOffset() + buffer.position(),
        output.array(),
        output.arrayOffset() + headerLength,
        payloadLength);
    SocketAddress firstHopAddress;
    if (pathState == ScionPacketHelper.PathState.RCV_PATH) {
      firstHopAddress = routerAddress;
    } else {
      firstHopAddress = helper.getFirstHopAddress();
      // TODO routerAddress = firstHopAddress?
    }
    channel.send(output, firstHopAddress);
    buffer.position(buffer.limit());
  }

  public ScionDatagramChannel bind(InetSocketAddress address) throws IOException {
    localAddress = address; // `address` may be `null`.
    channel.bind(address);
    return this;
  }

  public void configureBlocking(boolean block) throws IOException {
    channel.configureBlocking(block);
  }

  @Deprecated
  public void setDstIsdAs(String isdAs) {
    helper.setDstIsdAs(isdAs);
  }

  public SocketAddress getLocalAddress() {
    return localAddress;
  }

//  public SocketAddress getRemoteAddress() {
//    return helper.get;
//  }
}
