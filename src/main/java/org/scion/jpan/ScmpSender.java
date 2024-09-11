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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.scion.jpan.internal.InternalConstants;
import org.scion.jpan.internal.PathHeaderParser;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.internal.ScmpParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScmpSender implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ScmpSender.class);
  private int timeOutMs = 1000;
  private final InternalChannel channel;
  private final AtomicInteger sequenceIDs = new AtomicInteger(0);
  private final ConcurrentHashMap<Integer, TimeOutTask> timers = new ConcurrentHashMap<>();
  private final Timer timer = new Timer(true);
  private Thread receiver;
//  private final PrimaryEchoHandler primaryEchoListener = new PrimaryEchoHandler();
//  private final PrimaryTraceHandler primaryTraceListener = new PrimaryTraceHandler();
  private final ScmpResponseHandler handler;

  public interface ScmpResponseHandler {
    void onResponse(Scmp.TimedMessage msg);

    void onTimeout(Scmp.TimedMessage msg);

    default void onError(Scmp.ErrorMessage msg) {}
    default void onException(Throwable t) {}
  }

  ScmpSender(ScionService service, int port, ScmpResponseHandler handler) throws IOException {
    this.channel = new InternalChannel(service, port);
    this.handler = handler;
    startReceiver();
  }

  private void startReceiver() {
    this.receiver = new Thread(this::handleReceive, "ScmpChannel-receiver");
    this.receiver.setDaemon(true);
    this.receiver.start();
  }

  private void stopReceiver() {
    if (receiver != null) {
      this.receiver.interrupt();
      try {
        this.receiver.join(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public void abortAll() {
    for (TimeOutTask task : timers.values()) {
      task.cancel();
    }
    timers.clear();
  }

  /**
   * Sends a SCMP echo request to the connected destination.
   *
   * @param path The path to use.
   * @param payload user data that is sent with the request
   * @return The sequence ID.
   * @throws IOException if an IO error occurs or if an SCMP error is received.
   */
  public int asyncEchoRequest(Path path, ByteBuffer payload) throws IOException {
    int sequenceId = sequenceIDs.getAndIncrement();
    Scmp.EchoMessage request = Scmp.EchoMessage.createRequest(sequenceId, path, payload);
    channel.sendEchoRequest(request);
    return sequenceId;
  }

  /**
   * Sends a SCMP traceroute request to the connected destination.
   *
   * @param path The path to use.
   * @return A list of sequence IDs.
   * @throws IOException if an IO error occurs or if an SCMP error is received.
   */
  public List<Integer> asyncTracerouteRequest(Path path) throws IOException {
    List<Integer> requestIDs = new ArrayList<>();
    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    for (int i = 0; i < nodes.size(); i++) {
      int sequenceId = sequenceIDs.getAndIncrement();
      Scmp.TracerouteMessage request = Scmp.TracerouteMessage.createRequest(sequenceId, path);
      requestIDs.add(sequenceId);
      channel.sendTracerouteRequest(request, nodes.get(i));
    }
    return requestIDs;
  }

  public void setTimeOut(int milliSeconds) {
    this.timeOutMs = milliSeconds;
  }

  public int getTimeOut() {
    return this.timeOutMs;
  }

  @Override
  public void close() throws IOException {
    channel.close();
    timer.cancel();
    stopReceiver();
  }

  public Consumer<Scmp.Message> setScmpErrorListener(Consumer<Scmp.Message> listener) {
    return channel.setScmpErrorListener(listener);
  }

  public <T> void setOption(SocketOption<T> option, T t) throws IOException {
    channel.setOption(option, t);
  }

  private class InternalChannel extends AbstractDatagramChannel<InternalChannel> {
    private final Selector selector;
    private Predicate<Scmp.TimedMessage> scmpResponseListener;

    protected InternalChannel(ScionService service, int port) throws IOException {
      super(service);

      // selector
      this.selector = Selector.open();
      super.channel().configureBlocking(false);
      super.channel().register(selector, SelectionKey.OP_READ);

      // listen on ANY interface: 0.0.0.0 / [::]
      super.bind(new InetSocketAddress(port));
    }

    void sendEchoRequest(Scmp.EchoMessage request) throws IOException {
      writeLock().lock();
      try {
        Path path = request.getPath();
        super.channel().connect(path.getFirstHopAddress());
        ByteBuffer buffer = getBufferSend(DEFAULT_BUFFER_SIZE);
        // EchoHeader = 8 + data
        int len = 8 + request.getData().length;
        buildHeader(buffer, request.getPath(), len, InternalConstants.HdrTypes.SCMP);
        int localPort = super.getLocalAddress().getPort();
        ScmpParser.buildScmpPing(
            buffer, Scmp.Type.INFO_128, localPort, request.getSequenceNumber(), request.getData());
        buffer.flip();
        request.setSizeSent(buffer.remaining());

        sendRequest(request, buffer, path);
      } finally {
        writeLock().unlock();
        if (super.channel().isConnected()) {
          super.channel().disconnect();
        }
      }
    }

    void sendTracerouteRequest(Scmp.TracerouteMessage request, PathHeaderParser.Node node)
        throws IOException {
      writeLock().lock();
      try {
        Path path = request.getPath();
        super.channel().connect(path.getFirstHopAddress());
        ByteBuffer buffer = getBufferSend(DEFAULT_BUFFER_SIZE);
        // TracerouteHeader = 24
        int len = 24;
        buildHeader(buffer, path, len, InternalConstants.HdrTypes.SCMP);
        int interfaceNumber = request.getSequenceNumber();
        int localPort = super.getLocalAddress().getPort();
        ScmpParser.buildScmpTraceroute(buffer, Scmp.Type.INFO_130, localPort, interfaceNumber);
        buffer.flip();

        // Set flags for border routers to return SCMP packet
        int posPath = ScionHeaderParser.extractPathHeaderPosition(buffer);
        buffer.put(posPath + node.posHopFlags, node.hopFlags);

        sendRequest(request, buffer, path);
      } finally {
        writeLock().unlock();
        if (super.channel().isConnected()) {
          super.channel().disconnect();
        }
      }
    }

    private void sendRequest(Scmp.TimedMessage request, ByteBuffer buffer, Path path)
        throws IOException {
      request.setSendNanoSeconds(System.nanoTime());
      sendRaw(buffer, path);
      TimeOutTask timerTask = new TimeOutTask(request);
      timer.schedule(timerTask, timeOutMs);
      timers.put(request.getSequenceNumber(), timerTask);
    }

    private void receiveAsync() throws IOException {
      while (selector.isOpen() && selector.select() > 0) {
        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        if (iter.hasNext()) {
          SelectionKey key = iter.next();
          iter.remove();
          if (key.isValid() && key.isReadable()) {
            readIncomingScmp(key);
          }
        }
      }
    }

    private void readIncomingScmp(SelectionKey key) throws IOException {
      readLock().lock();
      try {
        DatagramChannel incoming = (DatagramChannel) key.channel();
        ByteBuffer buffer = super.getBufferReceive(DEFAULT_BUFFER_SIZE);
        buffer.clear();
        InetSocketAddress srcAddress = (InetSocketAddress) incoming.receive(buffer);
        buffer.flip();
        if (validate(buffer)) {
          InternalConstants.HdrTypes hdrType = ScionHeaderParser.extractNextHeader(buffer);
          // From here on we use linear reading using the buffer's position() mechanism
          buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
          // Check for extension headers.
          // This should be mostly unnecessary, however we sometimes saw SCMP error headers
          // wrapped in extensions headers.
          hdrType = receiveExtensionHeader(buffer, hdrType);

          if (hdrType != InternalConstants.HdrTypes.SCMP) {
            return; // drop
          }
          handleIncomingScmp(buffer, srcAddress);
        }
      } catch (ScionException e) {
        // Validation problem -> ignore
//        primaryEchoListener.handleException(e);
//        primaryTraceListener.handleException(e);
        handler.onException(e);
      } catch (IOException e) {
//        primaryEchoListener.handleException(e);
//        primaryTraceListener.handleException(e);
        handler.onException(e);
        throw e;
      } finally {
        readLock().unlock();
      }
    }

    private void handleIncomingScmp(ByteBuffer buffer, InetSocketAddress srcAddress) {
      long currentNanos = System.nanoTime();
      ResponsePath receivePath = ScionHeaderParser.extractResponsePath(buffer, srcAddress);
      Scmp.Message msg = ScmpParser.consume(buffer, receivePath);
      if (msg.getTypeCode().isError()) {
//        primaryEchoListener.handleError((Scmp.ErrorMessage) msg);
//        primaryTraceListener.handleError((Scmp.ErrorMessage) msg);
        handler.onError((Scmp.ErrorMessage) msg);
        checkListeners(msg);
        return;
      }

      TimeOutTask task = timers.remove(msg.getSequenceNumber());
      if (task != null) {
        task.cancel(); // Cancel timeout timer
        Scmp.TimedMessage request = task.request;
        if (msg.getTypeCode() == Scmp.TypeCode.TYPE_131) {
          ((Scmp.TimedMessage) msg).setRequest(request);
          ((Scmp.TimedMessage) msg).setReceiveNanoSeconds(currentNanos);
          handler.onResponse((Scmp.TimedMessage) msg);
       //   primaryTraceListener.handle((Scmp.TracerouteMessage) msg);
        } else if (msg.getTypeCode() == Scmp.TypeCode.TYPE_129) {
          ((Scmp.EchoMessage) msg).setSizeReceived(buffer.position());
          ((Scmp.TimedMessage) msg).setRequest(request);
          ((Scmp.TimedMessage) msg).setReceiveNanoSeconds(currentNanos);
          handler.onResponse((Scmp.TimedMessage) msg);
         // primaryEchoListener.handle((Scmp.EchoMessage) msg);
        } else {
          // Wrong type, -> ignore
          return;
        }
      }
      checkListeners(msg);
    }

    @Override
    public void close() throws IOException {
      selector.close();
      super.close();
    }
  }

  public InetSocketAddress getLocalAddress() throws IOException {
    return channel.getLocalAddress();
  }

  /**
   * Specify a source address override. See {@link
   * ScionDatagramChannel#setOverrideSourceAddress(InetSocketAddress)}.
   *
   * @param overrideSourceAddress Override address
   */
  public void setOverrideSourceAddress(InetSocketAddress overrideSourceAddress) {
    channel.setOverrideSourceAddress(overrideSourceAddress);
  }

  private void handleReceive() {
    try {
      channel.receiveAsync();
    } catch (IOException e) {
      log.error("While receiving SCMP message: {}", e.getMessage());
    }
  }

  private class TimeOutTask extends TimerTask {
    private final Scmp.TimedMessage request;

    TimeOutTask(Scmp.TimedMessage request) {
      this.request = request;
    }

    /** This is executed when the task times out, i.e. if we didn't get a ping withing timeOutMs. */
    @Override
    public void run() {
      TimeOutTask timerTask = timers.remove(request.getSequenceNumber());
      if (timerTask != null) {
        Scmp.TimedMessage msg = timerTask.request;
        msg.setTimedOut(timeOutMs * 1_000_000L);
//        if (msg instanceof Scmp.TracerouteMessage) {
//          primaryTraceListener.handle((Scmp.TracerouteMessage) msg);
//        } else if (msg instanceof Scmp.EchoMessage) {
//          primaryEchoListener.handle((Scmp.EchoMessage) msg);
//        }
        handler.onTimeout(msg);
      }
    }
  }

//  private abstract static class PrimaryScmpHandler<T> {
//    private Scmp.ErrorMessage error = null;
//    private Throwable exception = null;
//    private int seqNumber;
//    private boolean isActive = false;
//
//    void init(int seqNumber) {
//      synchronized (this) {
//        if (isActive) {
//          throw new IllegalStateException();
//        }
//        this.seqNumber = seqNumber;
//        this.error = null;
//        this.isActive = true;
//      }
//    }
//
//    void handleError(Scmp.ErrorMessage msg) {
//      synchronized (this) {
//        if (isActive) {
//          error = msg;
//          this.notifyAll();
//        }
//      }
//    }
//
//    void handleException(Throwable t) {
//      synchronized (this) {
//        if (isActive) {
//          exception = t;
//          this.notifyAll();
//        }
//      }
//    }
//
//    protected T waitForResult(Supplier<T> checkResult) throws IOException {
//      while (true) {
//        synchronized (this) {
//          try {
//            if (error != null) {
//              String txt = error.getTypeCode().getText();
//              error = null;
//              reset();
//              isActive = false;
//              throw new IOException(txt);
//            }
//            if (exception != null) {
//              reset();
//              isActive = false;
//              throw new IOException(exception);
//            }
//            T result = checkResult.get();
//            if (result != null) {
//              isActive = false;
//              return result;
//            }
//            this.wait();
//          } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            log.error("Interrupted: {}", Thread.currentThread().getName());
//            throw new RuntimeException(e);
//          }
//        }
//      }
//    }
//
//    abstract T reset();
//
//    protected void assertActive() {
//      if (!isActive) {
//        throw new IllegalStateException();
//      }
//    }
//  }
//
//  private static class PrimaryEchoHandler extends PrimaryScmpHandler<Scmp.EchoMessage> {
//    Scmp.EchoMessage response = null;
//
//    void init(int seqNumber) {
//      synchronized (this) {
//        response = null;
//        super.init(seqNumber);
//      }
//    }
//
//    void handle(Scmp.EchoMessage msg) {
//      synchronized (this) {
//        assertActive();
//        response = msg;
//        this.notifyAll();
//      }
//    }
//
//    Scmp.EchoMessage get() throws IOException {
//      return super.waitForResult(() -> response != null ? reset() : null);
//    }
//
//    @Override
//    Scmp.EchoMessage reset() {
//      Scmp.EchoMessage msg = response;
//      response = null;
//      return msg;
//    }
//  }
//
//  private static class PrimaryTraceHandler
//      extends PrimaryScmpHandler<List<Scmp.TracerouteMessage>> {
//    ArrayList<Scmp.TracerouteMessage> responses = null;
//    int count;
//
//    void init(int seqNumberStart, int count) {
//      synchronized (this) {
//        responses = new ArrayList<>(count);
//        super.init(seqNumberStart);
//        this.count = count;
//      }
//    }
//
//    void handle(Scmp.TracerouteMessage msg) {
//      synchronized (this) {
//        assertActive();
//        if (responses != null) {
//          responses.add(msg);
//          if (responses.size() >= count) {
//            this.notifyAll();
//          }
//        }
//      }
//    }
//
//    List<Scmp.TracerouteMessage> get() throws IOException {
//      return super.waitForResult(() -> responses.size() >= count ? reset() : null);
//    }
//
//    @Override
//    List<Scmp.TracerouteMessage> reset() {
//      List<Scmp.TracerouteMessage> result = responses;
//      responses = null;
//      return result;
//    }
//  }
}
