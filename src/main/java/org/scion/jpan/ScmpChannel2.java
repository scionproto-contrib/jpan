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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.scion.jpan.internal.InternalConstants;
import org.scion.jpan.internal.PathHeaderParser;
import org.scion.jpan.internal.ScionHeaderParser;
import org.scion.jpan.internal.ScmpParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScmpChannel2 implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ScmpChannel2.class);
  private int timeOutMs = 1000;
  private final InternalChannel channel;
  private final AtomicInteger sequenceIDs = new AtomicInteger(0);
  private final ConcurrentHashMap<Integer, Scmp.TimedMessage> requests = new ConcurrentHashMap<>();
  private final Timer timer = new Timer(true);
  private final List<Consumer<Scmp.TracerouteMessage>> traceListeners =
      new CopyOnWriteArrayList<>();
  private final Thread receiver;
  private final PrimaryTraceHandler primaryTraceListener = new PrimaryTraceHandler();

  ScmpChannel2() throws IOException {
    this(Scion.defaultService(), 12345);
  }

  ScmpChannel2(ScionService service, int port) throws IOException {
    this.channel = new InternalChannel(service, port);
    this.receiver = new Thread(this::handleReceive, "ScmpChannel-receiver");
    this.receiver.setDaemon(true);
    this.receiver.start();
  }

  /**
   * Sends a SCMP echo request to the connected destination.
   *
   * @param path The path to use.
   * @param sequenceNumber sequence number of the request
   * @param data user data that is sent with the request
   * @return A SCMP result. If a reply is received, the result contains the reply and the time in
   *     milliseconds that the reply took. If the request timed out, the result contains no message
   *     and the time is equal to the time-out duration.
   * @throws IOException if an IO error occurs or if an SCMP error is received.
   */
  public Scmp.EchoMessage sendEchoRequest(Path path, int sequenceNumber, ByteBuffer data)
      throws IOException {
    Scmp.EchoMessage request = Scmp.EchoMessage.createRequest(sequenceNumber, path, data);
    sendScmpRequest(() -> channel.sendEchoRequest(request), Scmp.TypeCode.TYPE_129);
    return request;
  }

  /**
   * Sends a SCMP traceroute request to the connected destination.
   *
   * @param path The path to use.
   * @return A list of SCMP results, one for each hop on the route. For every reply received, the
   *     result contains the reply and the time in milliseconds that the reply took. If the request
   *     timed out, the result contains no message and the time is equal to the time-out duration.
   *     If a request times out, the traceroute is aborted.
   * @throws IOException if an IO error occurs or if an SCMP error is received.
   */
  public List<Scmp.TracerouteMessage> sendTracerouteRequest(Path path) throws IOException {
    // List<Scmp.TracerouteMessage> requests = new ArrayList<>();
    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    //    for (int i = 0; i < nodes.size(); i++) {
    //      Scmp.TracerouteMessage request = Scmp.TracerouteMessage.createRequest(i, path);
    //      requests.add(request);
    //      PathHeaderParser.Node node = nodes.get(i);
    //      sendScmpRequest(() -> channel.sendTracerouteRequest(request, node),
    // Scmp.TypeCode.TYPE_131);
    //      if (request.isTimedOut()) {
    //        break;
    //      }
    //    }

    primaryTraceListener.init(sequenceIDs.get(), nodes.size());

    for (int i = 0; i < nodes.size(); i++) {
      int sequenceId = sequenceIDs.getAndIncrement();
      Scmp.TracerouteMessage request = Scmp.TracerouteMessage.createRequest(sequenceId, path);
      PathHeaderParser.Node node = nodes.get(i);
      channel.sendTracerouteRequest(request, node);
      requests.put(sequenceId, request);
    }
    List<Scmp.TracerouteMessage> result = primaryTraceListener.get();
    for (Scmp.TracerouteMessage msg : result) {

    }
    return result;

    //    while (requests.size() < nodes.size()) {
    //      synchronized (primaryTraceListener) {
    //        try {
    //          primaryTraceListener.wait();
    //        } catch (InterruptedException e) {
    //          Thread.currentThread().interrupt();
    //          log.error("Interrupted: {}", Thread.currentThread().getName());
    //          throw new RuntimeException(e);
    //        }
    //      }
    //    }
    //    return requests;
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
      PathHeaderParser.Node node = nodes.get(i);
      requests.put(sequenceId, request);
      channel.sendTracerouteRequest(request, node);
    }
    return requestIDs;
  }

  private void sendScmpRequest(IOCallable<Scmp.TimedMessage> sender, Scmp.TypeCode expectedTypeCode)
      throws IOException {
    long sendNanos = System.nanoTime();
    Scmp.TimedMessage result = sender.call();
    long nanos = System.nanoTime() - sendNanos;
    if (result.getTypeCode() == expectedTypeCode) {
      result.setNanoSeconds(nanos);
    } else if (result.isTimedOut()) {
      result.setNanoSeconds(timeOutMs * 1_000_000L);
    } else {
      throw new IOException("SCMP error: " + result.getTypeCode());
    }
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
    receiver.interrupt();
  }

  public Consumer<Scmp.Message> setScmpErrorListener(Consumer<Scmp.Message> listener) {
    return channel.setScmpErrorListener(listener);
  }

  /**
   * Install a listener for echo messages. The listener is called for every incoming echo request
   * message. A response will be sent iff the listener returns 'true'. Any time spent in the
   * listener counts towards the RTT of the echo request.
   *
   * <p>The listener will only be called for messages received during `setUpScmpEchoResponder()`.
   *
   * @param listener THe listener function
   * @return Any previously installed listener or 'null' if none was installed.
   * @see #setUpScmpEchoResponder()
   */
  public Predicate<Scmp.EchoMessage> setScmpEchoListener(Predicate<Scmp.EchoMessage> listener) {
    return channel.setScmpEchoListener(listener);
  }

  public <T> void setOption(SocketOption<T> option, T t) throws IOException {
    channel.setOption(option, t);
  }

  /**
   * Install an SCMP echo responder. This method blocks until interrupted. While blocking, it will
   * answer all valid SCMP echo requests.
   *
   * <p>SCMP requests can be monitored and intercepted through a listener, see {@link
   * #setScmpEchoListener(Predicate)}.
   *
   * @throws IOException If an IO exception occurs.
   */
  public void setUpScmpEchoResponder() throws IOException {
    this.channel.sendEchoResponses();
  }

  @FunctionalInterface
  private interface IOCallable<V> {
    V call() throws IOException;
  }

  private class InternalChannel extends AbstractDatagramChannel<InternalChannel> {
    private final Selector selector;
    private Predicate<Scmp.EchoMessage> echoListener;

    protected InternalChannel(ScionService service, int port) throws IOException {
      super(service);

      // selector
      this.selector = Selector.open();
      super.channel().configureBlocking(false);
      super.channel().register(selector, SelectionKey.OP_READ);

      // listen on ANY interface: 0.0.0.0 / [::]
      super.bind(new InetSocketAddress(port));
    }

    Scmp.TimedMessage sendEchoRequest(Scmp.EchoMessage request) throws IOException {
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
        sendRaw(buffer, path);

        int sizeReceived = receive(request);
        request.setSizeReceived(sizeReceived);
        return request;
      } finally {
        writeLock().unlock();
        if (super.channel().isConnected()) {
          super.channel().disconnect();
        }
      }
    }

    Scmp.TimedMessage sendTracerouteRequest(
        Scmp.TracerouteMessage request, PathHeaderParser.Node node) throws IOException {
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

        request.setSendNanoSeconds(System.nanoTime());
        sendRaw(buffer, path);
        timer.schedule(new TimeOutTask(request.getSequenceNumber()), timeOutMs);

        return request;
      } finally {
        writeLock().unlock();
        if (super.channel().isConnected()) {
          super.channel().disconnect();
        }
      }
    }

    private int receive(Scmp.TimedMessage request) throws IOException {
      readLock().lock();
      try {
        ByteBuffer buffer = getBufferReceive(DEFAULT_BUFFER_SIZE);
        ResponsePath receivePath = receiveWithTimeout(buffer);
        if (receivePath != null) {
          ScmpParser.consume(buffer, request);
          request.setPath(receivePath);
          checkListeners(request);
        } else {
          request.setTimedOut();
        }
        return buffer.position();
      } finally {
        readLock().unlock();
      }
    }

    private ResponsePath receiveWithTimeout(ByteBuffer buffer) throws IOException {
      while (true) {
        buffer.clear();
        if (selector.select(timeOutMs) == 0) {
          return null;
        }

        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        if (iter.hasNext()) {
          SelectionKey key = iter.next();
          iter.remove();
          if (key.isReadable()) {
            DatagramChannel incoming = (DatagramChannel) key.channel();
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
                continue; // drop
              }
              return ScionHeaderParser.extractResponsePath(buffer, srcAddress);
            }
          }
        }
      }
    }

    private void receiveAsync() throws IOException {
      while (true) {
        // TODO while(selector.select() != 0) { ...
        if (selector.select() == 0) {
          return;
        }
        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        if (iter.hasNext()) {
          SelectionKey key = iter.next();
          iter.remove();
          if (key.isReadable()) {
            readIncomingScmp(key);
          }
        }
      }
    }

    private void readIncomingScmp(SelectionKey key) {
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
        e.printStackTrace();
      } catch (IOException e) {
        throw new RuntimeException(e);
      } finally {
        // TODO remove lock!?!?!
        readLock().unlock();
      }
    }

    private void handleIncomingScmp(ByteBuffer buffer, InetSocketAddress srcAddress) {
      ResponsePath receivePath = ScionHeaderParser.extractResponsePath(buffer, srcAddress);
      Scmp.Message msg = ScmpParser.consume(buffer, receivePath);
      int sn = -1;
      if (msg.getTypeCode().isError()) {
        Scmp.ErrorMessage error = (Scmp.ErrorMessage) msg;
        if (error.getCause() != null) {
          sn = error.getCause().getSequenceNumber();
        }
      } else {
        sn = msg.getSequenceNumber();
      }

      if (sn >= 0) {
        // TODO traceListeners.remove();
        Scmp.TimedMessage request = requests.remove(sn);
        if (request != null) {
          if (msg instanceof Scmp.TracerouteMessage) {
            // msg.setRequest(Scmp.TimedMessage (request)); // TODO
            primaryTraceListener.handle((Scmp.TracerouteMessage) msg);
          } else if (msg instanceof Scmp.ErrorMessage) {
              // msg.setRequest(Scmp.TimedMessage (request)); // TODO
            primaryTraceListener.handleError((Scmp.ErrorMessage) msg);
          } else {
            // Wrong type, -> ignore
            return;
          }
        }
      }

      checkListeners(msg);
    }

    void sendEchoResponses() throws IOException {
      readLock().lock();
      writeLock().lock();
      int timeOut = timeOutMs;
      setTimeOut(Integer.MAX_VALUE);
      try {
        while (true) {
          ByteBuffer buffer = getBufferReceive(DEFAULT_BUFFER_SIZE);
          ResponsePath path = receiveWithTimeout(buffer);
          if (path == null) {
            return; // interrupted
          }

          Scmp.Type type = ScmpParser.extractType(buffer);
          log.info("Received SCMP message {} from {}", type, path.getRemoteAddress());
          if (type == Scmp.Type.INFO_128) {
            Scmp.EchoMessage msg = (Scmp.EchoMessage) Scmp.createMessage(Scmp.Type.INFO_128, path);
            ScmpParser.consume(buffer, msg);

            if (!checkEchoListener(msg)) {
              continue;
            }

            // EchoHeader = 8 + data
            int len = 8 + msg.getData().length;
            buildHeader(buffer, msg.getPath(), len, InternalConstants.HdrTypes.SCMP);
            int port = msg.getIdentifier();
            ScmpParser.buildScmpPing(
                buffer, Scmp.Type.INFO_129, port, msg.getSequenceNumber(), msg.getData());
            buffer.flip();
            msg.setSizeSent(buffer.remaining());
            sendRaw(buffer, path);
            log.info("Responded to SCMP {} from {}", type, path.getRemoteAddress());
          } else {
            log.info("Dropped SCMP message with type {} from {}", type, path.getRemoteAddress());
          }
        }
      } finally {
        setTimeOut(timeOut);
        writeLock().unlock();
        readLock().unlock();
      }
    }

    protected boolean checkEchoListener(Scmp.EchoMessage scmpMsg) {
      synchronized (this) {
        if (echoListener != null && scmpMsg.getTypeCode() == Scmp.TypeCode.TYPE_128) {
          return echoListener.test(scmpMsg);
        }
      }
      return true;
    }

    public Predicate<Scmp.EchoMessage> setScmpEchoListener(Predicate<Scmp.EchoMessage> listener) {
      synchronized (this) {
        Predicate<Scmp.EchoMessage> old = echoListener;
        echoListener = listener;
        return old;
      }
    }

    @Override
    public void close() throws IOException {
      super.close();
      selector.close();
    }
  }

  /**
   * Get the currently connected path. The connected path is set during channel creation.
   *
   * @return the current Path
   * @see ScionDatagramChannel#getConnectionPath()
   */
  public Path getConnectionPath() {
    return channel.getConnectionPath();
  }

  public InetSocketAddress getLocalAddress() throws IOException {
    return channel.getLocalAddress();
  }

  public InetSocketAddress getRemoteAddress() throws IOException {
    return channel.getRemoteAddress();
  }

  public PathPolicy getPathPolicy() {
    return channel.getPathPolicy();
  }

  /**
   * Set the path policy. The default path policy is set in {@link PathPolicy#DEFAULT}. If the
   * socket is connected, this method will request a new path using the new policy.
   *
   * <p>After initially setting the path policy, it is used to request a new path during write() and
   * send() whenever a path turns out to be close to expiration.
   *
   * @param pathPolicy the new path policy
   * @see PathPolicy#DEFAULT
   * @see ScionDatagramChannel#setPathPolicy(PathPolicy)
   */
  public void setPathPolicy(PathPolicy pathPolicy) throws IOException {
    channel.setPathPolicy(pathPolicy);
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
    // TODO receive and call callbacks
    System.out.println("Receive callback!! ");
    try {
      channel.receiveAsync();
    } catch (IOException e) {
      log.error("While receiving SCMP message: {}", e.getMessage());
    }
  }

  private class TimeOutTask extends TimerTask {
    private final int sequenceID;

    TimeOutTask(int sequenceID) {
      this.sequenceID = sequenceID;
    }

    @Override
    public void run() {
      Scmp.TimedMessage msg = requests.remove(sequenceID);
      System.out.println("Timeout callback!! " + sequenceID + "  -> " + msg);
      if (msg != null) {
        msg.setReceiveNanoSeconds(System.currentTimeMillis());
        msg.setTimedOut();
        // TODO trigger callback
        primaryTraceListener.handle((Scmp.TracerouteMessage) msg);
        for (Consumer<Scmp.TracerouteMessage> l : traceListeners) {
          l.accept((Scmp.TracerouteMessage) msg);
        }
      }
    }
  }

  private static class PrimaryTraceHandler {
    // TODO remove Atomic
    final AtomicReference<ArrayList<Scmp.TracerouteMessage>> responses = new AtomicReference<>();
    volatile Scmp.ErrorMessage error = null;
    int seqNumberStart;
    int count;

    void init(int seqNumberStart, int count) {
      synchronized (this) {
        if (responses.get() != null) {
          throw new IllegalStateException();
        }
        responses.set(new ArrayList<>(count));
        this.seqNumberStart = seqNumberStart;
        this.count = count;
      }
    }

    void handle(Scmp.TracerouteMessage msg) {
      // TODO verify seqID
      // TODO sort?
      synchronized (this) {
        if (responses.get() != null) {
          responses.get().add(msg);
          if (responses.get().size() >= count) {
            this.notifyAll();
          }
        }
      }
    }

    void handleError(Scmp.ErrorMessage msg) {
      synchronized (this) {
        error = msg;
        this.notifyAll();
      }
    }

    List<Scmp.TracerouteMessage> get() throws IOException {
      while (true) {
        synchronized (this) {
          if (responses.get() == null) {
            throw new IllegalStateException();
          }
          try {
            if (error != null) {
              String txt = error.getTypeCode().getText();
              error = null;
              responses.set(null);
              throw new IOException(txt);
            }
            if (responses.get().size() >= count) {
              return responses.getAndSet(null);
            }
            this.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted: {}", Thread.currentThread().getName());
            throw new RuntimeException(e);
          }
        }
      }
    }
  }
}
