// Copyright 2025 ETH Zurich
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

package org.scion.jpan.api;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.DatagramChannel;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.internal.*;
import org.scion.jpan.internal.header.ScionHeaderParser;
import org.scion.jpan.internal.util.ByteUtil;
import org.scion.jpan.testutil.MockDatagramChannel;
import org.scion.jpan.testutil.MockNetwork2;

class CustomChannelTest {

  /**
   * This is NOT a TCP channel. This test just validates that implementing something like a TCP
   * channel is possible on top of the AbstractScionChannel.
   */
  private static class TcpChannel extends PackageVisibilityHelper.AbstractChannel
      implements ByteChannel {

    private static final int PROTOCOL_ID_TCP = 6;
    private static final int DATA_OFFSET_WORDS = 5;

    static TcpChannel open() throws IOException {
      return new TcpChannel(
          Scion.defaultService(),
          DatagramChannel.open(),
          PathProviderNoOp.create(PathPolicy.DEFAULT));
    }

    static TcpChannel open(DatagramChannel channel) {
      return new TcpChannel(
          Scion.defaultService(), channel, PathProviderNoOp.create(PathPolicy.DEFAULT));
    }

    protected TcpChannel(ScionService service, DatagramChannel channel, PathProvider pathProvider) {
      super(service, channel, pathProvider);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      writeLock().lock();
      try {
        checkOpen();
        checkConnected(true);
        Path path = getConnectionPath();

        ByteBuffer buffer = getBufferSend(src.remaining());
        int len = src.remaining();
        checkPathAndBuildHeaderCUSTOM(buffer, path, len);
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

    private void checkPathAndBuildHeaderCUSTOM(ByteBuffer buffer, Path path, int payloadLength)
        throws IOException {
      synchronized (super.stateLock()) {
        // + 8 for UDP overlay header length
        ByteUtil.MutInt srcPort = new ByteUtil.MutInt(-1);
        int length = payloadLength + DATA_OFFSET_WORDS * 4;
        buildHeader(buffer, path, length, PROTOCOL_ID_TCP, srcPort);
        int dstPort = path.getRemotePort();

        // write CUSTOM header
        buffer.putShort(ByteUtil.toShort(srcPort.get()));
        buffer.putShort(ByteUtil.toShort(dstPort));
        buffer.putInt(12345); // sequence number
        buffer.putInt(12344); // ACK number
        buffer.put(ByteUtil.toByte(DATA_OFFSET_WORDS << 4)); // data offset
        buffer.put((byte) 0); // flags
        buffer.putShort(ByteUtil.toShort(0)); // window
        buffer.putShort(ByteUtil.toShort(0)); // checksum
        buffer.putShort(ByteUtil.toShort(0)); // URG
      }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
      readLock().lock();
      try {
        checkOpen();
        checkConnected(true);
        int oldPos = dst.position();

        ByteBuffer buffer = getBufferReceive(dst.capacity());
        ResponsePath receivePath = receiveFromChannel(buffer, PROTOCOL_ID_TCP);
        if (receivePath == null) {
          return 0; // non-blocking, nothing available
        }
        ScionHeaderParser.extractUserPayload(buffer, dst);
        buffer.clear();
        return dst.position() - oldPos;
      } finally {
        readLock().unlock();
      }
    }

    @Override
    public void configureBlocking(boolean block) throws IOException {
      super.configureBlocking(block);
    }
  }

  private MockDatagramChannel mockChannel(Path path) throws IOException {
    MockDatagramChannel mockChannel = MockDatagramChannel.open();
    ByteBuffer response = ByteBuffer.allocate(1000);
    mockChannel.setSendCallback(
        (request, socketAddress) -> {
          createResponse(request, response);
          return request.limit(); // ignores offset for now
        });
    mockChannel.setReceiveCallback(
        buffer -> {
          response.flip();
          if (response.remaining() == 0) {
            return null;
          }
          buffer.put(response);
          response.clear();
          // This is simply to make it work with MockNetwork2
          return path.getFirstHopAddress();
        });
    return mockChannel;
  }

  private void createResponse(ByteBuffer orig, ByteBuffer response) {
    response.clear();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(orig);
    spi.reversePath();
    spi.writePacketCUSTOM(response, TcpChannel.PROTOCOL_ID_TCP);
  }

  @Test
  void test() throws IOException {
    // TODO see https://www.baeldung.com/java-nio2-async-socket-channel

    try (MockNetwork2 nw = MockNetwork2.start(MockNetwork2.Topology.TINY4, "ASff00_0_111")) {

      // client channel
      Path path = PackageVisibilityHelper.createDummyPath();
      try (TcpChannel channel = TcpChannel.open(mockChannel(path))) {
        channel.configureBlocking(false);
        channel.connect(path);
        channel.write(ByteBuffer.allocate(0));
        channel.read(ByteBuffer.allocate(1000));
      }

      // Server channel
      try (TcpChannel channel = TcpChannel.open(mockChannel(path))) {
        channel.configureBlocking(false);
        channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345));
        channel.connect(path);
        channel.read(ByteBuffer.allocate(100));
        channel.write(ByteBuffer.allocate(0));
      }
    }
  }
}
