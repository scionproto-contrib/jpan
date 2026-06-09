// Copyright 2026 ETH Zurich
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.scion.jpan.ScionRuntimeException;

/**
 * Minimal HTTP server for testing. Immediately frees the listening port when {@link #stop()} is
 * called.
 */
public abstract class SimpleHttpServer {

  public static final int SOCKET_READ_TIMEOUT = 5000;
  public static final String MIME_PLAINTEXT = "text/plain";

  private final int port;
  private volatile ServerSocket serverSocket;
  private volatile boolean started = false;
  private ExecutorService executor;

  public SimpleHttpServer(int port) {
    this.port = port;
  }

  public void start() throws IOException {
    ServerSocket ss = new ServerSocket();
    ss.setReuseAddress(true);
    ss.bind(new InetSocketAddress(port));
    serverSocket = ss;
    executor = Executors.newCachedThreadPool(new DaemonThreadFactory());
    Thread acceptThread = new Thread(this::serverLoop, "SimpleHttpServer-server-loop");
    acceptThread.setDaemon(true);
    acceptThread.start();
    started = true;
  }

  private void serverLoop() {
    while (!serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        executor.submit(() -> handleConnection(socket));
      } catch (IOException e) {
        // Server socket closed by stop()
      }
    }
  }

  private void handleConnection(Socket socket) {
    try {
      socket.setSoTimeout(SOCKET_READ_TIMEOUT);
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();

      String requestLine = readLine(in);
      if (requestLine == null || requestLine.isEmpty()) {
        return;
      }
      String[] parts = requestLine.split(" ", 3);
      if (parts.length < 2) {
        return;
      }
      RequestMethod requestMethod;
      try {
        requestMethod = RequestMethod.valueOf(parts[0]);
      } catch (IllegalArgumentException e) {
        throw new ScionRuntimeException("Unsupported request method: " + parts[0]);
      }
      String uri = parts[1];

      int contentLength = 0;
      String remoteAddress = socket.getInetAddress().getHostAddress();
      String line;
      while ((line = readLine(in)) != null && !line.isEmpty()) {
        if (line.toLowerCase().startsWith("content-length:")) {
          contentLength = Integer.parseInt(line.substring(15).trim());
        }
      }

      byte[] body = new byte[contentLength];
      int offset = 0;
      while (offset < contentLength) {
        int n = in.read(body, offset, contentLength - offset);
        if (n < 0) {
          break;
        }
        offset += n;
      }

      Session session = new Session(requestMethod, uri, remoteAddress, body);
      Response response = serve(session);

      StringBuilder sb = new StringBuilder();
      sb.append("HTTP/1.1 ")
          .append(response.status.getStatusCode())
          .append(" ")
          .append(response.status.getDescription())
          .append("\r\n");
      sb.append("Content-Type: ").append(response.mimeType).append("\r\n");
      sb.append("Content-Length: ").append(response.bodyBytes.length).append("\r\n");
      sb.append("Connection: close\r\n");
      sb.append("\r\n");
      out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
      if (response.bodyBytes.length > 0) {
        out.write(response.bodyBytes);
      }
      out.flush();
    } catch (IOException e) {
      // Connection error, socket closed by client or server
    } finally {
      try {
        socket.close();
      } catch (IOException e) {
        // ignore
      }
    }
  }

  private String readLine(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\r') {
        in.read(); // consume '\n'
        break;
      }
      if (c == '\n') {
        break;
      }
      sb.append((char) c);
    }
    if (c == -1 && sb.length() == 0) {
      return null;
    }
    return sb.toString();
  }

  public void stop() {
    started = false;
    try {
      if (serverSocket != null) {
        serverSocket.close();
      }
    } catch (IOException e) {
      // ignore
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  public boolean wasStarted() {
    return started;
  }

  public int getListeningPort() {
    ServerSocket ss = serverSocket;
    if (ss != null && !ss.isClosed()) {
      return ss.getLocalPort();
    }
    return -1;
  }

  public abstract Response serve(Session session);

  public static Response newFixedLengthResponse(
      Response.Status status, String mimeType, String body) {
    byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
    return new Response(status, mimeType, bytes);
  }

  public static Response newFixedLengthResponse(
      Response.Status status, String mimeType, InputStream body, long length) {
    try {
      byte[] bytes = new byte[(int) length];
      int offset = 0;
      while (offset < bytes.length) {
        int n = body.read(bytes, offset, bytes.length - offset);
        if (n < 0) {
          break;
        }
        offset += n;
      }
      return new Response(status, mimeType, bytes);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public enum RequestMethod {
    POST
  }

  public static class Response {
    final Status status;
    final String mimeType;
    final byte[] bodyBytes;

    Response(Status status, String mimeType, byte[] bodyBytes) {
      this.status = status;
      this.mimeType = mimeType;
      this.bodyBytes = bodyBytes;
    }

    public enum Status {
      OK(200, "OK"),
      BAD_REQUEST(400, "Bad Request"),
      UNAUTHORIZED(401, "Unauthorized"),
      FORBIDDEN(403, "Forbidden"),
      NOT_FOUND(404, "Not Found"),
      INTERNAL_ERROR(500, "Internal Server Error");

      private final int statusCode;
      private final String description;

      Status(int statusCode, String description) {
        this.statusCode = statusCode;
        this.description = description;
      }

      public int getStatusCode() {
        return statusCode;
      }

      public String getDescription() {
        return description;
      }
    }
  }

  public static class Session {
    private final RequestMethod requestMethod;
    private final String uri;
    private final String remoteAddress;
    private final byte[] body;

    Session(RequestMethod requestMethod, String uri, String remoteAddress, byte[] body) {
      this.requestMethod = requestMethod;
      this.uri = uri;
      this.remoteAddress = remoteAddress;
      this.body = body;
    }

    public RequestMethod getMethod() {
      return requestMethod;
    }

    public String getUri() {
      return uri;
    }

    public String getRemoteIpAddress() {
      return remoteAddress;
    }

    public InputStream getInputStream() {
      return new ByteArrayInputStream(body);
    }
  }

  private static class DaemonThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, "SimpleHttpServer-worker-" + counter.incrementAndGet());
      t.setDaemon(true);
      return t;
    }
  }
}
