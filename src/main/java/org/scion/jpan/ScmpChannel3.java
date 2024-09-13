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
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.scion.jpan.internal.PathHeaderParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScmpChannel3 implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ScmpChannel3.class);
  private final ScmpSender sender;
  private final PrimaryEchoHandler primaryEchoListener = new PrimaryEchoHandler();
  private final PrimaryTraceHandler primaryTraceListener = new PrimaryTraceHandler();

  ScmpChannel3() throws IOException {
    this(Scion.defaultService(), 12345); // TODO 30041 ??? Or auto-assign?
  }

  ScmpChannel3(ScionService service, int port) throws IOException {
    this.sender =
        Scmp.createSender(
            new ScmpSender.ScmpResponseHandler() {
              @Override
              public void onResponse(Scmp.TimedMessage msg) {
                if (msg.getTypeCode() == Scmp.TypeCode.TYPE_129) {
                  primaryEchoListener.handle((Scmp.EchoMessage) msg);
                } else if (msg.getTypeCode() == Scmp.TypeCode.TYPE_131) {
                  primaryTraceListener.handle((Scmp.TracerouteMessage) msg);
                } else {
                  throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
                }
              }

              @Override
              public void onTimeout(Scmp.TimedMessage msg) {
                if (msg.getTypeCode() == Scmp.TypeCode.TYPE_128) {
                  primaryEchoListener.handle((Scmp.EchoMessage) msg);
                } else if (msg.getTypeCode() == Scmp.TypeCode.TYPE_130) {
                  primaryTraceListener.handle((Scmp.TracerouteMessage) msg);
                } else {
                  throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
                }
              }

              @Override
              public void onError(Scmp.ErrorMessage msg) {
                primaryEchoListener.handleError(msg);
                primaryTraceListener.handleError(msg);
              }

              @Override
              public void onException(Throwable t) {
                primaryEchoListener.handleException(t);
                primaryTraceListener.handleException(t);
              }
            },
            service,
            port);
  }

  /**
   * Sends a SCMP echo request to the connected destination.
   *
   * @param path The path to use.
   * @param data user data that is sent with the request
   * @return A SCMP result. If a reply is received, the result contains the reply and the time in
   *     milliseconds that the reply took. If the request timed out, the result contains no message
   *     and the time is equal to the time-out duration.
   * @throws IOException if an IO error occurs or if an SCMP error is received.
   */
  public Scmp.EchoMessage sendEchoRequest(Path path, ByteBuffer data) throws IOException {
    primaryEchoListener.init();
    sender.asyncEchoRequest(path, data);
    try {
      return primaryEchoListener.get();
    } finally {
      sender.abortAll();
    }
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
    /// TODO clarify behaviour:
    //   It is "difficult" (but possible) to associate incoming SCMP errors with previous requests.
    //   COnsidering "asyncXYZ" we cannot abort everything.
    //    - Well we _can_   abort everything in non-async context!
    //   In case of async, we simply time out. If someone is interested in the error, they can get
    //   it via the error callback.

    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    primaryTraceListener.init(nodes.size());
    sender.asyncTracerouteRequest(path);
    try {
      List<Scmp.TracerouteMessage> result = primaryTraceListener.get();
      result.sort(Comparator.comparingInt(Scmp.Message::getSequenceNumber));
      return result;
    } finally {
      sender.abortAll();
    }
  }

  public void setTimeOut(int milliSeconds) {
    sender.setTimeOut(milliSeconds);
  }

  public int getTimeOut() {
    return sender.getTimeOut();
  }

  @Override
  public void close() throws IOException {
    sender.close();
  }

  public Consumer<Scmp.Message> setScmpErrorListener(Consumer<Scmp.Message> listener) {
    return sender.setScmpErrorListener(listener);
  }

  public <T> void setOption(SocketOption<T> option, T t) throws IOException {
    sender.setOption(option, t);
  }

  public InetSocketAddress getLocalAddress() throws IOException {
    return sender.getLocalAddress();
  }

  /**
   * Specify a source address override. See {@link
   * ScionDatagramChannel#setOverrideSourceAddress(InetSocketAddress)}.
   *
   * @param overrideSourceAddress Override address
   */
  public void setOverrideSourceAddress(InetSocketAddress overrideSourceAddress) {
    sender.setOverrideSourceAddress(overrideSourceAddress);
  }

  private abstract static class PrimaryScmpHandler<T> {
    private Scmp.ErrorMessage error = null;
    private Throwable exception = null;
    private boolean isActive = false;

    void init() {
      synchronized (this) {
        if (isActive) {
          throw new IllegalStateException();
        }
        this.error = null;
        this.isActive = true;
      }
    }

    void handleError(Scmp.ErrorMessage msg) {
      synchronized (this) {
        if (isActive) {
          error = msg;
          this.notifyAll();
        }
      }
    }

    void handleException(Throwable t) {
      synchronized (this) {
        if (isActive) {
          exception = t;
          this.notifyAll();
        }
      }
    }

    protected T waitForResult(Supplier<T> checkResult) throws IOException {
      while (true) {
        synchronized (this) {
          try {
            if (error != null) {
              String txt = error.getTypeCode().getText();
              error = null;
              reset();
              isActive = false;
              throw new IOException(txt);
            }
            if (exception != null) {
              reset();
              isActive = false;
              throw new IOException(exception);
            }
            T result = checkResult.get();
            if (result != null) {
              isActive = false;
              return result;
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

    abstract T reset();

    protected void assertActive() {
      if (!isActive) {
        throw new IllegalStateException();
      }
    }
  }

  private static class PrimaryEchoHandler extends PrimaryScmpHandler<Scmp.EchoMessage> {
    Scmp.EchoMessage response = null;

    void init() {
      synchronized (this) {
        response = null;
        super.init();
      }
    }

    void handle(Scmp.EchoMessage msg) {
      synchronized (this) {
        assertActive();
        response = msg;
        this.notifyAll();
      }
    }

    Scmp.EchoMessage get() throws IOException {
      return super.waitForResult(() -> response != null ? reset() : null);
    }

    @Override
    Scmp.EchoMessage reset() {
      Scmp.EchoMessage msg = response;
      response = null;
      return msg;
    }
  }

  private static class PrimaryTraceHandler
      extends PrimaryScmpHandler<List<Scmp.TracerouteMessage>> {
    ArrayList<Scmp.TracerouteMessage> responses = null;
    int count;

    void init(int count) {
      synchronized (this) {
        responses = new ArrayList<>(count);
        super.init();
        this.count = count;
      }
    }

    void handle(Scmp.TracerouteMessage msg) {
      synchronized (this) {
        assertActive();
        if (responses != null) {
          responses.add(msg);
          if (responses.size() >= count) {
            this.notifyAll();
          }
        }
      }
    }

    List<Scmp.TracerouteMessage> get() throws IOException {
      return super.waitForResult(() -> responses.size() >= count ? reset() : null);
    }

    @Override
    List<Scmp.TracerouteMessage> reset() {
      List<Scmp.TracerouteMessage> result = responses;
      responses = null;
      return result;
    }
  }
}
