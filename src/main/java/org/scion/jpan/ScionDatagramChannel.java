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
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.util.WeakHashMap;
import org.scion.jpan.internal.*;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.internal.header.ScionHeaderParser;
import org.scion.jpan.internal.util.ByteUtil;
import org.scion.jpan.paths.PathSelectorFactory;

public class ScionDatagramChannel extends AbstractScionChannel<ScionDatagramChannel>
    implements ByteChannel, Closeable {

  // Store one path per (non-Scion-)destination address
  private final WeakHashMap<InetSocketAddress, PathProvider> resolvedDestinations =
      new WeakHashMap<>();

  protected ScionDatagramChannel(
      ScionService service, java.nio.channels.DatagramChannel channel, PathSelectorFactory factory)
      throws IOException {
    super(service, channel, factory);
  }

  /**
   * Creates a channel with the default ScionService.
   *
   * @return new channel
   * @throws IOException in case of an error
   */
  public static ScionDatagramChannel open() throws IOException {
    return open(Scion.defaultService());
  }

  /**
   * Creates a channel with a specific ScionService instance. The instance can be 'null'.
   *
   * <p>Use of 'null' is not recommended but can be used for server side channels if no topology
   * file is available. The ScionService is required to look up and refresh paths. It is also
   * required to determine addresses and ports of border routers.
   *
   * <p>A server side channel (a channel that only responds to packets by reversing their path) may
   * work without a service. However, having a service allows the server to look up the correct
   * border router port for the first hop. If no service is available, the port is determined from
   * the incoming IP header, assuming that the border router sends and receives on the same IP/port.
   * Unfortunately, this is not guaranteed to be the case, so using a service to look up the correct
   * port is recommended.
   *
   * @param service ScionService.
   * @return new channel
   * @throws IOException if an error occurs
   */
  public static ScionDatagramChannel open(ScionService service) throws IOException {
    return open(service, java.nio.channels.DatagramChannel.open());
  }

  public static ScionDatagramChannel open(
      ScionService service, java.nio.channels.DatagramChannel channel) throws IOException {
    return new Builder().service(service).channel(channel).open();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  // TODO we return `void` here. If we implement SelectableChannel
  //  this can be changed to return SelectableChannel.
  @Override
  public void configureBlocking(boolean block) throws IOException {
    super.configureBlocking(block);
  }

  public ScionSocketAddress receive(ByteBuffer userBuffer) throws IOException {
    readLock().lock();
    try {
      ByteBuffer buffer = getBufferReceive(userBuffer.capacity());
      ResponsePath receivePath = receiveFromChannel(buffer, HeaderConstants.HdrTypes.UDP.code());
      if (receivePath == null) {
        return null; // non-blocking, nothing available
      }
      ScionHeaderParser.extractUserPayload(buffer, userBuffer);
      buffer.clear();
      return receivePath.getRemoteSocketAddress();
    } finally {
      readLock().unlock();
    }
  }

  /**
   * Attempts to send the content of the buffer to the destinationAddress.
   *
   * <p>If the `destination` is of type {@link InetSocketAddress}, a path lookup is performed.<br>
   * Otherwise, if the `destination` is of type {@link ScionSocketAddress}, the contained path is
   * used directly.
   *
   * <p>When a path expires, it will be automatically refreshed. This behavior can be controlled
   * with path policies. For example, {@link PathPolicy.SameLink} can be used to ensure that any
   * refreshed path uses the exactly same links as a previously defined reference path.
   *
   * <p>Also, a path can only be refreshed if it was acquired via the ScionService. Paths that stem
   * from {@link #receive(ByteBuffer)} cannot be refreshed.
   *
   * @param srcBuffer Data to send
   * @param destination Destination address. This should contain a host name known to the DNS so
   *     that the ISD/AS information can be retrieved.
   * @return The number of bytes sent, see {@link java.nio.channels.DatagramChannel#send(ByteBuffer,
   *     SocketAddress)}.
   * @throws IOException if an error occurs, e.g. if the destinationAddress is an IP address that
   *     cannot be resolved to an ISD/AS.
   * @see java.nio.channels.DatagramChannel#send(ByteBuffer, SocketAddress)
   */
  public int send(ByteBuffer srcBuffer, SocketAddress destination) throws IOException {
    if (!(destination instanceof InetSocketAddress)) {
      throw new IllegalArgumentException("Address must be of type InetSocketAddress.");
    }
    if (destination instanceof ScionSocketAddress) {
      return sendInternal(srcBuffer, ((ScionSocketAddress) destination).getPath());
    }

    InetSocketAddress dst = (InetSocketAddress) destination;
    Path path;
    synchronized (stateLock()) {
      PathProvider pathProvider = resolvedDestinations.get(dst);
      if (pathProvider == null) {
        pathProvider = createPathProvider(dst);
        resolvedDestinations.put(dst, pathProvider);
      }
      path = pathProvider.getPath();
    }
    return sendInternal(srcBuffer, path);
  }

  /**
   * Attempts to send the content of the buffer to the destinationAddress.
   *
   * @param srcBuffer Data to send
   * @param path Path to destination. Expiration is *not* verified.
   * @return The number of bytes sent, see {@link java.nio.channels.DatagramChannel#send(ByteBuffer,
   *     SocketAddress)}.
   * @throws IOException if an error occurs, e.g. if the destinationAddress is an IP address that
   *     cannot be resolved to an ISD/AS.
   * @see java.nio.channels.DatagramChannel#send(ByteBuffer, SocketAddress)
   */
  public int send(ByteBuffer srcBuffer, Path path) throws IOException {
    return sendInternal(srcBuffer, path);
  }

  private int sendInternal(ByteBuffer srcBuffer, Path path) throws IOException {
    writeLock().lock();
    try {
      ByteBuffer buffer = getBufferSend(srcBuffer.remaining());
      checkPathAndBuildHeaderUDP(buffer, path, srcBuffer.remaining());
      int headerSize = buffer.position();
      try {
        buffer.put(srcBuffer);
      } catch (BufferOverflowException e) {
        throw new IOException("Packet is larger than max send buffer size.");
      }
      buffer.flip();
      int size = sendRaw(buffer, path);
      return size - headerSize;
    } finally {
      writeLock().unlock();
    }
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
      Path path = getConnectedPathOrThrow();

      ByteBuffer buffer = getBufferSend(src.remaining());
      int len = src.remaining();
      checkPathAndBuildHeaderUDP(buffer, path, len);
      buffer.put(src);
      buffer.flip();

      int sent = sendRaw(buffer, path);
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

  /**
   * @param path path
   * @param payloadLength payload length
   * @throws IOException in case of IOException.
   */
  private void checkPathAndBuildHeaderUDP(ByteBuffer buffer, Path path, int payloadLength)
      throws IOException {
    synchronized (super.stateLock()) {
      // + 8 for UDP overlay header length
      ByteUtil.MutInt srcPort = new ByteUtil.MutInt(-1);
      buildHeader(buffer, path, payloadLength + 8, HeaderConstants.HdrTypes.UDP.code(), srcPort);
      int dstPort = path.getRemotePort();
      ScionHeaderParser.writeUdpOverlayHeader(buffer, payloadLength, srcPort.get(), dstPort);
    }
  }

  /**
   * The channel maintains mappings from input address to output paths. Input addresses are given as
   * input to {@link #send(ByteBuffer, SocketAddress)}. The channel tries to resolve the address via
   * DNS TXT record to a SCION enabled address and stores the result in the mapping for future use.
   *
   * @param address A destination address
   * @return The mapped path or the path itself if no mapping is available.
   * @deprecated To be removed in 0.8.0
   */
  @Deprecated // remove in 0.8.0
  public Path getMappedPath(InetSocketAddress address) {
    synchronized (stateLock()) {
      PathProvider pp = resolvedDestinations.get(address);
      return pp == null ? null : pp.getPath();
    }
  }

  @Override
  public void close() throws IOException {
    super.close();
    for (PathProvider pathProvider : resolvedDestinations.values()) {
      // Warning: this will not disconnect() all pathProviders, they may have been GC'd already.
      // Their timer tasks may not have been cleaned up, but they will disappear over time
      // when they are executed.
      pathProvider.disconnect();
    }
  }

  public static class Builder {
    protected ScionService service;
    protected boolean nullService = false;
    protected PathSelectorFactory factory;
    protected DatagramChannel channel;

    /**
     * @param channel A {@link DatagramChannel} to be used. The default is the plain {@link
     *     DatagramChannel}.
     * @return This builder.
     */
    public Builder channel(DatagramChannel channel) {
      this.channel = channel;
      return this;
    }

    /**
     * @param factory A {@link PathSelectorFactory} to be used. If this value is not set, the
     *     default {@link PathSelectorFactory} is used.
     * @return This builder.
     */
    public Builder pathSelectorFactory(PathSelectorFactory factory) {
      this.factory = factory;
      return this;
    }

    /**
     * @param service A {@link ScionService} to be used. The default is the {@link
     *     ScionService#defaultService()}. The service can be explicitly set ,to `null` if no
     *     ScionService should be used.
     * @return This builder.
     */
    public Builder service(ScionService service) {
      this.service = service;
      this.nullService = service == null;
      return this;
    }

    public ScionDatagramChannel open() throws IOException {
      // Use defaultService() unless it was set explicitly to null.
      if (!nullService && service == null) {
        service = ScionService.defaultService();
      }

      if (channel == null) {
        channel = java.nio.channels.DatagramChannel.open();
      }

      if (factory == null) {
        if (service == null) {
          factory = PathSelectorFactory.NoOp.instance();
        } else {
          factory = PathSelectorFactory.Default.instance();
        }
      }

      return new ScionDatagramChannel(service, channel, factory);
    }
  }
}
