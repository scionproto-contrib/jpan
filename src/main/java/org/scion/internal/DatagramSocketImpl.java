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

package org.scion.internal;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Set;
import org.scion.DatagramChannel;
import org.scion.Path;

public class DatagramSocketImpl extends java.net.DatagramSocketImpl {

  private final boolean isForBind;
  private DatagramChannel channel;
  private int port;

  public DatagramSocketImpl(int port) {
    this.port = port;
    throw new UnsupportedOperationException();
  }

  public DatagramSocketImpl(boolean isForBind) {
    this.isForBind = isForBind;
  }

  /**
   * @throws SocketException
   * @see java.net.DatagramSocketImpl#create()
   */
  @Override
  protected void create() throws SocketException {
    try {
      channel = DatagramChannel.open();
      channel.configureBlocking(true);
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  /**
   * @param port the local port
   * @param inetAddress the local address
   * @throws SocketException in case of a problem with the underlying protocol
   * @see java.net.DatagramSocketImpl#bind(int, InetAddress)
   */
  @Override
  protected void bind(int port, InetAddress inetAddress) throws SocketException {
    try {
      channel.bind(new InetSocketAddress(inetAddress, port));
    } catch (IOException e) {
      throw new SocketException(e.getMessage());
    }
  }

  /**
   * @param datagramPacket the packet to be sent.
   * @throws IOException
   * @see java.net.DatagramSocketImpl#send(DatagramPacket)
   */
  @Override
  protected void send(DatagramPacket datagramPacket) throws IOException {
    ByteBuffer buf =
        ByteBuffer.wrap(
            datagramPacket.getData(), datagramPacket.getOffset(), datagramPacket.getLength());
    channel.send(buf, datagramPacket.getSocketAddress());
  }

  /**
   * @param inetAddress an InetAddress object
   * @return
   * @throws IOException
   * @see java.net.DatagramSocketImpl#peek(InetAddress)
   */
  @Override
  protected int peek(InetAddress inetAddress) throws IOException {
    throw new UnsupportedOperationException(); // TODO
    // inetAddress.
    // return 0;
  }

  /**
   * @param datagramPacket the Packet Received.
   * @return
   * @throws IOException
   * @see java.net.DatagramSocketImpl#peekData(DatagramPacket)
   */
  @Override
  protected int peekData(DatagramPacket datagramPacket) throws IOException {
    throw new UnsupportedOperationException(); // TODO
    //    return 0;
  }

  /**
   * @param datagramPacket the Packet Received.
   * @throws IOException
   * @see java.net.DatagramSocketImpl#receive(DatagramPacket)
   */
  @Override
  protected void receive(DatagramPacket datagramPacket) throws IOException {
    // throw new UnsupportedOperationException(); // TODO
    ByteBuffer buf =
        ByteBuffer.wrap(
            datagramPacket.getData(), datagramPacket.getOffset(), datagramPacket.getLength());
    Path a = channel.receive(buf);
    // System.out.println("buf:" + buf.limit() + "  p=" + buf.position() + "  rem=" +
    // buf.remaining());
    buf.flip();
    // System.out.println("buf-f:" + buf.limit() + "  p=" + buf.position() + "  rem=" +
    // buf.remaining());
    datagramPacket.setLength(buf.limit()); // TODO offset?
    datagramPacket.setPort(a.getPort());
    datagramPacket.setAddress(a.getAddress()); // TODO setSocketAddress() ?
  }

  /**
   * @param b a byte specifying the TTL value
   * @throws IOException
   * @see java.net.DatagramSocketImpl#setTTL(byte)
   */
  @Override
  protected void setTTL(byte b) throws IOException {
    throw new UnsupportedOperationException(); // TODO
  }

  /**
   * @return
   * @throws IOException
   * @see java.net.DatagramSocketImpl#getTTL()
   */
  @Override
  protected byte getTTL() throws IOException {
    throw new UnsupportedOperationException(); // TODO
    //    return 0;
  }

  /**
   * @param i an {@code int} specifying the time-to-live value
   * @throws IOException
   * @see java.net.DatagramSocketImpl#setTimeToLive(int)
   */
  @Override
  protected void setTimeToLive(int i) throws IOException {
    throw new UnsupportedOperationException(); // TODO
  }

  /**
   * @return
   * @throws IOException
   * @see java.net.DatagramSocketImpl#getTimeToLive()
   */
  @Override
  protected int getTimeToLive() throws IOException {
    throw new UnsupportedOperationException(); // TODO
    //    return 0;
  }

  @Override
  protected void join(InetAddress inetAddress) throws IOException {
    throw new UnsupportedOperationException(); // TODO
  }

  @Override
  protected void leave(InetAddress inetAddress) throws IOException {
    throw new UnsupportedOperationException(); // TODO
  }

  @Override
  protected void joinGroup(SocketAddress socketAddress, NetworkInterface networkInterface)
      throws IOException {
    throw new UnsupportedOperationException(); // TODO
  }

  @Override
  protected void leaveGroup(SocketAddress socketAddress, NetworkInterface networkInterface)
      throws IOException {
    throw new UnsupportedOperationException(); // TODO
  }

  /**
   * @see java.net.DatagramSocketImpl#close()
   */
  @Override
  protected void close() {
    try {
      channel.close();
    } catch (IOException e) {
      throw new RuntimeException(e); // TODO ?s
    }
  }

  @Override
  public void setOption(int i, Object o) throws SocketException {
    throw new UnsupportedOperationException(); // TODO
  }

  @Override
  public Object getOption(int i) throws SocketException {
    throw new UnsupportedOperationException(); // TODO
    //    return null;
  }

  /**
   * @see java.net.DatagramSocketImpl#disconnect()
   */
  @Override
  protected void disconnect() {
    super.disconnect();
  }

  // TODO ? @Override
  protected int dataAvailable() {
    throw new UnsupportedOperationException();
  }

  @Override
  protected void connect(InetAddress a, int port) {
    throw new UnsupportedOperationException();
  }

  protected void setDatagramSocket(Object s) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected int getLocalPort() {
    return super.getLocalPort();
  }

  protected SocketAddress getLocalAddress() throws IOException {
    return channel.getLocalAddress();
  }

  protected Set<SocketOption<?>> supportedOptions9() {
    throw new UnsupportedOperationException();
  }

  public <T> T getOption9(SocketOption<T> name) throws IOException {
    throw new UnsupportedOperationException();
  }

  public <T> void setOption9(SocketOption<T> name, T value) throws IOException {
    throw new UnsupportedOperationException();
  }
}
