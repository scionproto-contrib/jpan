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

package org.scion.jpan;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.NotYetConnectedException;
import org.scion.jpan.internal.InternalConstants;
import org.scion.jpan.internal.ScionHeaderParser;

public class ScionDatagramChannel extends AbstractDatagramChannel<ScionDatagramChannel>
    implements ByteChannel, Closeable {

  protected ScionDatagramChannel(ScionService service, java.nio.channels.DatagramChannel channel)
      throws IOException {
    super(service, channel);
  }

  public static ScionDatagramChannel open() throws IOException {
    return open(null);
  }

  public static ScionDatagramChannel open(ScionService service) throws IOException {
    return open(service, java.nio.channels.DatagramChannel.open());
  }

  public static ScionDatagramChannel open(
      ScionService service, java.nio.channels.DatagramChannel channel) throws IOException {
    return new ScionDatagramChannel(service, channel);
  }

  // TODO we return `void` here. If we implement SelectableChannel
  //  this can be changed to return SelectableChannel.
  @Override
  public void configureBlocking(boolean block) throws IOException {
    super.configureBlocking(block);
  }

  @Override
  public boolean isBlocking() {
    return super.isBlocking();
  }

  public ScionSocketAddress receive(ByteBuffer userBuffer) throws IOException {
    readLock().lock();
    try {
      ByteBuffer buffer = getBufferReceive(userBuffer.capacity());
      ResponsePath receivePath = receiveFromChannel(buffer, InternalConstants.HdrTypes.UDP);
      if (receivePath == null) {
        return null; // non-blocking, nothing available
      }
      ScionHeaderParser.extractUserPayload(buffer, userBuffer);
      buffer.clear();
      return ScionSocketAddress.fromPath(receivePath);
    } finally {
      readLock().unlock();
    }
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
  public int send(ByteBuffer srcBuffer, SocketAddress destination) throws IOException {
    if (!(destination instanceof InetSocketAddress)) {
      throw new IllegalArgumentException("Address must be of type InetSocketAddress.");
    }
    if (destination instanceof ScionSocketAddress) {
      ScionSocketAddress ssa = (ScionSocketAddress) destination;
      send(srcBuffer, ssa);
      return 12345; // TODO
    }
    InetSocketAddress dstISA = (InetSocketAddress) destination;
    ScionService service = getOrCreateService();
    ScionSocketAddress dst = service.lookupSocketAddress(dstISA.getHostString(), dstISA.getPort());
    // TODO dst.refreshPath(service, getPathPolicy(), getCfgExpirySafetyMargin());
    send(srcBuffer, dst);
    return 12345; // TODO
  }

  /**
   * Attempts to send the content of the buffer to the destinationAddress.
   *
   * @param srcBuffer Data to send
   * @param address Path to destination. If this is a Request path and it is expired then it will
   *     automatically be replaced with a new path. Expiration of ResponsePaths is not checked
   * @return either the path argument or a new path if the path was an expired RequestPath. Note
   *     that ResponsePaths are not checked for expiration.
   * @throws IOException if an error occurs, e.g. if the destinationAddress is an IP address that
   *     cannot be resolved to an ISD/AS.
   * @see java.nio.channels.DatagramChannel#send(ByteBuffer, SocketAddress)
   */
  public void send(ByteBuffer srcBuffer, ScionSocketAddress address) throws IOException {
    writeLock().lock();
    try {
      ByteBuffer buffer = getBufferSend(srcBuffer.remaining());
      // + 8 for UDP overlay header length
      int len = srcBuffer.remaining() + 8;
      checkPathAndBuildHeader(buffer, address, len, InternalConstants.HdrTypes.UDP);
      try {
        buffer.put(srcBuffer);
      } catch (BufferOverflowException e) {
        throw new IOException("Packet is larger than max send buffer size.");
      }
      buffer.flip();
      sendRaw(buffer, address.getPath().getFirstHopAddress(), address.getPath());
      // TODO consider TRICK
      //    - If read()/write() is used then we have no problem, getCurrentPath() works fine
      //    - receive() always contains a ResponsePath -> cannot expire
      //    - send() on server -> we have a ResponsePath -> cannot be refreshed
      //    - send() on client -> we have a RequestPath -> use with getCurrentPath() ???
    } finally {
      writeLock().unlock();
    }
  }

  @Deprecated // Please use another send method instead
  public void send(ByteBuffer srcBuffer, Path path) throws IOException {
    send(srcBuffer, ScionSocketAddress.fromPath(path));
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
  public int read(ByteBuffer dst) throws IOException {
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
  public int write(ByteBuffer src) throws IOException {
    writeLock().lock();
    try {
      checkOpen();
      checkConnected(true);

      ByteBuffer buffer = getBufferSend(src.remaining());
      int len = src.remaining();
      // + 8 for UDP overlay header length
      checkPathAndBuildHeader(buffer, getRemoteAddress(), len + 8, InternalConstants.HdrTypes.UDP);
      buffer.put(src);
      buffer.flip();

      int sent = sendRaw(buffer, getConnectionPath().getFirstHopAddress(), getConnectionPath());
      if (sent < buffer.limit() || buffer.remaining() > 0) {
        throw new ScionException("Failed to send all data.");
      }
      return len - buffer.remaining();
    } catch (BufferOverflowException e) {
      throw new IOException("Source buffer larger than MTU", e);
    } finally {
      writeLock().unlock();
    }
  }
}
