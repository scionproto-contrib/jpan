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

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.Blake2sDigest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.scion.jpan.proto.snap.ApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock SNAP service providing both the WireGuard-based UDP dataplane and the HTTP control API used
 * by {@link org.scion.jpan.internal.snap.SnapTunnelSession} and {@link
 * org.scion.jpan.internal.snap.SnapControlClient}.
 */
public class MockSnapService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(MockSnapService.class);

  /** Hint address passed to {@link #start(String)}; port 0 means an ephemeral port is chosen. */
  public static final String ADDRESS = "127.0.0.1:24242";

  public static final String SNAP_TOKEN = "42";
  public static final String PATH_SERVICE_TOKEN = "4242";

  /** WireGuard packet types. */
  private static final int TYPE_HANDSHAKE_INIT = 1;

  private static final int TYPE_HANDSHAKE_RESPONSE = 2;

  // These must be identical to the constants in SnapTunnelSession.
  private static final byte[] INITIAL_CHAIN_KEY = {
    96,
    (byte) 226,
    109,
    (byte) 174,
    (byte) 243,
    39,
    (byte) 239,
    (byte) 192,
    46,
    (byte) 195,
    53,
    (byte) 226,
    (byte) 160,
    37,
    (byte) 210,
    (byte) 208,
    22,
    (byte) 235,
    66,
    6,
    (byte) 248,
    114,
    119,
    (byte) 245,
    45,
    56,
    (byte) 209,
    (byte) 152,
    (byte) 139,
    120,
    (byte) 205,
    54
  };
  private static final byte[] INITIAL_CHAIN_HASH = {
    34,
    17,
    (byte) 179,
    97,
    8,
    26,
    (byte) 197,
    102,
    105,
    18,
    67,
    (byte) 219,
    69,
    (byte) 138,
    (byte) 213,
    50,
    45,
    (byte) 156,
    108,
    102,
    34,
    (byte) 147,
    (byte) 232,
    (byte) 183,
    14,
    (byte) 225,
    (byte) 156,
    101,
    (byte) 186,
    7,
    (byte) 158,
    (byte) 243
  };

  // Assigned tunnel address returned to the client after the handshake.
  private static final InetSocketAddress TUNNEL_ASSIGNED_ADDRESS;

  static {
    try {
      TUNNEL_ASSIGNED_ADDRESS =
          new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 0, 0, 1}), 12345);
    } catch (UnknownHostException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final X25519PrivateKeyParameters staticPrivate;
  private final byte[] staticPublic;
  private final DatagramChannel dataplaneChannel;
  private final InetSocketAddress dataplaneAddress;
  private volatile boolean running;
  private Thread dataplaneThread;
  private SnapControlServer httpServer;

  private MockSnapService(int httpPort, String expectedToken) throws IOException {
    SecureRandom rng = new SecureRandom();
    staticPrivate = new X25519PrivateKeyParameters(rng);
    staticPublic = staticPrivate.generatePublicKey().getEncoded();

    dataplaneChannel = DatagramChannel.open(StandardProtocolFamily.INET);
    dataplaneChannel.bind(new InetSocketAddress("127.0.0.1", 0));
    dataplaneAddress = (InetSocketAddress) dataplaneChannel.getLocalAddress();

    httpServer = new SnapControlServer(httpPort, expectedToken);
  }

  public static MockSnapService start(String address) {
    return start(address, null);
  }

  /**
   * Starts the service and requires a {@code Bearer <expectedToken>} on all control requests when
   * {@code expectedToken} is non-null.
   */
  public static MockSnapService start(String address, String expectedToken) {
    int port = parsePort(address);
    try {
      MockSnapService service = new MockSnapService(port, expectedToken);
      service.startInternal();
      return service;
    } catch (IOException e) {
      throw new RuntimeException("Failed to start MockSnapService at " + address, e);
    }
  }

  private void startInternal() {
    // The HTTP server starts itself inside the SnapControlServer constructor.
    while (!httpServer.wasStarted()) {
      TestUtil.sleep(1);
    }
    log.info(
        "MockSnapService HTTP control started on port {}, UDP dataplane on {}",
        httpServer.getListeningPort(),
        dataplaneAddress);

    running = true;
    dataplaneThread = new Thread(this::dataplaneLoop, "MockSnapService-dataplane");
    dataplaneThread.setDaemon(true);
    dataplaneThread.start();
  }

  private void dataplaneLoop() {
    ByteBuffer buf = ByteBuffer.allocate(4096);
    while (running) {
      try {
        buf.clear();
        InetSocketAddress sender = (InetSocketAddress) dataplaneChannel.receive(buf);
        if (sender == null) {
          TestUtil.sleep(5);
          continue;
        }
        buf.flip();
        byte[] packet = new byte[buf.remaining()];
        buf.get(packet);
        if (packet.length >= 4) {
          int type = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt();
          if (type == TYPE_HANDSHAKE_INIT) {
            byte[] response = processHandshakeInit(packet);
            if (response != null) {
              dataplaneChannel.send(ByteBuffer.wrap(response), sender);
              log.debug("Sent SNAP handshake response to {}", sender);
            }
          }
        }
      } catch (ClosedChannelException e) {
        break;
      } catch (IOException e) {
        if (running) {
          log.error("Error in MockSnapService dataplane loop", e);
        }
      }
    }
  }

  private byte[] processHandshakeInit(byte[] packet) {
    if (packet.length < 148) {
      return null;
    }
    ByteBuffer in = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    in.getInt(); // type, already checked
    int senderIndex = in.getInt();
    byte[] eiPub = new byte[32];
    byte[] encStatic = new byte[48];
    byte[] encTimestamp = new byte[28];
    in.get(eiPub);
    in.get(encStatic);
    in.get(encTimestamp);

    try {
      // Replicate the initiator's chain key and hash state up to the point of
      // sending the init packet, then continue with the responder's additions.
      byte[] hash = Arrays.copyOf(INITIAL_CHAIN_HASH, 32);
      byte[] ck = Arrays.copyOf(INITIAL_CHAIN_KEY, 32);

      hash = b2sHash(hash, staticPublic); // H1 = H(H0, peer_static)
      hash = b2sHash(hash, eiPub); // H2 = H(H1, e_i_pub)
      ck = b2sHmac(b2sHmac(ck, eiPub), new byte[] {0x01}); // CK1

      // Decrypt the initiator's static public key.
      X25519PublicKeyParameters eiPubKey = new X25519PublicKeyParameters(eiPub, 0);
      byte[] temp = b2sHmac(ck, dh(staticPrivate, eiPubKey));
      ck = b2sHmac(temp, new byte[] {0x01}); // CK2
      byte[] key = b2sHmac2(temp, ck, new byte[] {0x02});
      byte[] csPub = aeadOpen(key, 0, encStatic, hash, 32);
      hash = b2sHash(hash, encStatic); // H3

      // Advance chain key past the timestamp (no need to decrypt it).
      X25519PublicKeyParameters csPubKey = new X25519PublicKeyParameters(csPub, 0);
      temp = b2sHmac(ck, dh(staticPrivate, csPubKey));
      ck = b2sHmac(temp, new byte[] {0x01}); // CK3
      hash = b2sHash(hash, encTimestamp); // H4

      // Generate the responder's ephemeral key pair.
      SecureRandom rng = new SecureRandom();
      X25519PrivateKeyParameters erPriv = new X25519PrivateKeyParameters(rng);
      byte[] erPub = erPriv.generatePublicKey().getEncoded();

      hash = b2sHash(hash, erPub); // H5

      // Build the responder's side of the noise handshake (mirrors the initiator's response
      // processing in SnapTunnelSession.ensureConnected).
      temp = b2sHmac(ck, erPub); // HMAC(CK3, e_r_pub) — raw bytes, SNAP variant
      ck = b2sHmac(temp, new byte[] {0x01}); // CK4
      temp = b2sHmac(ck, dh(erPriv, eiPubKey)); // HMAC(CK4, DH(e_r, e_i))
      ck = b2sHmac(temp, new byte[] {0x01}); // CK5
      temp = b2sHmac(ck, dh(erPriv, csPubKey)); // HMAC(CK5, DH(e_r, c_s))
      ck = b2sHmac(temp, new byte[] {0x01}); // CK6
      temp = b2sHmac(ck, new byte[32]); // HMAC(CK6, PSK=zeros)
      ck = b2sHmac(temp, new byte[] {0x01}); // CK7

      byte[] temp2 = b2sHmac2(temp, ck, new byte[] {0x02});
      byte[] encKey = b2sHmac2(temp, temp2, new byte[] {0x03});
      hash = b2sHash(hash, temp2); // H6

      // Encrypt the tunnel socket address assigned to this session.
      byte[] sockAddrBytes = encodeSockAddr(TUNNEL_ASSIGNED_ADDRESS);
      byte[] encSockAddr = aeadSeal(encKey, 0, sockAddrBytes, hash); // 36 bytes

      // Build response: type(4) + serverIdx(4) + receiverIdx(4) + e_r_pub(32) + encSockAddr(36)
      // + zeros(32) = 112
      byte[] response = new byte[112];
      ByteBuffer out = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
      out.putInt(TYPE_HANDSHAKE_RESPONSE);
      out.putInt(1); // server sender index
      out.putInt(senderIndex);
      out.put(erPub);
      out.put(encSockAddr);
      // remaining 32 bytes stay zero (MACs not used in this SNAP variant)
      return response;
    } catch (InvalidCipherTextException e) {
      log.error("MockSnapService: crypto error during handshake", e);
      return null;
    }
  }

  /** Returns the server's X25519 static public key (32 bytes). */
  public byte[] getStaticPublicKey() {
    return Arrays.copyOf(staticPublic, staticPublic.length);
  }

  /** Returns the UDP address of the SNAP dataplane. */
  public InetSocketAddress getDataplaneAddress() {
    return dataplaneAddress;
  }

  /** Returns the HTTP control server address in {@code host:port} form. */
  public String getControlAddress() {
    return "127.0.0.1:" + httpServer.getListeningPort();
  }

  /** Returns the HTTP control server base URL, e.g. {@code http://127.0.0.1:PORT}. */
  public String getControlUrl() {
    return "http://127.0.0.1:" + httpServer.getListeningPort();
  }

  @Override
  public void close() {
    running = false;
    try {
      dataplaneChannel.close();
    } catch (IOException e) {
      log.warn("Error closing MockSnapService dataplane channel", e);
    }
    if (dataplaneThread != null) {
      try {
        dataplaneThread.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (httpServer != null) {
      httpServer.stop();
    }
    log.info("MockSnapService stopped");
  }

  // -------------------------------------------------------------------------
  // HTTP control server
  // -------------------------------------------------------------------------

  private class SnapControlServer extends SimpleHttpServer {

    private final String expectedToken;

    SnapControlServer(int port, String expectedToken) throws IOException {
      super(port);
      this.expectedToken = expectedToken;
      super.start();
    }

    @Override
    public Response serve(Session session) {
      if (expectedToken != null) {
        String auth = session.getAuthorization();
        if (!("Bearer " + expectedToken).equals(auth)) {
          return newFixedLengthResponse(
              Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized");
        }
      }
      if (session.getMethod() != RequestMethod.POST) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "POST expected");
      }
      String uri = session.getUri();
      if (uri.endsWith("/GetSnapDataPlaneAddress")) {
        return handleGetDataPlaneAddress(session);
      }
      if (uri.endsWith("/RegisterSnapTunIdentity")) {
        return handleRegisterIdentity(session);
      }
      return newFixedLengthResponse(
          Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown endpoint: " + uri);
    }

    private Response handleGetDataPlaneAddress(Session session) {
      try {
        // Parse request (body is empty for GetSnapDataPlaneRequest).
        session.getInputStream().read(new byte[4096]);
      } catch (IOException e) {
        // ignore
      }
      String dpAddress =
          dataplaneAddress.getAddress().getHostAddress() + ":" + dataplaneAddress.getPort();
      ApiService.GetSnapDataPlaneResponse response =
          ApiService.GetSnapDataPlaneResponse.newBuilder()
              .setAddress(dpAddress)
              .setSnapStaticX25519(ByteString.copyFrom(staticPublic))
              .build();
      byte[] body = response.toByteArray();
      return newFixedLengthResponse(
          Response.Status.OK,
          "application/proto",
          new java.io.ByteArrayInputStream(body),
          body.length);
    }

    private Response handleRegisterIdentity(Session session) {
      try {
        // Parse request but ignore the content for the mock.
        byte[] buf = new byte[4096];
        int n = session.getInputStream().read(buf);
        ApiService.RegisterSnapTunIdentityRequest.newBuilder()
            .mergeFrom(buf, 0, n > 0 ? n : 0)
            .build();
      } catch (IOException e) {
        // ignore parse errors in mock
      }
      // Return all-zeros PSK share: the client treats zeros as "no PSK".
      ApiService.RegisterSnapTunIdentityResponse response =
          ApiService.RegisterSnapTunIdentityResponse.newBuilder()
              .setPskShare(ByteString.copyFrom(new byte[32]))
              .build();
      byte[] body = response.toByteArray();
      return newFixedLengthResponse(
          Response.Status.OK,
          "application/proto",
          new java.io.ByteArrayInputStream(body),
          body.length);
    }
  }

  // -------------------------------------------------------------------------
  // Wire encoding
  // -------------------------------------------------------------------------

  /**
   * Encodes a socket address as the 20-byte SNAP format: ipVersion(1) + reserved(1) + port(2,BE) +
   * ip(16).
   */
  private static byte[] encodeSockAddr(InetSocketAddress addr) {
    ByteBuffer out = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
    out.put((byte) 0x04); // IPv4
    out.put((byte) 0x00); // reserved
    out.putShort((short) addr.getPort());
    byte[] ip4 = addr.getAddress().getAddress(); // 4 bytes
    byte[] ipBytes = new byte[16];
    System.arraycopy(ip4, 0, ipBytes, 12, 4); // IPv4 goes in the last 4 bytes
    out.put(ipBytes);
    return out.array();
  }

  // -------------------------------------------------------------------------
  // Crypto helpers (mirrors private methods in SnapTunnelSession)
  // -------------------------------------------------------------------------

  private static byte[] dh(
      X25519PrivateKeyParameters privateKey, X25519PublicKeyParameters publicKey) {
    byte[] secret = new byte[32];
    privateKey.generateSecret(publicKey, secret, 0);
    return secret;
  }

  private static byte[] aeadSeal(byte[] key, long counter, byte[] data, byte[] aad)
      throws InvalidCipherTextException {
    byte[] nonce = buildNonce(counter);
    ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
    cipher.init(true, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
    byte[] out = new byte[cipher.getOutputSize(data.length)];
    int len = cipher.processBytes(data, 0, data.length, out, 0);
    len += cipher.doFinal(out, len);
    return Arrays.copyOf(out, len);
  }

  private static byte[] aeadOpen(byte[] key, long counter, byte[] data, byte[] aad, int plainLen)
      throws InvalidCipherTextException {
    byte[] nonce = buildNonce(counter);
    ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
    cipher.init(false, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
    byte[] out = new byte[Math.max(plainLen, cipher.getOutputSize(data.length))];
    int len = cipher.processBytes(data, 0, data.length, out, 0);
    len += cipher.doFinal(out, len);
    return Arrays.copyOf(out, len);
  }

  private static byte[] buildNonce(long counter) {
    // WireGuard nonce: 4 zero bytes + 8-byte LE counter
    byte[] nonce = new byte[12];
    ((ByteBuffer) ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).position(4))
        .asLongBuffer()
        .put(counter);
    return nonce;
  }

  private static byte[] b2sHash(byte[] data1, byte[] data2) {
    Blake2sDigest digest = new Blake2sDigest(256);
    digest.update(data1, 0, data1.length);
    digest.update(data2, 0, data2.length);
    byte[] out = new byte[32];
    digest.doFinal(out, 0);
    return out;
  }

  private static byte[] b2sHmac(byte[] key, byte[] data) {
    HMac hmac = new HMac(new Blake2sDigest(256));
    hmac.init(new KeyParameter(key));
    hmac.update(data, 0, data.length);
    byte[] out = new byte[32];
    hmac.doFinal(out, 0);
    return out;
  }

  private static byte[] b2sHmac2(byte[] key, byte[] data1, byte[] data2) {
    HMac hmac = new HMac(new Blake2sDigest(256));
    hmac.init(new KeyParameter(key));
    hmac.update(data1, 0, data1.length);
    hmac.update(data2, 0, data2.length);
    byte[] out = new byte[32];
    hmac.doFinal(out, 0);
    return out;
  }

  // -------------------------------------------------------------------------
  // Utilities
  // -------------------------------------------------------------------------

  private static int parsePort(String address) {
    // Handles both "host:port" and "[ipv6]:port".
    int last = address.lastIndexOf(':');
    if (last < 0) {
      throw new IllegalArgumentException("Cannot parse port from address: " + address);
    }
    return Integer.parseInt(address.substring(last + 1));
  }
}
