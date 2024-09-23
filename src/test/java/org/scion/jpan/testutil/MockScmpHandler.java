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

package org.scion.jpan.testutil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.jpan.*;
import org.scion.jpan.demo.inspector.HopField;
import org.scion.jpan.demo.inspector.PathHeaderScion;
import org.scion.jpan.demo.inspector.ScionPacketInspector;
import org.scion.jpan.demo.inspector.ScmpHeader;
import org.scion.jpan.internal.InternalConstants;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.internal.ScmpParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockScmpHandler implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(MockScmpHandler.class.getName());
  private static ExecutorService handler;
  private static final AtomicInteger nAnswerTotal = new AtomicInteger();
  private static CountDownLatch barrier = null;
  private static final AtomicReference<InetSocketAddress> address = new AtomicReference<>();

  private final String name;
  private final String ip;

  public static void start(String ip) {
    if (handler != null) {
      throw new IllegalStateException();
    }
    System.err.println("ScmpHandler-start starting " + Instant.now());
    barrier = new CountDownLatch(1);
    nAnswerTotal.set(0);
    handler = Executors.newSingleThreadExecutor();
    handler.execute(new MockScmpHandler(ip));
    try {
      barrier.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    //    while (true) {
    //      try (DatagramChannel c = DatagramChannel.open()) {
    //        InetSocketAddress handlerAddress = new InetSocketAddress(ip, Constants.SCMP_PORT);
    //        c.connect(handlerAddress);
    //      } catch (IOException e) {
    //          throw new RuntimeException(e);
    //      }
    //    }
    System.err.println("ScmpHandler-start done " + Instant.now());
  }

  public static void stop() {
    if (handler == null) {
      return;
    }
    try {
      handler.shutdownNow();
      // Wait a while for tasks to respond to being cancelled
      if (!handler.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.error("BorderRouterScmp did not terminate");
      }
      logger.info("BorderRouterScmp shut down");
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    handler = null;
    System.err.println("ScmpHandler-stop done");
  }

  public static InetSocketAddress getAddress() {
    return address.get();
  }

  public static int getAndResetAnswerTotal() {
    return nAnswerTotal.getAndSet(0);
  }

  private MockScmpHandler(String ip) {
    this.name = "BorderRouterScmp";
    this.ip = ip;
  }

  @Override
  public void run() {
    Thread.currentThread().setName(name);
    InetSocketAddress localAddress = new InetSocketAddress(ip, Constants.SCMP_PORT);
    try (DatagramChannel chn = DatagramChannel.open().bind(localAddress);
        Selector selector = Selector.open()) {
      chn.configureBlocking(false);
      chn.register(selector, SelectionKey.OP_READ, chn);
      ByteBuffer buffer = ByteBuffer.allocate(66000);
      address.set((InetSocketAddress) chn.getLocalAddress());
      logger.info("{} started on {}", name, localAddress);
      System.err.println("ScmpHandler-run() 1 " + Instant.now());
      barrier.countDown();

      while (true) {
        System.err.println("ScmpHandler-run() 2 " + Instant.now());
        if (selector.select() == 0) {
          // This must be an interrupt
          System.err.println("ScmpHandler-run() 4 " + Instant.now());
          selector.close();
          return;
        }
        System.err.println("ScmpHandler-run() 3 " + Instant.now());

        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        while (iter.hasNext()) {
          SelectionKey key = iter.next();
          if (key.isReadable()) {
            DatagramChannel incoming = (DatagramChannel) key.channel();
            SocketAddress srcAddress = incoming.receive(buffer);
            if (srcAddress == null) {
              throw new IllegalStateException();
            }
            buffer.flip();

            if (PackageVisibilityHelper.getNextHdr(buffer) == InternalConstants.HdrTypes.SCMP) {
              handleScmp(buffer, srcAddress, incoming);
              nAnswerTotal.incrementAndGet();
            } else {
              logger.error("Not supported: {}", PackageVisibilityHelper.getNextHdr(buffer).code());
              throw new UnsupportedOperationException();
            }
          }
          iter.remove();
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      logger.info("Shutting down BorderRouterScmp");
    }
  }

  private void handleScmp(ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel incoming)
      throws IOException {
    buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
    Scmp.Type type0 = ScmpParser.extractType(buffer);
    // ignore SCMP responses
    if (type0 == Scmp.Type.INFO_129 || type0 == Scmp.Type.INFO_131) {
      // always drop responses
      return;
    }

    buffer.rewind();
    InetSocketAddress dstAddress = PackageVisibilityHelper.getDstAddress(buffer);
    // From here on we use linear reading using the buffer's position() mechanism
    buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
    Path path = PackageVisibilityHelper.getResponsePath(buffer, (InetSocketAddress) srcAddress);
    Scmp.Type type = ScmpParser.extractType(buffer);
    Scmp.Message scmpMsg = PackageVisibilityHelper.createMessage(type, path);
    ScmpParser.consume(buffer, scmpMsg);
    logger.info(
        " received SCMP {} {}", scmpMsg.getTypeCode().name(), scmpMsg.getTypeCode().getText());

    if (scmpMsg instanceof Scmp.EchoMessage) {
      // send back!
      // This is very basic:
      // - we always answer regardless of whether we are actually the destination.
      // - We do not invert path / addresses
      sendEchoReply(buffer, srcAddress, incoming);
    } else if (scmpMsg instanceof Scmp.TracerouteMessage) {
      answerTraceRoute(buffer, srcAddress, incoming);
    } else {
      // forward error
      logger.info(
          "{} dropping SCMP error {} from {} to {}",
          name,
          scmpMsg.getTypeCode().getText(),
          srcAddress,
          dstAddress);
      buffer.clear();
    }
  }

  private void sendEchoReply(ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel channel)
      throws IOException {
    // send back!
    buffer.rewind();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(buffer);
    spi.reversePath();
    ScmpHeader scmpHeader = spi.getScmpHeader();
    scmpHeader.setCode(Scmp.TypeCode.TYPE_129);
    ByteBuffer out = ByteBuffer.allocate(100);
    spi.writePacketSCMP(out);
    out.flip();
    channel.send(out, srcAddress);
    buffer.clear();
  }

  private void answerTraceRoute(
      ByteBuffer buffer, SocketAddress srcAddress, DatagramChannel incoming) throws IOException {
    // This is very basic:
    // - we always answer regardless of whether we are actually the destination.
    buffer.rewind();
    ScionPacketInspector spi = ScionPacketInspector.readPacket(buffer);
    spi.reversePath();
    ScmpHeader scmpHeader = spi.getScmpHeader();
    scmpHeader.setCode(Scmp.TypeCode.TYPE_131);
    PathHeaderScion phs = spi.getPathHeaderScion();
    for (int i = 0; i < phs.getHopCount(); i++) {
      HopField hf = phs.getHopField(i);
      // These answers are hardcoded to work specifically with ScmpTest.traceroute()
      if (hf.hasEgressAlert()) {
        scmpHeader.setTraceData(ScionUtil.parseIA("1-ff00:0:112"), 42);
      }
      if (hf.hasIngressAlert()) {
        scmpHeader.setTraceData(ScionUtil.parseIA("1-ff00:0:110"), 42);
      }
    }
    ByteBuffer out = ByteBuffer.allocate(100);
    spi.writePacketSCMP(out);
    out.flip();
    incoming.send(out, srcAddress);
    buffer.clear();
  }
}
