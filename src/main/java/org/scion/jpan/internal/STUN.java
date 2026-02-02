// Copyright 2024 ETH Zurich
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

package org.scion.jpan.internal;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.internal.util.ByteUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class STUN {

  private static final Logger log = LoggerFactory.getLogger(STUN.class);

  private static final SecureRandom rnd = new SecureRandom();

  private static final int MAGIC_COOKIE = 0x2112A442;
  private static final int FINGERPRINT_XOR = 0x5354554e;
  private static final boolean ADD_FINGERPRINT = true;

  // This must be UTF-8
  private static final String SOFTWARE_ID = "jpan.scion.org v0.4.0";

  public static boolean isStunPacket(ByteBuffer buf, TransactionID id) {
    try {
      return buf.getShort(2) + 20 == buf.remaining()
          && buf.getInt(4) == MAGIC_COOKIE
          && buf.getInt(8) == id.id0
          && buf.getInt(12) == id.id1
          && buf.getInt(16) == id.id2;
    } catch (IndexOutOfBoundsException e) {
      // ignore, bad packet
      return false;
    }
  }

  public static boolean isStunResponse(ByteBuffer buf, TransactionID id) {
    byte b0 = buf.get(0);
    byte b1 = buf.get(1);
    return isStunPacket(buf, id) && b0 == 0x1 && b1 == 0x1;
  }

  public static boolean isStunRequest(ByteBuffer buf) {
    return buf.get(0) == 0x0
        && buf.get(1) == 0x1
        && buf.getShort(2) + 20 == buf.remaining()
        && buf.getInt(4) == MAGIC_COOKIE;
  }

  public static InetSocketAddress parseResponse(
      ByteBuffer in,
      Predicate<TransactionID> idHandler,
      ByteUtil.MutRef<TransactionID> txIdOut,
      ByteUtil.MutRef<String> error) {
    parseHeader(in, idHandler, txIdOut, error);
    if (error.get() != null) {
      return null;
    }
    return parseBody(in, error);
  }

  public static TransactionID parseRequest(ByteBuffer in) {
    ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
    ByteUtil.MutRef<TransactionID> txIdOut = new ByteUtil.MutRef<>();
    parseHeader(in, transactionID -> true, txIdOut, error);
    if (error.get() != null) {
      return null;
    }
    // TODO ?
    //   parseBody(in, error);
    return txIdOut.get();
  }

  private static void parseHeader(
      ByteBuffer in,
      Predicate<TransactionID> idHandler,
      ByteUtil.MutRef<TransactionID> txIdOut,
      ByteUtil.MutRef<String> error) {
    if (in.remaining() < 20) {
      error.set("STUN validation error, packet too short.");
      return;
    }
    in.get();
    in.get();
    int dataLength = in.getShort();
    if (in.remaining() - 16 != dataLength) {
      error.set("STUN validation error, invalid length.");
      return;
    }
    int magic = in.getInt();
    if (magic != MAGIC_COOKIE) {
      error.set("STUN validation error, invalid MAGIC_COOKIE.");
      return;
    }

    int txId1 = in.getInt();
    int txId2 = in.getInt();
    int txId3 = in.getInt();
    TransactionID id = TransactionID.from(txId1, txId2, txId3);
    txIdOut.set(id);
    if (!idHandler.test(id)) {
      error.set("STUN validation error, TxID validation failed.");
    }
  }

  private static InetSocketAddress parseBody(ByteBuffer in, ByteUtil.MutRef<String> error) {
    InetSocketAddress mappedAddress = null;
    InetSocketAddress mappedAddressXor = null;

    // read again as byte[]
    in.position(in.position() - 16);
    byte[] txId = new byte[16];
    in.get(txId);

    // Attributes
    while (in.remaining() > 0) {
      int typeInt = ByteUtil.toUnsigned(in.getShort());
      int len = in.getShort();
      Type typeEnum = Type.parse(typeInt);
      switch (typeEnum) {
        case MAPPED_ADDRESS:
          mappedAddress = readMappedAddress(in);
          log.info("MAPPED_ADDRESS: {}", mappedAddress);
          break;
        case WAS_SOURCE_ADDRESS:
          log.info("SOURCE_ADDRESS: {}", readMappedAddress(in));
          break;
        case WAS_CHANGED_ADDRESS:
          log.info("CHANGED_ADDRESS: {}", readMappedAddress(in));
          break;
        case XOR_MAPPED_ADDRESS:
          mappedAddressXor = readXorMappedAddress(in, txId);
          log.info("XOR_MAPPED_ADDRESS: {}", mappedAddressXor);
          break;
        case OLD_XOR_MAPPED_ADDRESS:
          mappedAddressXor = readXorMappedAddress(in, txId);
          log.info("OLD_XOR_MAPPED_ADDRESS: {}", mappedAddressXor);
          break;
        case SOFTWARE:
          String software = readSoftware(in, len);
          log.info("SOFTWARE: {}", software);
          break;
        case XXX_RESERVATION_TOKEN:
          // ignore
          in.position(in.position() + len);
          break;
        case ERROR_CODE:
          String errorMessage = readErrorCode(in);
          error.set(errorMessage);
          log.error(errorMessage);
          break;
        case FINGERPRINT:
          boolean match = readFingerprint(in);
          log.info("FINGERPRINT: match = {}", match);
          if (!match) {
            return null;
          }
          break;
        case RESPONSE_ORIGIN:
          log.info("RESPONSE_ORIGIN: {}", readMappedAddress(in));
          break;
        case OTHER_ADDRESS:
          log.info("OTHER_ADDRESS: {}", readMappedAddress(in));
          break;
        default:
          byte[] data = new byte[len];
          in.get(data);
          error.set("ERROR: Type not implemented: " + typeEnum);
          log.error(error.get());
          return null;
      }
    }

    if (mappedAddress != null
        && mappedAddressXor != null
        && !mappedAddress.equals(mappedAddressXor)) {
      log.error("Mismatch: {} <-> {}", mappedAddress, mappedAddressXor);
      // We ignore this for now, because 3 out of 41 XOR responses return bogus addresses...
    }
    return mappedAddressXor != null ? mappedAddressXor : mappedAddress;
  }

  private static boolean readFingerprint(ByteBuffer in) {
    int fpPos = in.position() - 4;
    int packetCRC = in.getInt();

    // calculate fingerprint
    CRC32 crc32 = new CRC32();
    in.flip();
    in.position(0);
    in.limit(fpPos);
    crc32.update(in);
    in.limit(fpPos + 8);

    long fingerprintLong = crc32.getValue() ^ FINGERPRINT_XOR;
    int fingerPrint32 = ByteUtil.toInt(fingerprintLong);

    // check fingerprint
    in.position(fpPos + 8);

    if (packetCRC != fingerPrint32) {
      log.error("FINGERPRINT mismatch: packet = {} vs calculated = {}", packetCRC, fingerPrint32);
      return false;
    }
    return true;
  }

  private static String readErrorCode(ByteBuffer in) {
    int i0 = in.getInt();
    int errorClass = ByteUtil.readInt(i0, 21, 3);
    int errorNumber = ByteUtil.readInt(i0, 24, 8);
    int errorCode = errorClass * 100 + errorNumber;
    byte[] bytes = new byte[in.remaining()];
    in.get(bytes);
    String msg = new String(bytes);
    if (!processError(errorCode).isEmpty()) {
      msg = msg + "\n" + processError(errorCode);
    }
    return "Remote error " + errorCode + ": " + msg;
  }

  private static InetSocketAddress readMappedAddress(ByteBuffer in) {
    in.get(); // MUST be ignored
    byte family = in.get();
    int port = ByteUtil.toUnsigned(in.getShort());
    byte[] bytes;
    if (family == 0x01) {
      bytes = new byte[4];
    } else if (family == 0x02) {
      bytes = new byte[16];
    } else {
      log.error("Unknown address family for MAPPED_ADDRESS: {}", family);
      return null;
    }
    in.get(bytes);
    try {
      InetAddress address = InetAddress.getByAddress(bytes);
      return new InetSocketAddress(address, port);
    } catch (UnknownHostException e) {
      // This should never happen
      throw new ScionRuntimeException(e);
    }
  }

  private static String readSoftware(ByteBuffer in, int length) {
    length = (length + 3) & 0xfffc;
    byte[] bytes = new byte[length];
    in.get(bytes);
    return new String(bytes);
  }

  private static InetSocketAddress readXorMappedAddress(ByteBuffer in, byte[] id) {
    in.get(); // MUST be ignored
    byte family = in.get();
    int port = ByteUtil.toUnsigned(in.getShort());
    port ^= 0x2112;
    byte[] bytes;
    if (family == 0x01) {
      bytes = new byte[4];
    } else if (family == 0x02) {
      bytes = new byte[16];
    } else {
      log.error("Unknown address family for XOR_MAPPED_ADDRESS: {}", family);
      return null;
    }
    in.get(bytes);

    xorBytes(bytes, id);

    try {
      InetAddress address = InetAddress.getByAddress(bytes);
      return new InetSocketAddress(address, port);
    } catch (UnknownHostException e) {
      // This should never happen
      throw new ScionRuntimeException(e);
    }
  }

  private static void xorBytes(byte[] bytes, byte[] bytes2) {
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] ^= bytes2[i];
    }
  }

  private static String processError(int errorCode) {
    switch (errorCode) {
      case 300:
        return "Try Alternate: The client should contact an alternate server for\n"
            + "this request.  This error response MUST only be sent if the\n"
            + "request included a USERNAME attribute and a valid MESSAGE-\n"
            + "INTEGRITY attribute; otherwise, it MUST NOT be sent and error\n"
            + "code 400 (Bad Request) is suggested.  This error response MUST\n"
            + "be protected with the MESSAGE-INTEGRITY attribute, and receivers\n"
            + "MUST validate the MESSAGE-INTEGRITY of this response before\n"
            + "redirecting themselves to an alternate server.\n"
            + "\n"
            + "Note: Failure to generate and validate message integrity\n"
            + "for a 300 response allows an on-path attacker to falsify\na "
            + "300 response thus causing subsequent STUN messages to be\n"
            + "sent to a victim.";
      case 400:
        return "Bad Request: The request was malformed.  The client SHOULD NOT\n"
            + "retry the request without modification from the previous\n"
            + "attempt.  The server may not be able to generate a valid\n"
            + "MESSAGE-INTEGRITY for this error, so the client MUST NOT expect\n"
            + "a valid MESSAGE-INTEGRITY attribute on this response.";
      case 401:
        return "Unauthorized: The request did not contain the correct\n"
            + "credentials to proceed.  The client should retry the request\n"
            + "with proper credentials.";

      case 420:
        return "Unknown Attribute: The server received a STUN packet containing\n"
            + "a comprehension-required attribute that it did not understand.\n"
            + "The server MUST put this unknown attribute in the UNKNOWN-\n"
            + "ATTRIBUTE attribute of its error response.";

      case 438:
        return "Stale Nonce: The NONCE used by the client was no longer valid.\n"
            + "The client should retry, using the NONCE provided in the\n"
            + "response.";
      case 500:
        return "Server Error: The server has suffered a temporary error.  The\n"
            + "client should try again.";
      default:
        return "Unknown error " + errorCode;
    }
  }

  private static void writeAttribute(ByteBuffer out, Type type, Runnable writer) {
    // write header
    out.putShort(ByteUtil.toShort(type.code())); // code
    int posLength = out.position();
    out.putShort((short) 0); // space holder
    int posBegin = out.position();
    // write content
    writer.run();
    // update length
    int posEnd = out.position();
    out.putShort(posLength, ByteUtil.toShort(posEnd - posBegin)); // length
  }

  private static void writeMappedAddress(ByteBuffer out, InetSocketAddress address) {
    byte[] addrBytes = address.getAddress().getAddress();
    out.put((byte) 0); // MUST be ignored
    out.put((byte) (addrBytes.length == 4 ? 0x01 : 0x02)); // family
    out.putShort(ByteUtil.toShort(address.getPort()));
    out.put(addrBytes);
  }

  private static void writeSoftware(ByteBuffer out) {
    byte[] softwareBytes = SOFTWARE_ID.getBytes();
    int softwareLength = (softwareBytes.length + 3) & 0xfffc;
    out.put(softwareBytes);
    for (int i = softwareBytes.length; i < softwareLength; i++) {
      out.put((byte) 0);
    }
  }

  private static void writeXorMappedAddress(ByteBuffer out, byte[] id, InetSocketAddress address) {
    out.put((byte) 0); // MUST be ignored
    byte[] addrBytes = address.getAddress().getAddress();
    out.put((byte) (addrBytes.length == 4 ? 0x01 : 0x02)); // family
    out.putShort(ByteUtil.toShort(address.getPort() ^ 0x2112));
    xorBytes(addrBytes, id);
    out.put(addrBytes);
  }

  enum Type implements HeaderConstants.ParseEnum {
    // https://datatracker.ietf.org/doc/html/rfc3489#section-11.2
    // https://datatracker.ietf.org/doc/html/rfc5389#section-15
    // https://datatracker.ietf.org/doc/html/rfc5389#section-18.2
    // https://www.iana.org/assignments/stun-parameters/stun-parameters.xhtml

    //  Comprehension-required range (0x0000-0x7FFF):
    RESERVED_0(0x0000, "(Reserved)"),
    MAPPED_ADDRESS(0x0001, "MAPPED_ADDRESS"),
    // deprecated from classic STUN: https://datatracker.ietf.org/doc/html/rfc5389#section-19
    WAS_RESPONSE_ADDRESS(0x0002, "(Reserved; was RESPONSE-ADDRESS)"),
    WAS_CHANGE_REQUEST(0x0003, "(Reserved; was CHANGE-ADDRESS)"),
    WAS_SOURCE_ADDRESS(0x0004, "(Reserved; was SOURCE-ADDRESS)"),
    WAS_CHANGED_ADDRESS(0x0005, "(Reserved; was CHANGED-ADDRESS)"),
    USERNAME(0x0006, "USERNAME"),
    WAS_PASSWORD(0x0007, "(Reserved; was PASSWORD)"),
    MESSAGE_INTEGRITY(0x0008, "MESSAGE-INTEGRITY"),
    ERROR_CODE(0x0009, "ERROR-CODE"),
    UNKNOWN_ATTRIBUTES(0x000a, "UNKNOWN-ATTRIBUTES"),
    WAS_REFLECTED_FROM(0x000b, "(Reserved; was REFLECTED-FROM)"),
    REALM(0x0014, "REALM"),
    NONCE(0x0015, "NONCE"),
    XOR_MAPPED_ADDRESS(0x0020, "XOR-MAPPED-ADDRESS"),
    // https://www.iana.org/assignments/stun-parameters/stun-parameters.xhtml
    XXX_RESERVATION_TOKEN(0x0022, "RESERVATION-TOKEN"),

    // Comprehension-optional range (0x8000-0xFFFF)
    // 0x8020 is not properly defined, or is it?
    OLD_XOR_MAPPED_ADDRESS(0x8020, "XOR-MAPPED-ADDRESS"),
    SOFTWARE(0x8022, "SOFTWARE"),
    ALTERNATE_SERVER(0x8023, "ALTERNATE-SERVER"),
    FINGERPRINT(0x8028, "FINGERPRINT"),
    RESPONSE_ORIGIN(0x802B, "RESPONSE-ORIGIN"),
    OTHER_ADDRESS(0x802C, "OTHER-ADDRESS");

    final int id;
    final String text;

    Type(int id, String text) {
      this.id = id;
      this.text = text;
    }

    public static Type parse(int id) {
      return HeaderConstants.ParseEnum.parse(Type.class, id);
    }

    @Override
    public int code() {
      return id;
    }

    public String getText() {
      return text;
    }

    @Override
    public String toString() {
      return "0x" + Integer.toHexString(code()) + ": '" + getText() + '\'';
    }
  }

  public static class TransactionID {
    final int id0;
    final int id1;
    final int id2;

    TransactionID(Random rnd) {
      id0 = rnd.nextInt();
      id1 = rnd.nextInt();
      id2 = rnd.nextInt();
    }

    TransactionID(int id0, int id1, int id2) {
      this.id0 = id0;
      this.id1 = id1;
      this.id2 = id2;
    }

    public static TransactionID from(int id0, int id1, int id2) {
      return new TransactionID(id0, id1, id2);
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TransactionID that = (TransactionID) o;
      return id0 == that.id0 && id1 == that.id1 && id2 == that.id2;
    }

    @Override
    public int hashCode() {
      return Objects.hash(id0, id1, id2);
    }
  }

  private static TransactionID createTxID() {
    synchronized (rnd) {
      return new TransactionID(rnd);
    }
  }

  public static TransactionID writeRequest(ByteBuffer buffer) {
    //    0                   1                   2                   3
    //    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |0 0|     STUN Message Type     |         Message Length        |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                         Magic Cookie                          |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                                                               |
    //    |                     Transaction ID (96 bits)                  |
    //    |                                                               |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    buffer.put(ByteUtil.toByte(0x00));
    buffer.put(ByteUtil.toByte(0x01));
    // length is written later
    buffer.putShort((short) 0);

    // 0x2112A442
    buffer.putInt(MAGIC_COOKIE);

    // Transaction ID
    TransactionID id = createTxID();
    buffer.putInt(id.id0);
    buffer.putInt(id.id1);
    buffer.putInt(id.id2);

    // SOFTWARE attribute
    byte[] softwareBytes = SOFTWARE_ID.getBytes();
    int softwareLength = (softwareBytes.length + 3) & 0xfffc;
    buffer.putShort(ByteUtil.toShort(0x8022));
    buffer.putShort(ByteUtil.toShort(softwareLength));
    buffer.put(softwareBytes);
    for (int i = softwareBytes.length; i < softwareLength; i++) {
      buffer.put((byte) 0);
    }

    // FINGERPRINT
    int fpPos = buffer.position();
    if (ADD_FINGERPRINT) {
      buffer.putShort(ByteUtil.toShort(Type.FINGERPRINT.code()));
      buffer.putShort(ByteUtil.toShort(4));
      buffer.putInt(0); // dummy
    }

    // post processing
    // adjust length, excluding the 20 byte header
    buffer.putShort(2, (short) (buffer.position() - 20));

    // calculate fingerprint
    if (ADD_FINGERPRINT) {
      CRC32 crc32 = new CRC32();
      buffer.flip();
      buffer.position(0);
      buffer.limit(fpPos);
      crc32.update(buffer);
      buffer.limit(fpPos + 8);
      // write fingerprint
      buffer.position(fpPos + 4);
      long fingerprint = crc32.getValue() ^ FINGERPRINT_XOR;
      buffer.putInt(ByteUtil.toInt(fingerprint));
    }

    return id;
  }

  public static void writeResponse(ByteBuffer buffer, TransactionID txId, InetSocketAddress src) {
    //    0                   1                   2                   3
    //    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |0 0|     STUN Message Type     |         Message Length        |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                         Magic Cookie                          |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                                                               |
    //    |                     Transaction ID (96 bits)                  |
    //    |                                                               |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    buffer.put(ByteUtil.toByte(0x01));
    buffer.put(ByteUtil.toByte(0x01));
    // length is written later
    buffer.putShort((short) 0);

    // 0x2112A442
    buffer.putInt(MAGIC_COOKIE);

    // Transaction ID
    buffer.putInt(txId.id0);
    buffer.putInt(txId.id1);
    buffer.putInt(txId.id2);

    // MAPPED_ADDRESS
    writeAttribute(buffer, Type.MAPPED_ADDRESS, () -> writeMappedAddress(buffer, src));

    // XOR_MAPPED_ADDRESS
    byte[] id = new byte[16];
    int pos = buffer.position();
    buffer.position(4);
    buffer.get(id);
    buffer.position(pos);
    writeAttribute(buffer, Type.XOR_MAPPED_ADDRESS, () -> writeXorMappedAddress(buffer, id, src));

    // SOFTWARE attribute
    writeAttribute(buffer, Type.SOFTWARE, () -> writeSoftware(buffer));

    // FINGERPRINT
    int fpPos = buffer.position();
    if (ADD_FINGERPRINT) {
      buffer.putShort(ByteUtil.toShort(Type.FINGERPRINT.code()));
      buffer.putShort(ByteUtil.toShort(4));
      buffer.putInt(0); // dummy
    }

    // post processing
    // adjust length, excluding the 20 byte header
    buffer.putShort(2, (short) (buffer.position() - 20));

    // calculate fingerprint
    if (ADD_FINGERPRINT) {
      CRC32 crc32 = new CRC32();
      buffer.flip();
      buffer.position(0);
      buffer.limit(fpPos);
      crc32.update(buffer);
      buffer.limit(fpPos + 8);
      // write fingerprint
      buffer.position(fpPos + 4);
      long fingerprint = crc32.getValue() ^ FINGERPRINT_XOR;
      buffer.putInt(ByteUtil.toInt(fingerprint));
    }
  }
}
