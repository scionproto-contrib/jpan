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

package org.scion.jpan.testutil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Scmp;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.demo.inspector.ScmpHeader;
import org.scion.jpan.internal.ByteUtil;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.internal.ScmpParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockBorderRouter implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(MockBorderRouter.class.getName());

  private final int id;
  private final String name;
  private final InetSocketAddress bind1;
  private final InetSocketAddress bind2;
  private final int interfaceId1;
  private final int interfaceId2;

  MockBorderRouter(int id, InetSocketAddress bind1, InetSocketAddress bind2, int ifId1, int ifId2) {
    this.id = id;
    this.name = "BorderRouter-" + id;
    this.bind1 = bind1;
    this.bind2 = bind2;
    this.interfaceId1 = ifId1;
    this.interfaceId2 = ifId2;
  }

  @Override
  public void run() {
    Thread.currentThread().setName(name);
    try (DatagramChannel chnLocal = DatagramChannel.open().bind(bind1);
        DatagramChannel chnRemote = DatagramChannel.open().bind(bind2);
        Selector selector = Selector.open()) {
      chnLocal.configureBlocking(false);
      chnRemote.configureBlocking(false);
      chnLocal.register(selector, SelectionKey.OP_READ, chnRemote);
      chnRemote.register(selector, SelectionKey.OP_READ, chnLocal);
      ByteBuffer buffer = ByteBuffer.allocate(66000);
      MockNetwork.barrier.countDown();
      logger.info("{} started on ports {} <-> {}", name, bind1, bind2);

      while (true) {
        if (selector.select() == 0) {
          // This must be an interrupt
          selector.close();
          return;
        }

        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        while (iter.hasNext()) {
          SelectionKey key = iter.next();
          iter.remove();
          if (key.isReadable()) {
            DatagramChannel incoming = (DatagramChannel) key.channel();
            DatagramChannel outgoing = (DatagramChannel) key.attachment();
            buffer.clear();
            SocketAddress srcAddress = incoming.receive(buffer);
            if (srcAddress == null) {
              throw new IllegalStateException();
            }
            buffer.flip();

            if (checkStun(buffer, srcAddress, incoming)) {
              continue;
            }

            if (MockNetwork.dropNextPackets.get() > 0) {
              MockNetwork.dropNextPackets.decrementAndGet();
              continue;
            }

            Scmp.TypeCode errorCode = MockNetwork.scmpErrorOnNextPacket.getAndSet(null);
            if (errorCode != null) {
              sendScmp(errorCode, buffer, srcAddress, incoming);
              continue;
            }

            switch (PackageVisibilityHelper.getNextHdr(buffer)) {
              case UDP:
                forwardPacket(buffer, srcAddress, outgoing);
                break;
              case SCMP:
                handleScmp(buffer, srcAddress, outgoing);
                break;
              default:
                logger.error(
                    "HDR not supported: {}", PackageVisibilityHelper.getNextHdr(buffer).code());
                throw new UnsupportedOperationException();
            }
          }
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      logger.info("Shutting down router");
    }
  }

  private void forwardPacket(ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel outgoing)
      throws IOException {
    InetSocketAddress dstAddress = PackageVisibilityHelper.getDstAddress(buffer);
    if (MockNetwork.useShim()) {
      dstAddress = MockNetwork.asInfo.mapDispatcherPorts(dstAddress);
    }
    logger.info(
        "{} forwarding {} bytes from {} to {}", name, buffer.remaining(), srcAddress, dstAddress);

    outgoing.send(buffer, dstAddress);
    buffer.clear();
    MockNetwork.nForwardTotal.incrementAndGet();
    MockNetwork.nForwards.incrementAndGet(id);
  }

  private void handleScmp(ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel outgoing)
      throws IOException {
    Scmp.Type type0 = ScmpParser.extractType(buffer);
    // ignore SCMP responses
    if (type0 == Scmp.Type.INFO_129 || type0 == Scmp.Type.INFO_131) {
      // always forward responses
      buffer.rewind();
      forwardPacket(buffer, srcAddress, outgoing);
      return;
    }

    // Forward packet to DST unless it is meant for us (us=bind1).
    InetSocketAddress dstIP = ScionHeaderParser.extractDestinationSocketAddress(buffer);
    if (dstIP != null && type0 == Scmp.Type.INFO_128 && dstIP.getAddress() != bind1.getAddress()) {
      buffer.rewind();
      forwardPacket(buffer, srcAddress, outgoing);
      return;
    }

    // relay to ScmpHandler
    buffer.rewind();

    InetSocketAddress scmpDst = MockScmpHandler.getAddress();
    logger.info(
        "{} relaying {} bytes SCMP from {} to {}", name, buffer.remaining(), srcAddress, scmpDst);
    outgoing.send(buffer, scmpDst);
    buffer.clear();
  }

  private void sendScmp(
      Scmp.TypeCode type, ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel channel)
      throws IOException {
    // send back!
    byte[] payload = new byte[buffer.remaining()];
    buffer.get(payload);

    buffer.rewind();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(buffer);
    spi.reversePath();
    ScmpHeader scmpHeader = spi.getScmpHeader();
    scmpHeader.setCode(type);
    scmpHeader.setErrorPayload(payload);
    ByteBuffer out = ByteBuffer.allocate(1232); // 1232 limit, see spec
    spi.writePacketSCMP(out);
    out.flip();
    channel.send(out, srcAddress);
    buffer.clear();
  }

  public int getPort1() {
    return bind1.getPort();
  }

  public InetSocketAddress getAddress1() {
    return bind1;
  }

  public int getInterfaceId1() {
    return interfaceId1;
  }

  public int getInterfaceId2() {
    return interfaceId2;
  }

  private boolean checkStun(ByteBuffer in, SocketAddress srcAddress, DatagramChannel incoming)
      throws IOException {
    if (in.getInt(4) != 0x2112A442) {
      return false;
    }
    if (!MockNetwork.enableStun.get()) {
      // read, but do not respond.
      return true;
    }
    MockNetwork.nStunRequests.incrementAndGet();
    // Let's assume this is a valid STUN packet.
    ByteBuffer out = ByteBuffer.allocate(60000);
    out.putShort(ByteUtil.toShort(0x0101));
    // length
    out.putShort((short) 0);

    // Tx ID
    out.putInt(in.getInt(4));
    out.putInt(in.getInt(8));
    out.putInt(in.getInt(12));
    out.putInt(in.getInt(16));

    // Mapped address attribute
    // type
    out.putShort(ByteUtil.toShort(0x0001));
    byte[] addrBytes = ((InetSocketAddress) srcAddress).getAddress().getAddress();
    // length
    out.putShort(ByteUtil.toShort(addrBytes.length + 4));
    // 0, family, port
    out.put((byte) 0);
    out.put((byte) (addrBytes.length == 4 ? 0x01 : 0x02));
    out.putShort(ByteUtil.toShort(((InetSocketAddress) srcAddress).getPort()));

    // address bytes
    out.put(addrBytes);

    // update packet length
    out.putShort(2, ByteUtil.toShort(out.position() - 20));

    if (MockNetwork.stunCallback.get() != null) {
      // If callback returns true we send the packet (which may have been altered)
      if (!MockNetwork.stunCallback.get().test(out)) {
        return true;
      }
    }

    out.flip();
    incoming.send(out, srcAddress);
    return true;
  }
}
