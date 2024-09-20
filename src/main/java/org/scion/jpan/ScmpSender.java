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
  private final UnifyingResponseHandler responseHandler = new UnifyingResponseHandler();
  private Consumer<Scmp.ErrorMessage> errorListener = null;

  private ScmpSender(ScionService service, int port) {
    this.sender =
        ScmpSenderAsync.newBuilder(responseHandler).setService(service).setLocalPort(port).build();
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
    responseHandler.startEcho();
    sender.sendEcho(path, data);
    try {
      return responseHandler.getEcho();
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
    responseHandler.startTrace();
    sender.sendTraceroute(path);
    try {
      List<Scmp.TracerouteMessage> result = responseHandler.getTraceroute(nodes.size());
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

  private class UnifyingResponseHandler implements ScmpSenderAsync.ResponseHandler {
    private Scmp.ErrorMessage error = null;
    private Throwable exception = null;
    private Scmp.EchoMessage response = null;
    private ArrayList<Scmp.TracerouteMessage> responses = null;

    private void start() {
      error = null;
      exception = null;
    }

    private <T> T waitForResult(Supplier<T> checkResult) throws IOException {
      try {
        while (true) {
          synchronized (this) {
            if (error != null) {
              throw new IOException(error.getTypeCode().getText());
            }
            if (exception != null) {
              throw new IOException(exception);
            }
            T result = checkResult.get();
            if (result != null) {
              return result;
            }
            this.wait();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Interrupted: {}", Thread.currentThread().getName());
        throw new ScionRuntimeException(e);
      }
    }

    synchronized void startEcho() {
      start();
      response = null;
    }

    synchronized void startTrace() {
      start();
      responses = new ArrayList<>();
    }

    @Override
    public synchronized void onResponse(Scmp.TimedMessage msg) {
      if (msg instanceof Scmp.EchoMessage) {
        response = (Scmp.EchoMessage) msg;
        this.notifyAll();
      } else if (msg instanceof Scmp.TracerouteMessage) {
        responses.add((Scmp.TracerouteMessage) msg);
        this.notifyAll();
      } else {
        throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
      }
    }

    @Override
    public synchronized void onTimeout(Scmp.TimedMessage msg) {
      if (msg instanceof Scmp.EchoMessage) {
        response = (Scmp.EchoMessage) msg;
        this.notifyAll();
      } else if (msg instanceof Scmp.TracerouteMessage) {
        responses.add((Scmp.TracerouteMessage) msg);
        this.notifyAll();
      } else {
        throw new IllegalArgumentException("Received: " + msg.getTypeCode().getText());
      }
    }

    @Override
    public synchronized void onError(Scmp.ErrorMessage msg) {
      if (errorListener != null) {
        errorListener.accept(msg);
      }
      error = msg;
      this.notifyAll();
    }

    @Override
    public synchronized void onException(Throwable t) {
      exception = t;
      this.notifyAll();
    }

    Scmp.EchoMessage getEcho() throws IOException {
      try {
        return waitForResult(() -> response);
      } finally {
        response = null;
      }
    }

    List<Scmp.TracerouteMessage> getTraceroute(int count) throws IOException {
      try {
        return waitForResult(() -> responses.size() >= count ? responses : null);
      } finally {
        responses = new ArrayList<>();
      }
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
