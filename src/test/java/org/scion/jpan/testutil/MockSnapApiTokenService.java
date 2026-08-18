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

import java.io.IOException;
import org.scion.jpan.proto.aa.Auth;

/**
 * Mock Anapaya AA (Auth/AuthZ) service that issues SNAP tokens in exchange for a known API key.
 * Used in tests to exercise the full token-fetch → SNAP-handshake flow without a real AA server.
 */
public class MockSnapApiTokenService implements AutoCloseable {

  public static final String API_KEY = "aakey_test";
  public static final String SNAP_TOKEN = "snap_token_from_aa";

  private final AaAuthServer httpServer;

  private MockSnapApiTokenService(AaAuthServer server) {
    this.httpServer = server;
  }

  public static MockSnapApiTokenService start() throws IOException {
    AaAuthServer server = new AaAuthServer(0);
    return new MockSnapApiTokenService(server);
  }

  /** Returns the base URL of this server, e.g. {@code http://127.0.0.1:PORT}. */
  public String getBaseUrl() {
    return "http://127.0.0.1:" + httpServer.getListeningPort();
  }

  @Override
  public void close() {
    httpServer.stop();
  }

  private static class AaAuthServer extends SimpleHttpServer {

    AaAuthServer(int port) throws IOException {
      super(port);
      super.start();
    }

    @Override
    public Response serve(Session session) {
      if (!session.getUri().endsWith("/AuthenticateByKey")) {
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown endpoint: " + session.getUri());
      }
      try {
        byte[] buf = new byte[4096];
        int n = session.getInputStream().read(buf);
        Auth.AuthenticateByKeyRequest request =
            Auth.AuthenticateByKeyRequest.newBuilder().mergeFrom(buf, 0, n > 0 ? n : 0).build();
        if (!API_KEY.equals(request.getApiKey())) {
          return newFixedLengthResponse(
              Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Invalid API key");
        }
        Auth.AuthenticateByKeyResponse response =
            Auth.AuthenticateByKeyResponse.newBuilder().setSnapToken(SNAP_TOKEN).build();
        byte[] body = response.toByteArray();
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/proto",
            new java.io.ByteArrayInputStream(body),
            body.length);
      } catch (IOException e) {
        return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
      }
    }
  }
}
