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
import java.net.*;
import org.scion.internal.DatagramSocketImpl;

/**
 * We are extending DatagramSocket as recommended here: <a
 * href="https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/DatagramSocket.html#setDatagramSocketImplFactory(java.net.DatagramSocketImplFactory)">...</a>
 *
 * @deprecated Please do not use this. It does not really work and will be removed in a future
 *     release.
 */
@Deprecated
public class DatagramSocket extends java.net.DatagramSocket {

  public DatagramSocket(int port) throws SocketException {
    super(new DatagramSocketImpl(true));
    super.bind(new InetSocketAddress(port));
  }

  public DatagramSocket(InetSocketAddress bindAddress) throws IOException {
    super(new DatagramSocketImpl(true));
    if (bindAddress != null) {
      super.bind(bindAddress);
    }
  }

  public DatagramSocket(int port, InetAddress localAddress) throws SocketException {
    super(new DatagramSocketImpl(true));
    super.bind(new InetSocketAddress(localAddress, port));
  }
}

// public class DatagramSocket implements Closeable {
//
//  /*
//   * Design:
//   * We use delegation rather than inheritance. Inheritance is more difficult to handle if future
// version of
//   * java.net.DatagramSocket get additional methods that need special SCION handling. These
// methods would
//   * behave incorrectly until adapted, and, once adapted, would not compile anymore with earlier
// Java releases.
//   */
//
//
//  private final java.net.DatagramSocket socket;
//  private final ScionPacketHelper helper = new ScionPacketHelper();
//  private InetSocketAddress localAddress = null;
//
//  // TODO respect MTU; report MTU to user (?); test!!!
//  // TODO use ByteBuffer to manage offset etc?
//  private final byte[] bytes = new byte[65535 - 28]; // -28 for 8 byte UDP + 20 byte IP header
//  private int underlayPort;
//  private InetAddress underlayAddress;
//  private ScionPacketHelper.PathState pathState = ScionPacketHelper.PathState.NO_PATH;
//  // TODO provide ports etc?  Allow separate instances for different sockets?
//  // TODO create lazily to prevent network connections before we create any actual DatagramSocket?
//  private ScionPathService pathService;
//  // TODO remove?
//  private final Object closeLock = new Object();
//  private boolean isClosed = false;
//
//
//  public DatagramSocket() throws SocketException {
//    socket = new java.net.DatagramSocket();
//    System.out.println(
//            "Creating socket: " + socket.getLocalAddress() + " : " + socket.getLocalPort());
//  }
//
//  public DatagramSocket(SocketAddress addr) throws SocketException {
//    socket = new java.net.DatagramSocket(addr);
//    localAddress = (InetSocketAddress) addr;
//    System.out.println(
//            "Creating socket with address: " + socket.getLocalAddress() + " : " +
// socket.getLocalPort());
//  }
//
//  public DatagramSocket(int port) throws SocketException {
//    this(port, (InetAddress)null);
//  }
//
//  public DatagramSocket(int port, InetAddress addr) throws SocketException {
//    this(new InetSocketAddress(addr, port));
//  }
//
//  @Deprecated // TODO This  is not how we should do it. Find IA automatically or require it via
//  // constructor
//  public void setDstIsdAs(String dstIA) {
//    helper.setDstIsdAs(dstIA);
//  }
//
//  public synchronized void receive(DatagramPacket packet) throws IOException {
//    // synchronized because we use `buffer`
//    while (true) {
//      // TODO reuse incoming packet
//    // TODO rename to bytes to buffer
//      DatagramPacket incoming = new DatagramPacket(bytes, bytes.length);
//      socket.receive(incoming);
//      underlayPort = incoming.getPort();
//      underlayAddress = incoming.getAddress();
//
//
//      int headerLength = helper.readScionHeader(bytes);
//      if (headerLength < 0) {
//        // Ignore packet
//        continue;
//      }
//      System.arraycopy(bytes, headerLength, packet.getData(), 0, helper.getPayloadLength());
//
//      // build packet
//      packet.setLength(helper.getPayloadLength());
//      packet.setSocketAddress(helper.getReceivedSrcAddress());
//
//      pathState = ScionPacketHelper.PathState.RCV_PATH;
//      break;
//    }
//  }
//
//  public synchronized void send(DatagramPacket userPacket) throws IOException {
//    ScionPath path = null;
//    InetSocketAddress dstAddress = (InetSocketAddress) userPacket.getSocketAddress();
//
//    if (dstAddress.getAddress().equals(helper.getSourceAddress())) {
//      // We are just sending back to last IP. We can use the reversed path. No need to lookup a
//      // path.
//      // path = helper.getLastIncomingPath(destinationAddress);
//      if (pathState != ScionPacketHelper.PathState.RCV_PATH) {
//        throw new IllegalStateException(
//                "state=" + pathState); // TODO remove this check and possibly the path state
// altogether
//      }
//    } else {
//      // find a path
//      path = helper.getDefaultPath(dstAddress);
//      underlayAddress = null;
//      underlayPort = -1;
//    }
//    send(userPacket, path);
//  }
//
//  public synchronized void send(DatagramPacket userPacket, ScionPath path) throws IOException {
//    // synchronized because we use `buffer`
//    // TODO request new path after a while?
//
//    // TODO use local field Datagram Packer?!
//    DatagramPacket outgoing = new DatagramPacket(bytes, bytes.length);
//    byte[] dataOut = outgoing.getData();
//
//    // get local IP
//    // TODO remove check for isConnected()?
//    if (!socket.isConnected() && localAddress == null) {
//      InetSocketAddress borderRouterAddr = helper.getFirstHopAddress(path);
//      socket.connect(borderRouterAddr);
//      localAddress = new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
//    }
//
//    InetSocketAddress dstAddress = (InetSocketAddress) userPacket.getSocketAddress();
//
//    // New stuff...
//    int payloadLength = userPacket.getLength();
//    int headerLength =
//            helper.writeHeader(dataOut, pathState, localAddress.getAddress().getAddress(),
//                    localAddress.getPort(), dstAddress.getAddress().getAddress(),
//                    dstAddress.getPort(), payloadLength);
//
//    System.arraycopy(userPacket.getData(), 0, dataOut, headerLength, payloadLength);
//    InetSocketAddress firstHopAddress = null;
//    if (pathState == ScionPacketHelper.PathState.RCV_PATH) {
//      //firstHopAddress = routerAddress;
//      outgoing.setAddress(underlayAddress);
//      outgoing.setPort(underlayPort);
//    } else {
//      firstHopAddress = helper.getFirstHopAddress();
//      outgoing.setAddress(firstHopAddress.getAddress());
//      outgoing.setPort(firstHopAddress.getPort());
//      // TODO routerAddress = firstHopAddress?
//    }
//    outgoing.setLength(headerLength + payloadLength);
//
//    // System.out.println("Sending to: " + outgoing.getSocketAddress());
//    socket.send(outgoing);
//
//    pathState = ScionPacketHelper.PathState.SEND_PATH;
//  }
//
//
//  public void connect(InetAddress addr, int port) {
//    // TODO set destination address
//    throw new UnsupportedOperationException();
//    // We do NOT call super.connect() here!
//  }
//
//  public void connect(SocketAddress addr) {
//    // TODO set destination address
//    throw new UnsupportedOperationException();
//    // We do NOT call super.connect() here!
//  }
//
//  public void bind(SocketAddress addr) {
//    // TODO set destination address
//    System.err.println("FIXME:  ScionDatagramSocket.bind()");
//    localAddress = (InetSocketAddress) addr;
//    // We do NOT call super.bind() here!
//    // TODO why not???
//  }
//
//  @Override
//  public void close() {
//    synchronized (closeLock) {
//      socket.close();
//      if (pathService != null) {
//        try {
//          pathService.close();
//          pathService = null;
//        } catch (IOException e) {
//          throw new RuntimeException(e);
//        }
//      }
//    }
//  }
//
//  public boolean isClosed() {
//    synchronized (closeLock) {
//      return isClosed;
//    }
//  }
//
//  public SocketAddress getLocalSocketAddress() {
//    return socket.getLocalSocketAddress();
//  }
//
//  public SocketAddress getRemoteSocketAddress() throws IOException {
//    if (pathState == ScionPacketHelper.PathState.RCV_PATH) {
//      return helper.getReceivedSrcAddress();
//    }
//    throw new UnsupportedOperationException("STUB: Return whatever is provided by connect()");
//  }
//
//  public InetAddress getLocalAddress() {
//    return socket.getLocalAddress();
//  }
//
//  public int getLocalPort() {
//    return socket.getLocalPort();
//  }
//
//  public int getPort() throws IOException {
//    if (pathState == ScionPacketHelper.PathState.RCV_PATH) {
//      return helper.getReceivedSrcAddress().getPort();
//    }
//    throw new UnsupportedOperationException("STUB: Return whatever is provided by connect()");
//  }
// }
