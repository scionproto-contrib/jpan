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
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.time.Instant;
import java.util.List;
import java.util.WeakHashMap;
import org.scion.jpan.internal.InternalConstants;
import org.scion.jpan.internal.ScionHeaderParser;

public class ScionDatagramChannel extends AbstractDatagramChannel<ScionDatagramChannel>
    implements ByteChannel, Closeable {

  public enum RefreshPolicy {
    /** No refresh. */
    OFF,
    /** Refresh with path along the same links. */
    SAME_LINKS,
    /** Refresh with path path following path policy. */
    POLICY
  }

  // Store on path per (non-Scion-)destination address
  private final WeakHashMap<InetSocketAddress, RequestPath> resolvedDestinations =
      new WeakHashMap<>();
  // Store a refreshed paths for every path
  private final WeakHashMap<Path, RequestPath> refreshedPaths = new WeakHashMap<>();

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
      return receivePath.getRemoteSocketAddress();
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
      return send(srcBuffer, ((ScionSocketAddress) destination).getPath(), RefreshPolicy.OFF);
    }

    InetSocketAddress dst = (InetSocketAddress) destination;
    RequestPath path;
    synchronized (stateLock()) {
      path = resolvedDestinations.get(dst);
      if (path == null) {
        path = (RequestPath) getOrCreateService().lookupAndGetPath(dst, getPathPolicy());
        resolvedDestinations.put(dst, path);
      }
    }
    return send(srcBuffer, path, RefreshPolicy.POLICY);
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
    return send(srcBuffer, path, RefreshPolicy.SAME_LINKS);
  }

  private int send(ByteBuffer srcBuffer, Path path, RefreshPolicy refresh) throws IOException {
    writeLock().lock();
    try {
      ByteBuffer buffer = getBufferSend(srcBuffer.remaining());
      checkPathAndBuildHeaderUDP(buffer, path, srcBuffer.remaining(), refresh);
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
      Path path = getConnectionPath();

      ByteBuffer buffer = getBufferSend(src.remaining());
      int len = src.remaining();
      checkPathAndBuildHeaderUDP(buffer, path, len, RefreshPolicy.POLICY);
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
  private void checkPathAndBuildHeaderUDP(
      ByteBuffer buffer, Path path, int payloadLength, RefreshPolicy rf) throws IOException {
    synchronized (super.stateLock()) {
      if (path instanceof RequestPath) {
        RequestPath requestPath = (RequestPath) path;
        RequestPath newPath = refreshPath(requestPath, rf);
        if (newPath != null) {
          refreshedPaths.put(path, newPath);
          updateConnection(requestPath, true);
        }
      }
      // + 8 for UDP overlay header length
      buildHeader(buffer, path, payloadLength + 8, InternalConstants.HdrTypes.UDP);
    }
  }

  /**
   * Checks whether the current path is expired and requests and assigns a new path if required.
   *
   * @param path RequestPath that may need refreshing
   * @param refreshPolicy Path refresh policy
   * @return a new Path if the path was updated, otherwise `null`.
   */
  private RequestPath refreshPath(RequestPath path, RefreshPolicy refreshPolicy) {
    int expiryMargin = getCfgExpirationSafetyMargin();
    if (Instant.now().getEpochSecond() + expiryMargin <= path.getMetadata().getExpiration()) {
      return null;
    }
    // expired, get new path
    List<Path> paths = getOrCreateService().getPaths(path);
    switch (refreshPolicy) {
      case OFF:
        // let this pass until it is ACTUALLY expired
        if (Instant.now().getEpochSecond() <= path.getMetadata().getExpiration()) {
          return path;
        }
        throw new ScionRuntimeException("Path is expired");
      case POLICY:
        return (RequestPath) getPathPolicy().filter(getOrCreateService().getPaths(path));
      case SAME_LINKS:
        return findPathSameLinks(paths, path);
      default:
        throw new UnsupportedOperationException();
    }
  }

  private RequestPath findPathSameLinks(List<Path> paths, RequestPath path) {
    List<PathMetadata.PathInterface> reference = path.getMetadata().getInterfacesList();
    for (Path newPath : paths) {
      List<PathMetadata.PathInterface> ifs = newPath.getMetadata().getInterfacesList();
      if (ifs.size() != reference.size()) {
        continue;
      }
      boolean isSame = true;
      for (int i = 0; i < ifs.size(); i++) {
        // In theory we could compare only the first ISD/AS and then only Interface IDs....
        PathMetadata.PathInterface if1 = ifs.get(i);
        PathMetadata.PathInterface if2 = reference.get(i);
        if (if1.getIsdAs() != if2.getIsdAs() || if1.getId() != if2.getId()) {
          isSame = false;
          break;
        }
      }
      if (isSame) {
        return (RequestPath) newPath;
      }
    }
    return null;
  }

  /**
   * The channel maintains mappings from input address to output paths. Input addresses are given as
   * input to {@link #send(ByteBuffer, SocketAddress)}. The channel tries to resolve the address via
   * DNS TXT record to a SCION enabled address and stores the result in the mapping for future use.
   *
   * @param address A destination address
   * @return The mapped path or the path itself if no mapping is available.
   */
  public Path getMappedPath(InetSocketAddress address) {
    synchronized (stateLock()) {
      return resolvedDestinations.get(address);
    }
  }

  /**
   * The channel maintains mappings from input path to output paths. Input paths are given as input
   * to {@link #send(ByteBuffer, Path)}. If the path is valid, it is also used as actual path for
   * any packet sent to the destination. If the path has expired, the channel will try to find an
   * identical, but more recent path and store it in the mapping.
   *
   * @param path A Path
   * @return The mapped path or the path itself if no mapping is available.
   */
  public Path getMappedPath(Path path) {
    if (!(path instanceof RequestPath)) {
      return null;
    }
    synchronized (stateLock()) {
      return refreshedPaths.getOrDefault(path, (RequestPath) path);
    }
  }
}
