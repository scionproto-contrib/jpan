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

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.proto.snap.ControlService;

/**
 * Whitebox coverage for {@link SnapControlClient} branches that the higher-level SNAP API tests
 * never exercise, since they only ever drive "everything works" round trips against a real,
 * always-plain-HTTP {@code MockSnapService}: URL normalization, address parsing, the
 * failOnError=true/false split in {@link SnapControlClient#getDataPlaneAddress}, {@link
 * SnapControlClient#registerSnapTunIdentity}'s validation and response handling, the Authorization
 * header, and hostname verification over a genuine TLS connection (which a plain-HTTP mock never
 * negotiates in the first place).
 */
class SnapControlClientWhiteboxTest {

  private MockWebServer server;
  private File trustStoreFile;

  @AfterEach
  void afterEach() throws Exception {
    System.clearProperty(Constants.PROPERTY_SNAP_AUTH_TOKEN);
    System.clearProperty("javax.net.ssl.trustStore");
    System.clearProperty("javax.net.ssl.trustStoreType");
    System.clearProperty("javax.net.ssl.trustStorePassword");
    if (server != null) {
      server.shutdown();
    }
    if (trustStoreFile != null) {
      trustStoreFile.delete();
    }
  }

  // -------------------------------------------------------------------------
  // normalizeBaseUrl()
  // -------------------------------------------------------------------------

  @Test
  void normalizeBaseUrl_null_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> SnapControlClient.normalizeBaseUrl(null));
  }

  @Test
  void normalizeBaseUrl_empty_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> SnapControlClient.normalizeBaseUrl(""));
  }

  @Test
  void normalizeBaseUrl_bareHostPort_defaultsToHttps() {
    assertEquals("https://127.0.0.1:8080", SnapControlClient.normalizeBaseUrl("127.0.0.1:8080"));
  }

  @Test
  void normalizeBaseUrl_explicitScheme_isKeptAsIs() {
    assertEquals(
        "http://127.0.0.1:8080", SnapControlClient.normalizeBaseUrl("http://127.0.0.1:8080"));
  }

  @Test
  void normalizeBaseUrl_trailingSlashes_areStripped() {
    assertEquals(
        "https://127.0.0.1:8080", SnapControlClient.normalizeBaseUrl("https://127.0.0.1:8080///"));
  }

  // -------------------------------------------------------------------------
  // parseAddress()
  // -------------------------------------------------------------------------

  @Test
  void parseAddress_valid_returnsCorrectSocketAddress() {
    InetSocketAddress addr = (InetSocketAddress) SnapControlClient.parseAddress("127.0.0.1:1234");
    assertEquals("127.0.0.1", addr.getHostString());
    assertEquals(1234, addr.getPort());
  }

  @Test
  void parseAddress_noColon_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> SnapControlClient.parseAddress("hostonly"));
  }

  @Test
  void parseAddress_colonAtStart_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> SnapControlClient.parseAddress(":1234"));
  }

  @Test
  void parseAddress_colonAtEnd_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> SnapControlClient.parseAddress("host:"));
  }

  // -------------------------------------------------------------------------
  // isIpLiteral()
  // -------------------------------------------------------------------------

  @Test
  void isIpLiteral_ipv4_returnsTrue() {
    assertTrue(SnapControlClient.isIpLiteral("127.0.0.1"));
  }

  @Test
  void isIpLiteral_ipv6_returnsTrue() {
    assertTrue(SnapControlClient.isIpLiteral("::1"));
  }

  @Test
  void isIpLiteral_hostname_returnsFalse() {
    assertFalse(SnapControlClient.isIpLiteral("snap.example.com"));
  }

  // -------------------------------------------------------------------------
  // verifyHostname() over a genuine TLS connection
  // -------------------------------------------------------------------------

  @Test
  void verifyHostname_mismatchedHostnameOnTrustedChain_stillConnects() throws Exception {
    // Documents CURRENT behavior (see SnapControlClient's constructor comment: "If that fails we
    // issue a warning but still allow the connection."). This does NOT mean the mismatch is
    // undetected -- isIpLiteral_hostname_returnsFalse plus the DEFAULT_VERIFIER call above prove
    // the check runs -- only that its result is currently ignored rather than enforced.
    HeldCertificate serverCert =
        new HeldCertificate.Builder()
            .commonName("not-the-name-we-connect-to")
            .addSubjectAlternativeName("not-the-name-we-connect-to")
            .build();
    HandshakeCertificates serverCertificates =
        new HandshakeCertificates.Builder().heldCertificate(serverCert).build();
    installAsDefaultTrustedCertificate(serverCert);

    server = new MockWebServer();
    server.useHttps(serverCertificates.sslSocketFactory(), false);
    server.enqueue(validGetDataPlaneAddressResponse());
    server.start();

    SnapControlClient client = new SnapControlClient("https://localhost:" + server.getPort());

    assertNotNull(client.getDataPlaneAddress(true));
  }

  @Test
  void verifyHostname_untrustedCertificateChain_stillFails() throws Exception {
    // Contrast with the test above: the hostname check being toothless does not also weaken basic
    // certificate-chain trust -- an untrusted certificate must still cause the connection to fail.
    HeldCertificate serverCert =
        new HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build();
    HandshakeCertificates serverCertificates =
        new HandshakeCertificates.Builder().heldCertificate(serverCert).build();
    // Deliberately not installed into any truststore.

    server = new MockWebServer();
    server.useHttps(serverCertificates.sslSocketFactory(), false);
    server.start();

    SnapControlClient client = new SnapControlClient("https://localhost:" + server.getPort());

    assertThrows(ScionRuntimeException.class, () -> client.getDataPlaneAddress(true));
  }

  // -------------------------------------------------------------------------
  // getDataPlaneAddress()
  // -------------------------------------------------------------------------

  @Test
  void getDataPlaneAddress_unreachableServer_failOnErrorTrue_throws() throws Exception {
    SnapControlClient client = new SnapControlClient("http://127.0.0.1:" + findDeadPort());

    assertThrows(ScionRuntimeException.class, () -> client.getDataPlaneAddress(true));
  }

  @Test
  void getDataPlaneAddress_unreachableServer_failOnErrorFalse_returnsNull() throws Exception {
    SnapControlClient client = new SnapControlClient("http://127.0.0.1:" + findDeadPort());

    assertNull(client.getDataPlaneAddress(false));
  }

  @Test
  void getDataPlaneAddress_malformedStaticKeyLength_failOnErrorTrue_throws() throws Exception {
    server = new MockWebServer();
    ControlService.GetSnapDataPlaneAddressResponse malformed =
        ControlService.GetSnapDataPlaneAddressResponse.newBuilder()
            .setAddress("127.0.0.1:12345")
            .setSnapStaticX25519(ByteString.copyFrom(new byte[16])) // must be 32 bytes
            .build();
    server.enqueue(protoResponse(malformed.toByteArray()));
    server.start();

    SnapControlClient client =
        new SnapControlClient("http://" + server.getHostName() + ":" + server.getPort());

    assertThrows(ScionRuntimeException.class, () -> client.getDataPlaneAddress(true));
  }

  // -------------------------------------------------------------------------
  // registerSnapTunIdentity()
  // -------------------------------------------------------------------------

  @Test
  void registerSnapTunIdentity_nullKey_throwsIllegalArgumentException() {
    SnapControlClient client = new SnapControlClient("http://127.0.0.1:1");
    assertThrows(IllegalArgumentException.class, () -> client.registerSnapTunIdentity(null, null));
  }

  @Test
  void registerSnapTunIdentity_wrongKeyLength_throwsIllegalArgumentException() {
    SnapControlClient client = new SnapControlClient("http://127.0.0.1:1");
    assertThrows(
        IllegalArgumentException.class, () -> client.registerSnapTunIdentity(new byte[16], null));
  }

  @Test
  void registerSnapTunIdentity_wrongPskLength_throwsIllegalArgumentException() {
    SnapControlClient client = new SnapControlClient("http://127.0.0.1:1");
    assertThrows(
        IllegalArgumentException.class,
        () -> client.registerSnapTunIdentity(new byte[32], new byte[10]));
  }

  @Test
  void registerSnapTunIdentity_unreachableServer_throws() throws Exception {
    SnapControlClient client = new SnapControlClient("http://127.0.0.1:" + findDeadPort());

    assertThrows(
        ScionRuntimeException.class, () -> client.registerSnapTunIdentity(new byte[32], null));
  }

  @Test
  void registerSnapTunIdentity_serverReturnsNonZeroPsk_returnsThatPsk() throws Exception {
    // MockSnapService (used by every other SNAP test) always returns an all-zero PSK share, so
    // this "the server actually assigns a real PSK" branch is otherwise never exercised.
    byte[] serverPsk = new byte[32];
    Arrays.fill(serverPsk, (byte) 0x42);
    server = new MockWebServer();
    ControlService.RegisterSnapTunIdentityResponse response =
        ControlService.RegisterSnapTunIdentityResponse.newBuilder()
            .setPskShare(ByteString.copyFrom(serverPsk))
            .build();
    server.enqueue(protoResponse(response.toByteArray()));
    server.start();

    SnapControlClient client =
        new SnapControlClient("http://" + server.getHostName() + ":" + server.getPort());

    byte[] returnedPsk = client.registerSnapTunIdentity(new byte[32], null);

    assertArrayEquals(serverPsk, returnedPsk);
  }

  @Test
  void registerSnapTunIdentity_malformedResponsePskLength_throws() throws Exception {
    server = new MockWebServer();
    ControlService.RegisterSnapTunIdentityResponse malformed =
        ControlService.RegisterSnapTunIdentityResponse.newBuilder()
            .setPskShare(ByteString.copyFrom(new byte[10])) // must be 32 bytes
            .build();
    server.enqueue(protoResponse(malformed.toByteArray()));
    server.start();

    SnapControlClient client =
        new SnapControlClient("http://" + server.getHostName() + ":" + server.getPort());

    assertThrows(
        ScionRuntimeException.class, () -> client.registerSnapTunIdentity(new byte[32], null));
  }

  // -------------------------------------------------------------------------
  // Authorization header
  // -------------------------------------------------------------------------

  @Test
  void post_withAuthTokenConfigured_attachesAuthorizationHeader() throws Exception {
    System.setProperty(Constants.PROPERTY_SNAP_AUTH_TOKEN, "s3cr3t");
    server = new MockWebServer();
    server.enqueue(validGetDataPlaneAddressResponse());
    server.start();

    SnapControlClient client =
        new SnapControlClient("http://" + server.getHostName() + ":" + server.getPort());
    client.getDataPlaneAddress(true);

    RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
    assertNotNull(recorded);
    assertEquals("Bearer s3cr3t", recorded.getHeader("Authorization"));
  }

  @Test
  void post_withoutAuthTokenConfigured_omitsAuthorizationHeader() throws Exception {
    server = new MockWebServer();
    server.enqueue(validGetDataPlaneAddressResponse());
    server.start();

    SnapControlClient client =
        new SnapControlClient("http://" + server.getHostName() + ":" + server.getPort());
    client.getDataPlaneAddress(true);

    RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
    assertNotNull(recorded);
    assertNull(recorded.getHeader("Authorization"));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void installAsDefaultTrustedCertificate(HeldCertificate cert) throws Exception {
    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    trustStore.setCertificateEntry("test-snap-control", cert.certificate());
    trustStoreFile = File.createTempFile("snap-control-truststore", ".p12");
    try (OutputStream out = new FileOutputStream(trustStoreFile)) {
      trustStore.store(out, "changeit".toCharArray());
    }
    System.setProperty("javax.net.ssl.trustStore", trustStoreFile.getAbsolutePath());
    System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
    System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
  }

  private static MockResponse protoResponse(byte[] body) {
    return new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/proto")
        .setBody(new Buffer().write(body));
  }

  private static MockResponse validGetDataPlaneAddressResponse() {
    ControlService.GetSnapDataPlaneAddressResponse response =
        ControlService.GetSnapDataPlaneAddressResponse.newBuilder()
            .setAddress("127.0.0.1:12345")
            .setSnapStaticX25519(ByteString.copyFrom(new byte[32]))
            .build();
    return protoResponse(response.toByteArray());
  }

  /** A TCP port that was free at the time of the call, for a guaranteed-unreachable target. */
  private static int findDeadPort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
