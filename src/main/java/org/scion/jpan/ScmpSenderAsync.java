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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.scion.jpan.internal.*;

public class ScmpSenderAsync implements AutoCloseable {
  private int timeOutMs = 1000;
  private final InternalChannel channel;
  private final AtomicInteger sequenceIDs = new AtomicInteger(0);
  private final ConcurrentHashMap<Integer, TimeOutTask> timers = new ConcurrentHashMap<>();
  private final Timer timer = new Timer(true);
  private final Thread receiver;
  private final ResponseHandler handler;

  public static Builder newBuilder(ResponseHandler handler) {
    return new Builder(handler);
  }

  public interface ResponseHandler {
    void onResponse(Scmp.TimedMessage msg);

    void onTimeout(Scmp.TimedMessage msg);

    default void onError(Scmp.ErrorMessage msg) {}

    default void onException(Throwable t) {}
  }

  private ScmpSenderAsync(
      ScionService service,
      Integer port,
      ResponseHandler handler,
      java.nio.channels.DatagramChannel channel) {
    this.channel = new InternalChannel(service, port, channel);
    this.handler = handler;
    this.receiver = startHandler(this::receiveTask, "ScmpSender-receiver");
  }

  private Thread startHandler(Consumer<CountDownLatch> task, String name) {
    CountDownLatch barrier = new CountDownLatch(1);
    Thread thread = new Thread(() -> task.accept(barrier), name);
    thread.setDaemon(true);
    thread.start();
    try {
      if (!barrier.await(1, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Could not start receiver thread: " + name);
      }
      return thread;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScionRuntimeException(e);
    }
  }

  private void stopHandler(Thread thread) {
    thread.interrupt();
    try {
      thread.join(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void abortAll() {
    for (TimeOutTask task : timers.values()) {
      task.cancel();
    }
    timers.clear();
  }

  /**
   * @return The assigned response handler.
   */
  public ResponseHandler getHandler() {
    return handler;
  }

  /**
   * Sends a SCMP echo request along the specified path.
   *
   * <p>After calling this method you may want to call {@link #abortAll()} to abort potentially
   * remaining timeout timers from outstanding responses.
   *
   * @param path The path to use.
   * @param payload user data that is sent with the request
   * @return The sequence ID.
   * @throws IOException if an IO error occurs.
   */
  public int sendEcho(Path path, ByteBuffer payload) throws IOException {
    int sequenceId = sequenceIDs.getAndIncrement();
    Scmp.EchoMessage request = Scmp.EchoMessage.createRequest(sequenceId, path, payload);
    channel.sendEchoRequest(request);
    return sequenceId;
  }

  /**
   * Sends a SCMP traceroute request along the specified path.
   *
   * <p>After calling this method you may want to call {@link #abortAll()} to abort potentially
   * remaining timeout timers from outstanding responses.
   *
   * @param path The path to use.
   * @return A list of sequence IDs.
   * @throws IOException if an IO error occurs.
   */
  public List<Integer> sendTraceroute(Path path) throws IOException {
    List<Integer> requestIDs = new ArrayList<>();
    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    for (PathHeaderParser.Node node : nodes) {
      int sequenceId = sequenceIDs.getAndIncrement();
      Scmp.TracerouteMessage request = Scmp.TracerouteMessage.createRequest(sequenceId, path);
      requestIDs.add(sequenceId);
      channel.sendTracerouteRequest(request, node);
    }
    return requestIDs;
  }

  /**
   * Sends a SCMP traceroute request along the specified path. A measurement will only be returned
   * for the _last_ AS, i.e. the final destination AS.
   *
   * <p>After calling this method you may want to call {@link #abortAll()} to abort potentially
   * remaining timeout timers from outstanding responses.
   *
   * @param path The path to use.
   * @return The sequence ID or -1 if the path is empty.
   * @throws IOException if an IO error occurs.
   */
  public int sendTracerouteLast(Path path) throws IOException {
    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    if (nodes.isEmpty()) {
      return -1;
    }
    int sequenceId = sequenceIDs.getAndIncrement();
    Scmp.TracerouteMessage request = Scmp.TracerouteMessage.createRequest(sequenceId, path);
    channel.sendTracerouteRequest(request, nodes.get(nodes.size() - 1));
    return sequenceId;
  }

  public void setTimeOut(int milliSeconds) {
    this.timeOutMs = milliSeconds;
  }

  public int getTimeOut() {
    return this.timeOutMs;
  }

  @Override
  public void close() throws IOException {
    timer.cancel();
    stopHandler(receiver);
    channel.close();
  }

  public <T> T getOption(SocketOption<T> option) throws IOException {
    return channel.getOption(option);
  }

  /**
   * This is currently only useful for {@link ScionSocketOptions#SCION_API_THROW_PARSER_FAILURE}.
   *
   * @param option option
   * @param t value
   * @param <T> option type
   * @throws IOException in case of IO error
   */
  public <T> void setOption(SocketOption<T> option, T t) throws IOException {
    channel.setOption(option, t);
  }

  private class InternalChannel extends AbstractDatagramChannel<InternalChannel> {
    private final Selector selector;

    protected InternalChannel(
        ScionService service, Integer port, java.nio.channels.DatagramChannel channel) {
      super(service, channel);

      try {
        // selector
        this.selector = channel.provider().openSelector();
        super.channel().configureBlocking(false);
        super.channel().register(this.selector, SelectionKey.OP_READ);

        if (port == null || port < 0) {
          ensureBound();
        } else {
          // listen on ANY interface: 0.0.0.0 / [::]
          super.bind(new InetSocketAddress(port));
        }
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
    }

    void sendEchoRequest(Scmp.EchoMessage request) throws IOException {
      writeLock().lock();
      try {
        Path path = request.getPath();
        ByteBuffer buffer = getBufferSend(DEFAULT_BUFFER_SIZE);
        // EchoHeader = 8 + data
        int len = 8 + request.getData().length;
        ByteUtil.MutInt srcPort = new ByteUtil.MutInt(-1);
        buildHeader(buffer, request.getPath(), len, InternalConstants.HdrTypes.SCMP, srcPort);
        ScmpParser.buildScmpPing(
            buffer, Scmp.Type.INFO_128, srcPort.v, request.getSequenceNumber(), request.getData());
        buffer.flip();
        request.setSizeSent(buffer.remaining());

        sendRequest(request, buffer, path);
      } finally {
        writeLock().unlock();
      }
    }

    void sendTracerouteRequest(Scmp.TracerouteMessage request, PathHeaderParser.Node node)
        throws IOException {
      writeLock().lock();
      try {
        Path path = request.getPath();
        ByteBuffer buffer = getBufferSend(DEFAULT_BUFFER_SIZE);
        // TracerouteHeader = 24
        int len = 24;
        ByteUtil.MutInt srcPort = new ByteUtil.MutInt(-1);
        buildHeader(buffer, path, len, InternalConstants.HdrTypes.SCMP, srcPort);
        int interfaceNumber = request.getSequenceNumber();
        ScmpParser.buildScmpTraceroute(buffer, Scmp.Type.INFO_130, srcPort.get(), interfaceNumber);
        buffer.flip();

        // Set flags for border routers to return SCMP packet
        int posPath = ScionHeaderParser.extractPathHeaderPosition(buffer);
        buffer.put(posPath + node.posHopFlags, node.hopFlags);

        sendRequest(request, buffer, path);
      } finally {
        writeLock().unlock();
      }
    }

    private void sendRequest(Scmp.TimedMessage request, ByteBuffer buffer, Path path)
        throws IOException {
      request.setSendNanoSeconds(System.nanoTime());
      TimeOutTask timerTask = new TimeOutTask(request);
      timer.schedule(timerTask, timeOutMs);
      timers.put(request.getSequenceNumber(), timerTask);
      // Send packet _after_ registering timers!
      sendRaw(buffer, path);
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
          ResponsePath receivePath = ScionHeaderParser.extractResponsePath(buffer, srcAddress);
          int packetLength = ScionHeaderParser.extractPacketLength(buffer);
          // From here on we use linear reading using the buffer's position() mechanism
          buffer.position(ScionHeaderParser.extractHeaderLength(buffer));
          // Check for extension headers.
          // This should be mostly unnecessary, however we sometimes saw SCMP error headers
          // wrapped in extensions headers.
          hdrType = receiveExtensionHeader(buffer, hdrType);

          if (hdrType != InternalConstants.HdrTypes.SCMP) {
            return; // drop
          }
          handleIncomingScmp(buffer, receivePath, packetLength);
        }
      } catch (ScionException e) {
        // Validation problem -> ignore
        handler.onException(e);
      } finally {
        readLock().unlock();
      }
    }

    private void handleIncomingScmp(ByteBuffer buffer, ResponsePath receivePath, int packetLength) {
      long currentNanos = System.nanoTime();
      int bufferStart = buffer.position();
      Scmp.Message msg = ScmpParser.consume(buffer, receivePath, packetLength);
      if (msg.getTypeCode().isError()) {
        handler.onError((Scmp.ErrorMessage) msg);
        checkListeners(msg);
        return;
      }

      TimeOutTask task = timers.remove(msg.getSequenceNumber());
      if (task != null) {
        task.cancel(); // Cancel timeout timer
        Scmp.TimedMessage request = task.request;
        if (msg.getTypeCode() == Scmp.TypeCode.TYPE_131) {
          ((Scmp.TimedMessage) msg).assignRequest(request, currentNanos);
          handler.onResponse((Scmp.TimedMessage) msg);
        } else if (msg.getTypeCode() == Scmp.TypeCode.TYPE_129) {
          ((Scmp.EchoMessage) msg).setSizeReceived(buffer.position() - bufferStart);
          ((Scmp.TimedMessage) msg).assignRequest(request, currentNanos);
          handler.onResponse((Scmp.TimedMessage) msg);
        } else {
          // Wrong type -> ignore
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

  private void receiveTask(CountDownLatch barrier) {
    try {
      barrier.countDown();
      channel.receiveAsync();
    } catch (Exception e) {
      handler.onException(e);
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
        handler.onTimeout(msg);
      }
    }
  }

  public static class Builder {
    private ScionService service;
    private Integer port = null;
    private final ResponseHandler handler;
    private java.nio.channels.DatagramChannel channel = null;

    private Builder(ResponseHandler handler) {
      this.handler = handler;
    }

    public Builder setLocalPort(Integer localPort) {
      this.port = localPort;
      return this;
    }

    public Builder setService(ScionService service) {
      this.service = service;
      return this;
    }

    public Builder setDatagramChannel(java.nio.channels.DatagramChannel channel) {
      this.channel = channel;
      return this;
    }

    public ScmpSenderAsync build() {
      service = service == null ? ScionService.defaultService() : service;
      try {
        channel = channel == null ? java.nio.channels.DatagramChannel.open() : channel;
        return new ScmpSenderAsync(service, port, handler, channel);
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
    }
  }
}
