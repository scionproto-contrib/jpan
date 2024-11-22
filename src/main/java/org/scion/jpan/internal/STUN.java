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
import java.util.Random;
import org.scion.jpan.ScionRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class STUN {

  private static final Logger log = LoggerFactory.getLogger(STUN.class);

  private static final SecureRandom rnd = new SecureRandom();

  private static final int MAGIC_COOKIE = 0x42A41221;
  // This must be UTF-8
  private static final String SOFTWARE_ID = "jpan.scion.org v0.4.0";

  public static boolean isStunPacket(ByteBuffer buf, TransactionID id) {
    return buf.getInt(4) == MAGIC_COOKIE
        && buf.getInt(8) == id.id[0]
        && buf.getInt(12) == id.id[1]
        && buf.getInt(16) == id.id[2];
  }

  public static InetSocketAddress parseResponse(ByteBuffer in) {
    byte b0 = in.get();
    byte b1 = in.get();
    int dataLength = in.getShort();
    long id0 = in.getLong();
    long id1 = in.getLong();
    if (in.remaining() != dataLength) {
      throw new IllegalStateException(
          "STUN validation error: length = " + in.remaining() + " " + dataLength);
    }
    System.out.println("More bytes: " + dataLength);

    InetSocketAddress mappedAddress = null;

    // Attributes
    while (in.remaining() > 0) {
      int typeInt = ByteUtil.toUnsigned(in.getShort());
      int len = in.getShort();
      Type typeEnum = Type.parse(typeInt);
      System.out.println("type: " + typeEnum);
      switch (typeEnum) {
        case MAPPED_ADDRESS:
          mappedAddress = readMAPPED_ADDRESS(in);
          log.warn("MAPPED_ADDRESS: {}", mappedAddress);
          break;
        case WAS_SOURCE_ADDRESS:
          log.warn("SOURCE_ADDRESS: {}", readMAPPED_ADDRESS(in));
          break;
        case WAS_CHANGED_ADDRESS:
          log.warn("CHANGED_ADDRESS: {}", readMAPPED_ADDRESS(in));
          break;
        case XOR_MAPPED_ADDRESS:
        case OLD_XOR_MAPPED_ADDRESS:
          System.out.println("        XOR_MAPPED_ADDRESS: " + readXOR_MAPPED_ADDRESS(in));
          // log.debug("XOR_MAPPED_ADDRESS: {}", readMAPPED_ADDRESS(in));
          break;
        case SOFTWARE:
          System.out.println("        SOFTWARE: " + readSOFTWARE(in, len));
          // log.debug("SOFTWARE: {}", readSOFTWARE(in, len));
          break;
        case XXX_RESERVATION_TOKEN:
          in.position(in.position() + len);
          // ignore
          break;
        default:
          byte[] data = new byte[len];
          in.get(data);
          throw new IllegalStateException(typeEnum.getText());
      }
    }

    return mappedAddress;
  }

  private static InetSocketAddress readMAPPED_ADDRESS(ByteBuffer in) {
    byte b0 = in.get();
    byte family = in.get();
    int port = ByteUtil.toUnsigned(in.getShort());
    byte[] bytes;
    if (family == 0x01) {
      bytes = new byte[4];
    } else if (family == 0x02) {
      bytes = new byte[16];
    } else {
      throw new UnsupportedOperationException("Unsupported family: " + family); // TODO
    }
    in.get(bytes);
    try {
      InetAddress address = InetAddress.getByAddress(bytes);
      return new InetSocketAddress(address, port);
    } catch (UnknownHostException e) {
      throw new ScionRuntimeException(e); // TODO ???
    }
  }

  private static String readSOFTWARE(ByteBuffer in, int length) {
    length = (length + 3) & 0xfffc;
    byte[] bytes = new byte[length];
    in.get(bytes);
    return new String(bytes);
  }

  private static InetSocketAddress readXOR_MAPPED_ADDRESS(ByteBuffer in) {
    byte b0 = in.get();
    byte family = in.get();
    int port = ByteUtil.toUnsigned(in.getShort());
    byte[] bytes;
    if (family == 0x01) {
      bytes = new byte[4];
    } else if (family == 0x02) {
      bytes = new byte[16];
    } else {
      throw new UnsupportedOperationException("Unsupported fammily: " + family); // TODO
    }
    in.get(bytes);
    try {
      InetAddress address = InetAddress.getByAddress(bytes);
      return new InetSocketAddress(address, port);
    } catch (UnknownHostException e) {
      throw new ScionRuntimeException(e); // TODO ???
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
        // TODO handle gracefully
        throw new UnsupportedOperationException("Unknown error: " + errorCode);
    }
  }

  enum Type implements InternalConstants.ParseEnum {
    // https://datatracker.ietf.org/doc/html/rfc3489#section-11.2
    // https://datatracker.ietf.org/doc/html/rfc5389#section-15
    // https://datatracker.ietf.org/doc/html/rfc5389#section-18.2

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
    FINGERPRINT(0x8028, "FINGERPRINT");

    final int id;
    final String text;

    Type(int id, String text) {
      this.id = id;
      this.text = text;
    }

    public static Type parse(int id) {
      return InternalConstants.ParseEnum.parse(Type.class, id);
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
      return id + ":'" + text + '\'';
    }
  }

  public static class TransactionID {
    int[] id = new int[3];

    TransactionID(Random rnd) {
      for (int i = 0; i < id.length; i++) {
        id[i] = rnd.nextInt();
      }
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
    buffer.putInt(0x42A41221);

    // Transaction ID
    TransactionID id = createTxID();
    for (int i = 0; i < id.id.length; i++) {
      buffer.putInt(id.id[i]);
    }

    // SOFTWARE attribute
    byte[] softwareBytes = SOFTWARE_ID.getBytes();
    int softwareLength = (softwareBytes.length + 3) & 0xfffc;
    buffer.putShort(ByteUtil.toShort(0x8022));
    buffer.putShort(ByteUtil.toShort(softwareLength));
    buffer.put(softwareBytes);
    for (int i = softwareBytes.length; i < softwareLength; i++) {
      buffer.put((byte) 0);
    }

    // adjust length
    buffer.putShort(2, (short) (buffer.position() - 20));
    return id;
  }
}
