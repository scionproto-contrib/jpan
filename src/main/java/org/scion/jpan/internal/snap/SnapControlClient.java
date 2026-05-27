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

package org.scion.jpan.internal.snap;

import anapaya.snap.v1.api_service.ApiService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.internal.util.Config;

/** SNAP control-plane client for Connect-RPC protobuf endpoints. */
public class SnapControlClient {

  private static final String SERVICE_PATH = "/anapaya.snap.v1.SnapControl";
  private static final String GET_DP_PATH = "/GetSnapDataPlaneAddress";
  private static final String REGISTER_ID_PATH = "/RegisterSnapTunIdentity";

  private final OkHttpClient httpClient;
  private final String baseUrl;

  public SnapControlClient(String endpoint) {
    this.httpClient = new OkHttpClient();
    this.baseUrl = normalizeBaseUrl(endpoint);
  }

  public SnapDataPlane getDataPlaneAddress() {
    ApiService.GetSnapDataPlaneRequest request =
        ApiService.GetSnapDataPlaneRequest.newBuilder().build();
    RequestBody requestBody = RequestBody.create(request.toByteArray());
    Request httpRequest =
        withAuth(
                new Request.Builder()
                    .url(baseUrl + SERVICE_PATH + GET_DP_PATH)
                    .addHeader("Content-type", "application/proto"))
            .post(requestBody)
            .build();

    try (Response response = httpClient.newCall(httpRequest).execute()) {
      ResponseBody body = response.body();
      if (!response.isSuccessful() || body == null) {
        throw new IOException("Unexpected code " + response.code() + ": " + response.message());
      }
      ApiService.GetSnapDataPlaneResponse parsed =
          ApiService.GetSnapDataPlaneResponse.newBuilder().mergeFrom(body.bytes()).build();
      SocketAddress dpAddress = parseAddress(parsed.getAddress());
      String snapTunControl =
          parsed.hasSnapTunControlAddress() ? parsed.getSnapTunControlAddress() : null;
      byte[] serverStaticX25519 =
          parsed.hasSnapStaticX25519() ? parsed.getSnapStaticX25519().toByteArray() : null;
      if (serverStaticX25519 != null && serverStaticX25519.length != 32) {
        throw new IOException("server static x25519 key must be 32 bytes");
      }
      return new SnapDataPlane((InetSocketAddress) dpAddress, snapTunControl, serverStaticX25519);
    } catch (IOException e) {
      throw new ScionRuntimeException("SNAP GetSnapDataPlaneAddress failed", e);
    }
  }

  public byte[] registerSnapTunIdentity(byte[] initiatorStaticX25519, byte[] pskShareOrNull) {
    if (initiatorStaticX25519 == null || initiatorStaticX25519.length != 32) {
      throw new IllegalArgumentException("initiator static key must be 32 bytes");
    }
    byte[] psk = pskShareOrNull == null ? new byte[32] : pskShareOrNull;
    if (psk.length != 32) {
      throw new IllegalArgumentException("psk must be 32 bytes");
    }

    ApiService.RegisterSnapTunIdentityRequest request =
        ApiService.RegisterSnapTunIdentityRequest.newBuilder()
            .setInitiatorStaticX25519(
                com.google.protobuf.ByteString.copyFrom(initiatorStaticX25519))
            .setPskShare(com.google.protobuf.ByteString.copyFrom(psk))
            .build();

    RequestBody requestBody = RequestBody.create(request.toByteArray());
    Request httpRequest =
        withAuth(
                new Request.Builder()
                    .url(baseUrl + SERVICE_PATH + REGISTER_ID_PATH)
                    .addHeader("Content-type", "application/proto"))
            .post(requestBody)
            .build();

    try (Response response = httpClient.newCall(httpRequest).execute()) {
      ResponseBody body = response.body();
      if (!response.isSuccessful() || body == null) {
        throw new IOException("Unexpected code " + response.code() + ": " + response.message());
      }
      ApiService.RegisterSnapTunIdentityResponse parsed =
          ApiService.RegisterSnapTunIdentityResponse.newBuilder().mergeFrom(body.bytes()).build();
      byte[] serverPsk = parsed.getPskShare().toByteArray();
      if (serverPsk.length != 32) {
        throw new IOException("server psk must be 32 bytes");
      }
      if (Arrays.equals(serverPsk, new byte[32])) {
        return null;
      }
      return serverPsk;
    } catch (IOException e) {
      throw new ScionRuntimeException("SNAP RegisterSnapTunIdentity failed", e);
    }
  }

  private Request.Builder withAuth(Request.Builder builder) {
    String token = Config.getSnapAuthToken();
    if (token == null || token.isEmpty()) {
      token = Config.getPathServiceAuthToken();
    }
    if (token != null && !token.isEmpty()) {
      builder.addHeader("Authorization", "Bearer " + token);
    }
    return builder;
  }

  private static SocketAddress parseAddress(String hostPort) {
    int split = hostPort.lastIndexOf(':');
    if (split <= 0 || split >= hostPort.length() - 1) {
      throw new IllegalArgumentException("invalid host:port address " + hostPort);
    }
    String host = hostPort.substring(0, split);
    int port = Integer.parseInt(hostPort.substring(split + 1));
    return new InetSocketAddress(host, port);
  }

  private static String normalizeBaseUrl(String endpoint) {
    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalArgumentException("endpoint must not be empty");
    }
    String normalized =
        endpoint.startsWith("http://") || endpoint.startsWith("https://")
            ? endpoint
            : "http://" + endpoint;
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}
