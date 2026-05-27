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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import org.scion.jpan.ScionRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SNAP tunnel session using the SNAP-specific WireGuard handshake from ana-gotatun. */
public class SnapTunnelSession {

  private static final Logger log = LoggerFactory.getLogger(SnapTunnelSession.class);

  private static final byte[] LABEL_MAC1 =
      "mac1----".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  private static final byte[] INITIAL_CHAIN_KEY =
      new byte[] {
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
  private static final byte[] INITIAL_CHAIN_HASH =
      new byte[] {
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
  private static final int KEY_LEN = 32;
  private static final int TIMESTAMP_LEN = 12;

  /**
   * Re-handshake before the server's REJECT_AFTER_TIME (180s). Use 120s to match REKEY_AFTER_TIME.
   */
  private static final long REKEY_AFTER_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(120);

  private final DatagramChannel underlay;
  private final InetSocketAddress dataPlane;
  private final byte[] peerStatic;
  private final SnapControlClient snapControlClient;
  private final SecureRandom random = new SecureRandom();

  // Static identity — generated once, reused across re-handshakes (like Rust client)
  private X25519PrivateKeyParameters localStaticPrivate;
  private byte[] localStaticPublic;
  private byte[] presharedKey;
  private boolean identityRegistered;

  private int localIndex;
  private int peerIndex;
  private int nextIndex;
  private byte[] sendKey;
  private byte[] recvKey;
  private long sendNonce;
  private boolean established;
  private long establishedAtNanos;
  private InetSocketAddress localTunnelAddress;

  public SnapTunnelSession(
      DatagramChannel underlay,
      InetSocketAddress dataPlane,
      byte[] peerStatic,
      SnapControlClient snapControlClient) {
    try {
      this.underlay = DatagramChannel.open(StandardProtocolFamily.INET);
      this.underlay.configureBlocking(false);
      this.underlay.bind(null);
    } catch (IOException e) {
      throw new ScionRuntimeException("failed to open SNAP underlay socket", e);
    }
    this.dataPlane = dataPlane;
    this.peerStatic = peerStatic;
    this.snapControlClient = snapControlClient;
  }

  private void ensureIdentityRegistered() {
    if (identityRegistered) {
      return;
    }
    localStaticPrivate = new X25519PrivateKeyParameters(random);
    localStaticPublic = localStaticPrivate.generatePublicKey().getEncoded();
    byte[] pskShare =
        snapControlClient != null
            ? snapControlClient.registerSnapTunIdentity(localStaticPublic, null)
            : null;
    presharedKey = pskShare == null ? new byte[32] : pskShare;
    identityRegistered = true;
    log.debug("Registered identity, PSK share null={}", pskShare == null);
  }

  public synchronized void ensureConnected() throws IOException {
    if (established) {
      long ageSeconds = (System.nanoTime() - establishedAtNanos) / 1_000_000_000L;
      if (ageSeconds < 120) {
        return;
      }
      log.info("SNAP session expired after {}s, re-handshaking", ageSeconds);
    }
    established = false;

    try {
      ensureIdentityRegistered();
      X25519PublicKeyParameters peerStaticPublic = new X25519PublicKeyParameters(peerStatic, 0);

      localIndex = incrementLocalIndex();

      X25519PrivateKeyParameters ephemeralPrivate = new X25519PrivateKeyParameters(random);
      byte[] ephemeralPublic = ephemeralPrivate.generatePublicKey().getEncoded();

      byte[] chainingKey = Arrays.copyOf(INITIAL_CHAIN_KEY, KEY_LEN);
      byte[] hash = Arrays.copyOf(INITIAL_CHAIN_HASH, KEY_LEN);
      hash = b2sHash(hash, peerStatic);
      hash = b2sHash(hash, ephemeralPublic);
      chainingKey = b2sHmac(b2sHmac(chainingKey, ephemeralPublic), new byte[] {0x01});

      byte[] temp = b2sHmac(chainingKey, diffieHellman(ephemeralPrivate, peerStaticPublic));
      chainingKey = b2sHmac(temp, new byte[] {0x01});
      byte[] key = b2sHmac2(temp, chainingKey, new byte[] {0x02});
      byte[] encryptedStatic = aeadChaCha20Seal(key, 0, localStaticPublic, hash);

      hash = b2sHash(hash, encryptedStatic);
      temp = b2sHmac(chainingKey, diffieHellman(localStaticPrivate, peerStaticPublic));
      chainingKey = b2sHmac(temp, new byte[] {0x01});
      key = b2sHmac2(temp, chainingKey, new byte[] {0x02});
      byte[] encryptedTimestamp = aeadChaCha20Seal(key, 0, tai64nStamp(), hash);

      hash = b2sHash(hash, encryptedTimestamp);

      byte[] noisePayload = new byte[108];
      ByteBuffer.wrap(noisePayload)
          .put(ephemeralPublic)
          .put(encryptedStatic)
          .put(encryptedTimestamp);
      byte[] mac1 = computeMac1(localIndex, noisePayload);
      byte[] initPacket =
          WireGuardPacket.buildHandshakeInit(localIndex, noisePayload, mac1, new byte[16]);
      // Drain stale packets before sending handshake init
      ByteBuffer drain = ByteBuffer.allocate(4096);
      while (underlay.receive(drain) != null) {
        drain.clear();
      }

      underlay.send(ByteBuffer.wrap(initPacket), dataPlane);

      ByteBuffer recv = ByteBuffer.allocate(4096);
      WireGuardPacket.HandshakeResponse response;
      long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (true) {
        InetSocketAddress src = (InetSocketAddress) underlay.receive(recv);
        if (src == null) {
          if (System.nanoTime() >= deadlineNanos) {
            throw new IOException("timed out waiting for SNAP handshake response");
          }
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for SNAP handshake response", e);
          }
          continue;
        }
        if (!dataPlane.equals(src)) {
          recv.clear();
          continue;
        }
        recv.flip();
        byte[] packet = new byte[recv.remaining()];
        recv.get(packet);
        recv.clear();
        response = WireGuardPacket.parseHandshakeResponse(packet);
        if (response != null && response.receiverIndex == localIndex) {
          break;
        }
      }

      byte[] responseEphemeral = Arrays.copyOfRange(response.noisePayload68, 0, 32);
      byte[] responseSocketAddrCipher = Arrays.copyOfRange(response.noisePayload68, 32, 68);
      X25519PublicKeyParameters responseEphemeralPublic =
          new X25519PublicKeyParameters(responseEphemeral, 0);

      hash = b2sHash(hash, responseEphemeral);
      temp = b2sHmac(chainingKey, responseEphemeral);
      chainingKey = b2sHmac(temp, new byte[] {0x01});
      temp = b2sHmac(chainingKey, diffieHellman(ephemeralPrivate, responseEphemeralPublic));
      chainingKey = b2sHmac(temp, new byte[] {0x01});
      temp = b2sHmac(chainingKey, diffieHellman(localStaticPrivate, responseEphemeralPublic));
      chainingKey = b2sHmac(temp, new byte[] {0x01});
      temp = b2sHmac(chainingKey, presharedKey);
      chainingKey = b2sHmac(temp, new byte[] {0x01});
      byte[] temp2 = b2sHmac2(temp, chainingKey, new byte[] {0x02});
      key = b2sHmac2(temp, temp2, new byte[] {0x03});
      hash = b2sHash(hash, temp2);

      byte[] assignedSockAddr = aeadChaCha20Open(key, 0, responseSocketAddrCipher, hash, 20);
      localTunnelAddress = parseSnapSocketAddress(assignedSockAddr);

      byte[] temp1 = b2sHmac(chainingKey, new byte[0]);
      sendKey = b2sHmac(temp1, new byte[] {0x01});
      recvKey = b2sHmac2(temp1, sendKey, new byte[] {0x02});
      peerIndex = response.senderIndex;
      sendNonce = 0;
      established = true;
      establishedAtNanos = System.nanoTime();
      log.info("SNAP tunnel established, assigned address: {}", localTunnelAddress);
    } catch (InvalidCipherTextException e) {
      throw new ScionRuntimeException("failed to establish SNAP tunnel", e);
    }
  }

  public synchronized byte[] encrypt(byte[] scionPacket) {
    if (!established) {
      throw new IllegalStateException("SNAP tunnel not connected");
    }
    try {
      byte[] ciphertext = aeadChaCha20Seal(sendKey, sendNonce, scionPacket, new byte[0]);
      byte[] packet = WireGuardPacket.buildDataPacket(peerIndex, sendNonce, ciphertext);
      sendNonce++;
      return packet;
    } catch (InvalidCipherTextException e) {
      throw new ScionRuntimeException("SNAP encrypt failed", e);
    }
  }

  public synchronized byte[] decrypt(byte[] wireguardPacket) {
    if (!established) {
      return null;
    }
    WireGuardPacket.DataPacket packet = WireGuardPacket.parseDataPacket(wireguardPacket);
    if (packet == null || packet.receiverIndex != localIndex) {
      return null;
    }
    try {
      return aeadChaCha20Open(
          recvKey, packet.counter, packet.ciphertext, new byte[0], packet.ciphertext.length - 16);
    } catch (InvalidCipherTextException e) {
      return null;
    }
  }

  public InetSocketAddress dataPlaneAddress() {
    return dataPlane;
  }

  public synchronized InetSocketAddress localTunnelAddress() {
    return localTunnelAddress;
  }

  public synchronized int sendPacket(byte[] scionPacket) throws IOException {
    ensureConnected();
    byte[] wg = encrypt(scionPacket);
    return underlay.send(ByteBuffer.wrap(wg), dataPlane);
  }

  public synchronized InetSocketAddress receivePacket(ByteBuffer buffer) throws IOException {
    ensureConnected();
    ByteBuffer underlayBuf = ByteBuffer.allocate(65535);
    while (true) {
      InetSocketAddress srcAddress = (InetSocketAddress) underlay.receive(underlayBuf);
      if (srcAddress == null) {
        return null;
      }
      if (!dataPlane.equals(srcAddress)) {
        underlayBuf.clear();
        continue;
      }
      underlayBuf.flip();
      byte[] wg = new byte[underlayBuf.remaining()];
      underlayBuf.get(wg);
      underlayBuf.clear();
      byte[] scion = decrypt(wg);
      if (scion == null) {
        continue;
      }
      buffer.put(scion);
      return srcAddress;
    }
  }

  private byte[] computeMac1(int senderIndex, byte[] noisePayload) {
    byte[] packetWithoutMac = new byte[8 + noisePayload.length];
    ByteBuffer.wrap(packetWithoutMac)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(WireGuardPacket.TYPE_HANDSHAKE_INIT)
        .putInt(senderIndex)
        .put(noisePayload);

    byte[] mac1Key = b2sHash(LABEL_MAC1, peerStatic);
    return b2sKeyedMac16(mac1Key, packetWithoutMac);
  }

  private int incrementLocalIndex() {
    int index = nextIndex;
    int idx8 = index & 0xff;
    nextIndex = (index & ~0xff) | ((idx8 + 1) & 0xff);
    return nextIndex;
  }

  private static byte[] diffieHellman(
      X25519PrivateKeyParameters privateKey, X25519PublicKeyParameters publicKey) {
    byte[] secret = new byte[32];
    privateKey.generateSecret(publicKey, secret, 0);
    return secret;
  }

  private static byte[] aeadChaCha20Seal(byte[] key, long counter, byte[] data, byte[] aad)
      throws InvalidCipherTextException {
    byte[] nonce = new byte[12];
    ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).position(4).asLongBuffer().put(counter);
    ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
    cipher.init(true, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
    byte[] out = new byte[cipher.getOutputSize(data.length)];
    int len = cipher.processBytes(data, 0, data.length, out, 0);
    len += cipher.doFinal(out, len);
    return Arrays.copyOf(out, len);
  }

  private static byte[] aeadChaCha20Open(
      byte[] key, long counter, byte[] data, byte[] aad, int plainLen)
      throws InvalidCipherTextException {
    byte[] nonce = new byte[12];
    ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).position(4).asLongBuffer().put(counter);
    ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
    cipher.init(false, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
    byte[] out = new byte[Math.max(plainLen, cipher.getOutputSize(data.length))];
    int len = cipher.processBytes(data, 0, data.length, out, 0);
    len += cipher.doFinal(out, len);
    return Arrays.copyOf(out, len);
  }

  private static byte[] tai64nStamp() {
    final long tai64Base = (1L << 62) + 37;
    long nowMillis = System.currentTimeMillis();
    long secs = Math.floorDiv(nowMillis, 1000L);
    long nanos = Math.floorMod(nowMillis, 1000L) * 1_000_000L;
    ByteBuffer out = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
    out.putLong(secs + tai64Base);
    out.putInt((int) nanos);
    return out.array();
  }

  private static byte[] b2sHash(byte[] data1, byte[] data2) {
    Blake2sDigest digest = new Blake2sDigest(256);
    digest.update(data1, 0, data1.length);
    digest.update(data2, 0, data2.length);
    byte[] out = new byte[32];
    digest.doFinal(out, 0);
    return out;
  }

  private static byte[] b2sHmac(byte[] key, byte[] data1) {
    HMac hmac = new HMac(new Blake2sDigest(256));
    hmac.init(new KeyParameter(key));
    hmac.update(data1, 0, data1.length);
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

  private static byte[] b2sKeyedMac16(byte[] key, byte[] data1) {
    Blake2sDigest digest = new Blake2sDigest(key, 16, null, null);
    digest.update(data1, 0, data1.length);
    byte[] out = new byte[16];
    digest.doFinal(out, 0);
    return out;
  }

  private static InetSocketAddress parseSnapSocketAddress(byte[] encoded) {
    if (encoded.length != 20) {
      throw new IllegalArgumentException("SNAP socket address encoding must be 20 bytes");
    }
    ByteBuffer in = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
    int ipVersion = Byte.toUnsignedInt(in.get());
    int reserved = Byte.toUnsignedInt(in.get());
    if (reserved != 0) {
      throw new ScionRuntimeException("SNAP socket address reserved byte is non-zero: " + reserved);
    }
    int port = Short.toUnsignedInt(in.getShort());
    byte[] ipBytes = new byte[16];
    in.get(ipBytes);
    try {
      if (ipVersion == 0x04) {
        return new InetSocketAddress(
            InetAddress.getByAddress(Arrays.copyOfRange(ipBytes, 12, 16)), port);
      }
      if (ipVersion == 0x06) {
        return new InetSocketAddress(InetAddress.getByAddress(ipBytes), port);
      }
      throw new ScionRuntimeException("unsupported SNAP socket address IP version: " + ipVersion);
    } catch (java.net.UnknownHostException e) {
      throw new ScionRuntimeException("invalid SNAP socket address", e);
    }
  }
}
