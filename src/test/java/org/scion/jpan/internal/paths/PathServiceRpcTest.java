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

package org.scion.jpan.internal.paths;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.bootstrap.LocalAsFromPathService;
import org.scion.jpan.internal.bootstrap.TrcStore;
import org.scion.jpan.proto.endhost.Segments;
import org.scion.jpan.proto.endhost.Underlays;
import org.scion.jpan.testutil.SimpleHttpServer;

/**
 * {@link PathServiceRpc} previously had no test coverage at all -- including for its use of {@link
 * org.scion.jpan.internal.util.HttpEndpoint}, applied identically to {@link
 * LocalAsFromPathService}. This covers a basic segment-fetch round trip.
 */
class PathServiceRpcTest {

  private static final long SRC_ISD_AS = ScionUtil.parseIA("64-2:0:0");
  private static final long DST_ISD_AS = ScionUtil.parseIA("64-2:0:9");
  private static final String NEXT_PAGE_TOKEN = "next-page-token-42";

  private MockEndhostApi mock;

  @AfterEach
  void afterEach() {
    if (mock != null) {
      mock.stop();
    }
  }

  @Test
  void segments_fetchesAndParsesResponse() throws IOException {
    mock = MockEndhostApi.launch();

    LocalAS localAS = LocalAsFromPathService.create(mock.getUrl(), TrcStore.createEmpty());
    PathServiceRpc rpc = PathServiceRpc.create(localAS);

    Segments.ListSegmentsResponse response = rpc.segments(SRC_ISD_AS, DST_ISD_AS);

    assertEquals(NEXT_PAGE_TOKEN, response.getNextPageToken());
    assertNotNull(mock.lastSegmentsRequest);
    assertEquals(SRC_ISD_AS, mock.lastSegmentsRequest.getSrcIsdAs());
    assertEquals(DST_ISD_AS, mock.lastSegmentsRequest.getDstIsdAs());
  }

  /** Minimal mock serving both {@code ListUnderlays} (for bootstrap) and {@code ListSegments}. */
  private static class MockEndhostApi extends SimpleHttpServer {
    private volatile Segments.ListSegmentsRequest lastSegmentsRequest;

    private MockEndhostApi() {
      super(0);
    }

    static MockEndhostApi launch() throws IOException {
      MockEndhostApi server = new MockEndhostApi();
      server.start();
      return server;
    }

    String getUrl() {
      return "http://127.0.0.1:" + getListeningPort();
    }

    @Override
    public Response serve(Session session) {
      if (session.getUri().endsWith("/ListUnderlays")) {
        Underlays.ListUnderlaysResponse response =
            Underlays.ListUnderlaysResponse.newBuilder()
                .setUdp(
                    Underlays.UdpUnderlay.newBuilder()
                        .addRouters(
                            Underlays.Router.newBuilder()
                                .setIsdAs(SRC_ISD_AS)
                                .setAddress("10.0.0.1:31000")
                                .build())
                        .build())
                .build();
        return protoResponse(response.toByteArray());
      }
      if (session.getUri().endsWith("/ListSegments")) {
        try {
          byte[] buf = new byte[4096];
          int n = session.getInputStream().read(buf);
          lastSegmentsRequest =
              Segments.ListSegmentsRequest.newBuilder().mergeFrom(buf, 0, Math.max(n, 0)).build();
        } catch (IOException e) {
          return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad request");
        }
        Segments.ListSegmentsResponse response =
            Segments.ListSegmentsResponse.newBuilder().setNextPageToken(NEXT_PAGE_TOKEN).build();
        return protoResponse(response.toByteArray());
      }
      return newFixedLengthResponse(
          Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown endpoint: " + session.getUri());
    }

    private Response protoResponse(byte[] body) {
      return newFixedLengthResponse(
          Response.Status.OK, "application/proto", new ByteArrayInputStream(body), body.length);
    }
  }
}
