// Copyright 2024 ETH Zurich
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

package org.scion.socket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import org.scion.RequestPath;
import org.scion.ResponsePath;
import org.scion.ScionService;
import org.scion.internal.InternalConstants;
import org.scion.internal.ScionHeaderParser;

/**
 * DatagramChannel with support for timeout.
 *
 * <p>The class is non-public for now. It may be removed (or not) once we implement Selectors.
 */
class SelectingDatagramChannel extends org.scion.DatagramChannel {
  private final Selector selector;
  private int timeoutMs = 0;

  SelectingDatagramChannel(ScionService service) throws IOException {
    super(service, DatagramChannel.open());

    // selector
    this.selector = Selector.open();
    super.channel().configureBlocking(false);
    super.channel().register(selector, SelectionKey.OP_READ);
  }

  SelectingDatagramChannel(ScionService service, RequestPath path, int port) throws IOException {
    this(service);
    super.setConnectionPath(path);
    // listen on ANY interface: 0.0.0.0 / [::]
    super.bind(new InetSocketAddress("[::]", port));
  }

  public void setTimeOut(int timeoutMilliseconds) {
    this.timeoutMs = timeoutMilliseconds;
  }

  public int getTimeOut() {
    return this.timeoutMs;
  }

  private ResponsePath receiveFromTimeoutChannel(
      ByteBuffer buffer, InternalConstants.HdrTypes expectedHdrType) throws IOException {
    while (true) {
      buffer.clear();
      if (selector.select(timeoutMs) == 0) {
        return null;
      }

      Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
      if (iter.hasNext()) {
        SelectionKey key = iter.next();
        iter.remove();
        if (key.isReadable()) {
          java.nio.channels.DatagramChannel incoming = (DatagramChannel) key.channel();
          InetSocketAddress srcAddress = (InetSocketAddress) incoming.receive(buffer);
          buffer.flip();
          if (validate(buffer)) {
            InternalConstants.HdrTypes hdrType = ScionHeaderParser.extractNextHeader(buffer);
            // From here on we use linear reading using the buffer's position() mechanism
            buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
            // Check for extension headers.
            // This should be mostly unnecessary, however we sometimes saw SCMP error headers
            // wrapped
            // in extensions headers.
            hdrType = receiveExtensionHeader(buffer, hdrType);

            ResponsePath path = ScionHeaderParser.extractResponsePath(buffer, srcAddress);
            if (hdrType == expectedHdrType) {
              return path;
            }
            receiveScmp(buffer, path);
          }
        }
      }
    }
  }

  //    protected ResponsePath receiveFromChannel(
  //            ByteBuffer buffer, InternalConstants.HdrTypes expectedHdrType) throws IOException {
  //        while (true) {
  //            buffer.clear();
  //            InetSocketAddress srcAddress = (InetSocketAddress) channel.receive(buffer);
  //            if (srcAddress == null) {
  //                // this indicates nothing is available - non-blocking mode
  //                return null;
  //            }
  //            buffer.flip();
  //
  //            if (!validate(buffer.asReadOnlyBuffer())) {
  //                continue;
  //            }
  //
  //            InternalConstants.HdrTypes hdrType = ScionHeaderParser.extractNextHeader(buffer);
  //            // From here on we use linear reading using the buffer's position() mechanism
  //            buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
  //            // Check for extension headers.
  //            // This should be mostly unnecessary, however we sometimes saw SCMP error headers
  // wrapped
  //            // in extensions headers.
  //            hdrType = receiveExtensionHeader(buffer, hdrType);
  //
  //            ResponsePath path = ScionHeaderParser.extractResponsePath(buffer, srcAddress);
  //            if (hdrType == expectedHdrType) {
  //                return path;
  //            }
  //            receiveScmp(buffer, path);
  //        }
  //    }

  public synchronized ResponsePath receive(ByteBuffer userBuffer) throws IOException {
    ResponsePath receivePath =
        receiveFromTimeoutChannel(bufferReceive(), InternalConstants.HdrTypes.UDP);
    if (receivePath == null) {
      return null; // non-blocking, nothing available
    }
    ScionHeaderParser.extractUserPayload(bufferReceive(), userBuffer);
    bufferReceive().clear();
    return receivePath;
  }

  @Override
  public void close() throws IOException {
    super.close();
    selector.close();
  }
}