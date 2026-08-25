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

package org.scion.jpan.internal.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.proto.endhost.discovery.DiscoveryService;
import org.scion.jpan.testutil.SimpleHttpServer;

class EndhostApiDiscoveryClientTest {

  private MockDiscoveryService mock;

  @AfterEach
  void afterEach() {
    if (mock != null) {
      mock.stop();
    }
  }

  @Test
  void discoverEndhostApis_flattensGroupsInOrder() throws IOException {
    DiscoveryService.RpcGetEndhostApisResponse response =
        DiscoveryService.RpcGetEndhostApisResponse.newBuilder()
            .addGroups(group("https://a.example.com:5001", "https://b.example.com:5001"))
            .addGroups(group("https://c.example.com:5001"))
            .build();
    mock = MockDiscoveryService.start(response);

    List<String> apis = EndhostApiDiscoveryClient.discoverEndhostApis(mock.getUrl());

    assertEquals(
        Arrays.asList(
            "https://a.example.com:5001",
            "https://b.example.com:5001",
            "https://c.example.com:5001"),
        apis);
    assertEquals("/endhost.discovery.v1.EndhostApiDiscoveryService/GetEndhostApis", mock.lastUri);
  }

  @Test
  void discoverEndhostApis_emptyResponse_returnsEmptyList() throws IOException {
    mock =
        MockDiscoveryService.start(DiscoveryService.RpcGetEndhostApisResponse.newBuilder().build());

    List<String> apis = EndhostApiDiscoveryClient.discoverEndhostApis(mock.getUrl());

    assertTrue(apis.isEmpty());
  }

  @Test
  void discoverEndhostApis_serverError_throwsScionRuntimeException() throws IOException {
    mock = MockDiscoveryService.startFailing();

    // TODO call only one function in the check
    ScionRuntimeException e =
        assertThrows(
            ScionRuntimeException.class,
            () -> EndhostApiDiscoveryClient.discoverEndhostApis(mock.getUrl()));
    assertTrue(e.getMessage().contains(mock.getUrl()));
  }

  private static DiscoveryService.RpcEndhostApiGroup group(String... addresses) {
    DiscoveryService.RpcEndhostApiGroup.Builder builder =
        DiscoveryService.RpcEndhostApiGroup.newBuilder();
    for (String address : addresses) {
      builder.addApis(DiscoveryService.RpcEndhostApiInfo.newBuilder().setAddress(address).build());
    }
    return builder.build();
  }

  /** Minimal mock for the {@code EndhostApiDiscoveryService/GetEndhostApis} RPC. */
  private static class MockDiscoveryService extends SimpleHttpServer {
    private final DiscoveryService.RpcGetEndhostApisResponse response;
    private final boolean fail;
    private volatile String lastUri;

    private MockDiscoveryService(
        DiscoveryService.RpcGetEndhostApisResponse response, boolean fail) {
      super(0);
      this.response = response;
      this.fail = fail;
    }

    static MockDiscoveryService start(DiscoveryService.RpcGetEndhostApisResponse response)
        throws IOException {
      MockDiscoveryService server = new MockDiscoveryService(response, false);
      server.start();
      return server;
    }

    static MockDiscoveryService startFailing() throws IOException {
      MockDiscoveryService server = new MockDiscoveryService(null, true);
      server.start();
      return server;
    }

    String getUrl() {
      return "http://127.0.0.1:" + getListeningPort();
    }

    @Override
    public Response serve(Session session) {
      lastUri = session.getUri();
      if (fail) {
        return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "mock failure");
      }
      byte[] body = response.toByteArray();
      return newFixedLengthResponse(
          Response.Status.OK, "application/proto", new ByteArrayInputStream(body), body.length);
    }
  }
}
