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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.util.Config;
import org.scion.jpan.proto.snap.ControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SNAP control-plane client for Connect-RPC protobuf endpoints. */
public class SnapControlClient {

  private static final String SERVICE_PATH = "/anapaya.snap.v1.SnapControl";
  private static final String GET_DP_PATH = "/GetSnapDataPlaneAddress";
  private static final String REGISTER_ID_PATH = "/RegisterSnapTunIdentity";

  private static final Logger LOG = LoggerFactory.getLogger(SnapControlClient.class.getName());
  private static final HostnameVerifier DEFAULT_VERIFIER =
      HttpsURLConnection.getDefaultHostnameVerifier();
  private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  private final OkHttpClient httpClient;
  private final String baseUrl;

  public static SnapControlClient create(LocalAS localAS) {
    String explicit = Config.getSnapControlPlaneAddress();
    if (explicit != null && !explicit.isEmpty()) {
      return new SnapControlClient(explicit);
    }

    List<LocalAS.SnapControlNode> snapControlNodes = localAS.getSnapControlNodes();
    if (snapControlNodes.isEmpty()) {
      return null;
    }
    // TODO Choosing only may be alright if the server mixes them up.
    String address = snapControlNodes.get(0).getAddress();
    return new SnapControlClient(address);
  }

  public SnapControlClient(String endpoint) {
    // If the snap address ia a hostname (not just a bare IP:port), we attempt
    // to check the hostname against a server certificate.
    // If that fails we issue a warning but still allow the connection.
    this.httpClient =
        new OkHttpClient.Builder().hostnameVerifier(SnapControlClient::verifyHostname).build();
    this.baseUrl = normalizeBaseUrl(endpoint);
  }

  // Package-private (rather than private) so SnapControlClientWhiteboxTest can exercise these
  // branches directly.

  static boolean verifyHostname(String hostname, SSLSession session) {
    if (!isIpLiteral(hostname) && !DEFAULT_VERIFIER.verify(hostname, session)) {
      LOG.warn("Hostname \"{}\" could not be verified.", hostname);
    }
    return true;
  }

  static boolean isIpLiteral(String host) {
    // IPv6 literals always contain ':', which is never valid in a DNS hostname.
    return host.indexOf(':') >= 0 || IPV4_LITERAL.matcher(host).matches();
  }

  public SnapDataplaneDetails getDataPlaneAddress(boolean failOnError) {
    try {
      byte[] responseBytes =
          post(
              SERVICE_PATH + GET_DP_PATH,
              ControlService.GetSnapDataPlaneAddressRequest.getDefaultInstance().toByteArray());
      ControlService.GetSnapDataPlaneAddressResponse parsed =
          ControlService.GetSnapDataPlaneAddressResponse.newBuilder()
              .mergeFrom(responseBytes)
              .build();
      SocketAddress dpAddress = parseAddress(parsed.getAddress());
      String snapTunControl =
          parsed.hasSnapTunControlAddress() ? parsed.getSnapTunControlAddress() : null;
      byte[] serverStaticX25519 =
          parsed.hasSnapStaticX25519() ? parsed.getSnapStaticX25519().toByteArray() : null;
      if (serverStaticX25519 != null && serverStaticX25519.length != 32) {
        throw new IOException("server static x25519 key must be 32 bytes");
      }
      return new SnapDataplaneDetails(
          (InetSocketAddress) dpAddress, snapTunControl, serverStaticX25519);
    } catch (IOException e) {
      LOG.error("SNAP GetSnapDataPlaneAddress failed: {}", e.getMessage());
      if (failOnError) {
        throw new ScionRuntimeException("SNAP GetSnapDataPlaneAddress failed", e);
      }
      return null;
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
    try {
      byte[] responseBytes =
          post(
              SERVICE_PATH + REGISTER_ID_PATH,
              ControlService.RegisterSnapTunIdentityRequest.newBuilder()
                  .setInitiatorStaticX25519(
                      com.google.protobuf.ByteString.copyFrom(initiatorStaticX25519))
                  .setPskShare(com.google.protobuf.ByteString.copyFrom(psk))
                  .build()
                  .toByteArray());
      ControlService.RegisterSnapTunIdentityResponse parsed =
          ControlService.RegisterSnapTunIdentityResponse.newBuilder()
              .mergeFrom(responseBytes)
              .build();
      byte[] serverPsk = parsed.getPskShare().toByteArray();
      if (serverPsk.length != 32) {
        throw new IOException("server psk must be 32 bytes");
      }
      return Arrays.equals(serverPsk, new byte[32]) ? null : serverPsk;
    } catch (IOException e) {
      throw new ScionRuntimeException("SNAP RegisterSnapTunIdentity failed", e);
    }
  }

  private byte[] post(String path, byte[] requestBytes) throws IOException {
    Request httpRequest =
        withAuth(
                new Request.Builder()
                    .url(baseUrl + path)
                    .addHeader("Content-type", "application/proto"))
            .post(RequestBody.create(requestBytes))
            .build();
    try (Response response = httpClient.newCall(httpRequest).execute()) {
      ResponseBody body = response.body();
      if (!response.isSuccessful() || body == null) {
        throw new IOException("Unexpected code " + response.code() + ": " + response.message());
      }
      return body.bytes();
    }
  }

  private Request.Builder withAuth(Request.Builder builder) {
    String token = Config.getSnapAuthToken();
    if (token != null && !token.isEmpty()) {
      builder.addHeader("Authorization", "Bearer " + token);
    }
    return builder;
  }

  static SocketAddress parseAddress(String hostPort) {
    int split = hostPort.lastIndexOf(':');
    if (split <= 0 || split >= hostPort.length() - 1) {
      throw new IllegalArgumentException("invalid host:port address " + hostPort);
    }
    String host = hostPort.substring(0, split);
    int port = Integer.parseInt(hostPort.substring(split + 1));
    return new InetSocketAddress(host, port);
  }

  static String normalizeBaseUrl(String endpoint) {
    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalArgumentException("endpoint must not be empty");
    }
    String normalized =
        endpoint.startsWith("http://") || endpoint.startsWith("https://")
            ? endpoint
            : "https://" + endpoint;
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  public String getUrl() {
    return baseUrl;
  }
}
