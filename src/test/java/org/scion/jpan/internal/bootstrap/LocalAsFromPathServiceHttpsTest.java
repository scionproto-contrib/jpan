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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.util.Collections;
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
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.endhost.Underlays;

/**
 * Verifies that {@link LocalAsFromPathService} genuinely negotiates TLS when the endhost API
 * address uses an "https://" scheme, rather than silently talking plaintext HTTP to it.
 */
class LocalAsFromPathServiceHttpsTest {

  private static final String TEST_ISD_AS = "64-2:0:9";

  private MockWebServer server;
  private File trustStoreFile;

  @AfterEach
  void afterEach() throws Exception {
    System.clearProperty(Constants.PROPERTY_UNDERLAY_MODE);
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

  @Test
  void create_viaHttps_negotiatesTlsAndParsesSnapUnderlay() throws Exception {
    // A self-signed certificate for the mock endhost API, and a matching client truststore that
    // trusts exactly (and only) that certificate -- proving the client validates the certificate
    // chain rather than skipping TLS verification.
    HeldCertificate serverCert =
        new HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build();
    HandshakeCertificates serverCertificates =
        new HandshakeCertificates.Builder().heldCertificate(serverCert).build();

    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    trustStore.setCertificateEntry("test-endhost-api", serverCert.certificate());
    trustStoreFile = File.createTempFile("endhost-api-truststore", ".p12");
    try (OutputStream out = new FileOutputStream(trustStoreFile)) {
      trustStore.store(out, "changeit".toCharArray());
    }
    System.setProperty("javax.net.ssl.trustStore", trustStoreFile.getAbsolutePath());
    System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
    System.setProperty("javax.net.ssl.trustStorePassword", "changeit");

    long testIsdAs = ScionUtil.parseIA(TEST_ISD_AS);
    Underlays.ListUnderlaysResponse response =
        Underlays.ListUnderlaysResponse.newBuilder()
            .setSnap(
                Underlays.SnapUnderlay.newBuilder()
                    .addSnaps(
                        Underlays.Snap.newBuilder()
                            .setAddress("https://snap.example.com:5001")
                            .addIsdAses(testIsdAs)
                            .build())
                    .build())
            .build();

    server = new MockWebServer();
    server.useHttps(serverCertificates.sslSocketFactory(), false);
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/proto")
            .setBody(new Buffer().write(response.toByteArray())));
    server.start();

    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");
    String endpoint = "https://" + server.getHostName() + ":" + server.getPort();

    LocalAS localAS = LocalAsFromPathService.create(endpoint, TrcStore.createEmpty());

    assertEquals(Collections.singleton(testIsdAs), localAS.getIsdAses());

    RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
    assertNotNull(recorded, "endhost API never received a request");
    assertEquals("/scion.endhost.v1.UnderlayService/ListUnderlays", recorded.getPath());
    // A non-null handshake proves this request was genuinely negotiated over TLS, not plaintext.
    assertNotNull(recorded.getHandshake(), "request was not sent over TLS");
  }

  @Test
  void create_viaHttps_withUntrustedCertificate_fails() throws Exception {
    // No truststore is installed for this test, so the mock server's self-signed certificate is
    // untrusted: access must fail rather than silently downgrading or skipping validation.
    HeldCertificate serverCert =
        new HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build();
    HandshakeCertificates serverCertificates =
        new HandshakeCertificates.Builder().heldCertificate(serverCert).build();

    server = new MockWebServer();
    server.useHttps(serverCertificates.sslSocketFactory(), false);
    server.start();

    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");
    String endpoint = "https://" + server.getHostName() + ":" + server.getPort();

    assertThrows(
        org.scion.jpan.ScionRuntimeException.class,
        () -> LocalAsFromPathService.create(endpoint, TrcStore.createEmpty()));
  }
}
