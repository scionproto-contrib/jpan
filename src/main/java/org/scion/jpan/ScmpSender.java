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

public class ScmpSender implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ScmpSender.class);
  private final ScmpSenderAsync sender;
  private final EchoHandler echoHandler = new EchoHandler();
  private final TraceHandler traceHandler = new TraceHandler();
  private Consumer<Scmp.ErrorMessage> errorListener = null;

  private ScmpSender(ScionService service, int port) {
    ScmpSenderAsync.ScmpResponseHandler handler =
        new ScmpSenderAsync.ScmpResponseHandler() {
          @Override
          public void onResponse(Scmp.TimedMessage msg) {
            if (msg.getTypeCode() == Scmp.TypeCode.TYPE_129) {
              echoHandler.handle((Scmp.EchoMessage) msg);
            } else if (msg.getTypeCode() == Scmp.TypeCode.TYPE_131) {
              traceHandler.handle((Scmp.TracerouteMessage) msg);
            } else {
              throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
            }
          }

          @Override
          public void onTimeout(Scmp.TimedMessage msg) {
            if (msg.getTypeCode() == Scmp.TypeCode.TYPE_128) {
              echoHandler.handle((Scmp.EchoMessage) msg);
            } else if (msg.getTypeCode() == Scmp.TypeCode.TYPE_130) {
              traceHandler.handle((Scmp.TracerouteMessage) msg);
            } else {
              throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
            }
          }

          @Override
          public void onError(Scmp.ErrorMessage msg) {
            echoHandler.handleError(msg);
            traceHandler.handleError(msg);
            if (errorListener != null) {
              errorListener.accept(msg);
            }
          }

          @Override
          public void onException(Throwable t) {
            echoHandler.handleException(t);
            traceHandler.handleException(t);
          }
        };
    this.sender =
        ScmpSenderAsync.newBuilder(handler).setService(service).setLocalPort(port).build();
  }

  public static Builder newBuilder() {
    return new Builder();
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
    echoHandler.init();
    sender.asyncEcho(path, data);
    try {
      return echoHandler.get();
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
    List<PathHeaderParser.Node> nodes = PathHeaderParser.getTraceNodes(path.getRawPath());
    traceHandler.init(nodes.size());
    sender.asyncTraceroute(path);
    try {
      List<Scmp.TracerouteMessage> result = traceHandler.get();
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

  public Consumer<Scmp.ErrorMessage> setScmpErrorListener(Consumer<Scmp.ErrorMessage> listener) {
    Consumer<Scmp.ErrorMessage> previous = this.errorListener;
    this.errorListener = listener;
    return previous;
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

  private abstract static class ScmpHandler<T> {
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
            throw new ScionRuntimeException(e);
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

  private static class EchoHandler extends ScmpHandler<Scmp.EchoMessage> {
    Scmp.EchoMessage response = null;

    @Override
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

  private static class TraceHandler extends ScmpHandler<List<Scmp.TracerouteMessage>> {
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

  public static class Builder {
    private ScionService service;
    private int port = 12345; // TODO Constants.SCMP_PORT;

    public Builder setLocalPort(int localPort) {
      this.port = localPort;
      return this;
    }

    public Builder setService(ScionService service) {
      this.service = service;
      return this;
    }

    public ScmpSender build() {
      ScionService service2 = service == null ? ScionService.defaultService() : service;
      return new ScmpSender(service2, port);
    }
  }
}
