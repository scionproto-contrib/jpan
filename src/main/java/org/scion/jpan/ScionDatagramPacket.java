// Copyright 2026 ETH Zurich
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

package org.scion.jpan;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketAddress;

/**
 * This is a drop-in replacement for the Java {@link DatagramPacket} class. Unlike the
 * DatagramPacket, this class support Scion network addresses. <br>
 * This class should be used instead of {@link DatagramPacket} whenever possible because it avoids
 * path mapping inside the Scion DatagramSocket and thus makes the socket faster, use less memory,
 * and safer (e.g. protects against memory exhaustion attacks).
 */
public class ScionDatagramPacket {

  private Path path;
  private byte[] buf;
  private int offset;
  private int length;

  /**
   * Constructs a DatagramPacket.
   *
   * @param buf buffer
   * @param offset the offset in the buffer
   * @param length buffer size
   * @see DatagramPacket#DatagramPacket(byte[], int, int)
   */
  public ScionDatagramPacket(byte[] buf, int offset, int length) {
    setData(buf, offset, length);
  }

  /**
   * Constructs a DatagramPacket.
   *
   * @param buf the packet data.
   * @param length the packet data length.
   * @see java.net.DatagramPacket#DatagramPacket)
   */
  public ScionDatagramPacket(byte[] buf, int length) {
    this(buf, 0, length);
  }

  /**
   * Constructs a datagram packet.
   *
   * @param buf the packet data.
   * @param offset the packet data offset.
   * @param length the packet data length.
   * @param path the path to the destination.
   * @throws IllegalArgumentException if address type is not supported
   * @see java.net.DatagramPacket#DatagramPacket(byte[], int, int, SocketAddress)
   */
  public ScionDatagramPacket(byte[] buf, int offset, int length, Path path) {
    setData(buf, offset, length);
    setPath(path);
  }

  /**
   * Constructs a datagram packet.
   *
   * @param buf the packet data.
   * @param length the packet length.
   * @param address the path to the destination.
   * @throws IllegalArgumentException if address type is not supported
   * @see java.net.DatagramPacket#DatagramPacket(byte[], int, SocketAddress)
   */
  public ScionDatagramPacket(byte[] buf, int length, Path address) {
    this(buf, 0, length, address);
  }

  /**
   * IP address.
   *
   * @return the IP address.
   * @see DatagramPacket#getAddress()
   * @see DatagramPacket#getAddress()
   */
  public synchronized InetAddress getAddress() {
    return path.getRemoteAddress();
  }

  /**
   * Port number.
   *
   * @return the port number.
   * @see DatagramPacket#setPort(int)
   */
  public synchronized int getPort() {
    return path.getRemotePort();
  }

  /**
   * Data buffer.
   *
   * @return the buffer
   * @see DatagramPacket#setData(byte[], int, int)
   */
  public synchronized byte[] getData() {
    return buf;
  }

  /**
   * The offset.
   *
   * @return the offset
   * @see DatagramPacket#getOffset()
   */
  public synchronized int getOffset() {
    return offset;
  }

  /**
   * Data length in bytes.
   *
   * @return the length.
   * @see DatagramPacket#getLength()
   */
  public synchronized int getLength() {
    return length;
  }

  /**
   * Set the data buffer.
   *
   * @param buf the buffer
   * @param offset the offset
   * @param length the length
   * @exception NullPointerException if the argument is null
   * @see DatagramPacket#setData(byte[], int, int)
   */
  public synchronized void setData(byte[] buf, int offset, int length) {
    if (length < 0 || offset < 0 || (length + offset) < 0 || ((length + offset) > buf.length)) {
      throw new IllegalArgumentException("Illegal length or offset");
    }
    this.buf = buf;
    this.length = length;
    this.offset = offset;
  }

  /**
   * Get the ScionSocketAddress.
   *
   * @return the {@code ScionSocketAddress}
   * @see DatagramPacket#getSocketAddress()
   */
  public synchronized ScionSocketAddress getSocketAddress() {
    return path.getRemoteSocketAddress();
  }

  /**
   * Set the ScionSocketAddress.
   *
   * @param path the {@code Path}
   * @see DatagramPacket#setSocketAddress(SocketAddress)
   */
  public synchronized void setPath(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("Path argument must not be null");
    }
    this.path = path;
  }

  /**
   * Get the Path.
   *
   * @return the {@code ScionSocketAddress}
   * @see DatagramPacket#getSocketAddress()
   */
  public synchronized Path getPath() {
    return path;
  }

  /**
   * Set the data.
   *
   * @param buf the data buffer.
   * @exception NullPointerException if the argument is null.
   * @see DatagramPacket#setData(byte[])
   */
  public synchronized void setData(byte[] buf) {
    if (buf == null) {
      throw new NullPointerException("Buffer argument must not be null");
    }
    this.buf = buf;
    this.offset = 0;
    this.length = buf.length;
  }

  /**
   * Set the length of the packet.
   *
   * @param length the length
   * @see DatagramPacket#setLength(int)
   */
  public synchronized void setLength(int length) {
    if ((length + offset) > buf.length || length < 0 || (length + offset) < 0) {
      throw new IllegalArgumentException("illegal length");
    }
    this.length = length;
  }
}
