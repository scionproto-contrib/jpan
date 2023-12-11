// Copyright 2023 ETH Zurich
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

package org.scion.internal;

import java.nio.ByteBuffer;
import org.scion.Path;

public class Scmp {

  public static void buildExtensionHeader(ByteBuffer buffer, Constants.HdrTypes nextHdr) {
    int len = 8;
    buffer.put(ByteUtil.toByte(nextHdr.code));
    buffer.put(ByteUtil.toByte(((len + 3) / 4) - 1));
    buffer.putShort((short) 0); // TODO this should be variable length!
    buffer.putInt(0);
  }

  public static void buildScmpPing(
      ByteBuffer buffer, int identifier, int sequenceNumber, ByteBuffer data) {
    buffer.put(ByteUtil.toByte(ScmpType.INFO_128.code));
    buffer.put(ByteUtil.toByte(0));
    buffer.putShort((short) 0); // TODO checksum
    buffer.putShort((short) identifier); // unsigned
    buffer.putShort((short) sequenceNumber); // unsigned
    buffer.put(data);
  }

  public static void buildScmpTraceroute(ByteBuffer buffer, int identifier, int sequenceNumber) {
    buffer.put(ByteUtil.toByte(ScmpType.INFO_130.code));
    buffer.put(ByteUtil.toByte(0));
    buffer.putShort((short) 0); // TODO checksum
    buffer.putShort((short) identifier); // unsigned
    buffer.putShort((short) sequenceNumber); // unsigned

    // add 16 byte placeholder
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // |              ISD              |                               |
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+         AS                    +
    // |                                                               |
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // |                                                               |
    // +                          Interface ID                         |
    // |                                                               |
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    buffer.putLong(0);
    buffer.putLong(0);
  }

  /**
   * Reads a SCMP message from the packet. Consumes the byte buffer.
   *
   * @param data packet data
   * @param path receive path
   * @return ScmpMessage object
   */
  public static ScmpMessage consume(ByteBuffer data, Path path) {
    int type = data.get();
    int code = data.get();
    data.getShort(); // checksum
    // TODO validate checksum

    ScmpType st = ScmpType.parse(type);
    ScmpCode sc = ScmpCode.parse(type, code);
    int short1 = ByteUtil.toUnsigned(data.getShort());
    int short2 = ByteUtil.toUnsigned(data.getShort());
    switch (st) {
      case INFO_128:
      case INFO_129:
        // TODO read data section
        byte[] scmpData = new byte[0];
        return new ScmpEcho(sc, short1, short2, path, scmpData);
      case INFO_130:
      case INFO_131:
        return new ScmpTraceroute(sc, short1, short2, path);
      default:
        return new ScmpMessage(sc, short1, short2, path);
    }
  }

  interface ParseEnum {

    int code();

    static <E extends ParseEnum> E parse(Class<E> e, int code) {
      E[] values = e.getEnumConstants();
      for (int i = 0; i < values.length; i++) {
        E pe = values[i];
        if (pe.code() == code) {
          return pe;
        }
      }
      throw new IllegalArgumentException("Unknown code: " + code);
    }
  }

  public enum ScmpType implements ParseEnum {
    // SCMP error messages:
    ERROR_1(1, "Destination Unreachable"),
    ERROR_2(2, "Packet Too Big"),
    ERROR_3(3, "(not assigned)"),
    ERROR_4(4, "Parameter Problem"),
    ERROR_5(5, "External Interface Down"),
    ERROR_6(6, "Internal Connectivity Down"),
    ERROR_100(100, "Private Experimentation"),
    ERROR_101(101, "Private Experimentation"),
    ERROR_127(127, "Reserved for expansion of SCMP error messages"),

    // SCMP informational messages:
    INFO_128(128, "Echo Request"),
    INFO_129(129, "Echo Reply"),
    INFO_130(130, "Traceroute Request"),
    INFO_131(131, "Traceroute Reply"),
    INFO_200(200, "Private Experimentation"),
    INFO_201(201, "Private Experimentation"),
    INFO_255(255, "Reserved for expansion of SCMP informational messages");

    final int code;
    final String text;

    ScmpType(int code, String text) {
      this.code = code;
      this.text = text;
    }

    public static ScmpType parse(int code) {
      return ParseEnum.parse(ScmpType.class, code);
    }

    @Override
    public int code() {
      return code;
    }

    public String getText() {
      return text;
    }

    @Override
    public String toString() {
      return code + ":'" + text + '\'';
    }
  }

  public enum ScmpCode implements ParseEnum {
    // SCMP type 1 messages:
    TYPE_1_CODE_0(1, 0, "No route to destination"),
    TYPE_1_CODE_1(1, 1, "Communication administratively denied"),
    TYPE_1_CODE_2(1, 2, "Beyond scope of source address"),
    TYPE_1_CODE_3(1, 3, "Address unreachable"),
    TYPE_1_CODE_4(1, 4, "Port unreachable"),
    TYPE_1_CODE_5(1, 5, "Source address failed ingress/egress policy"),
    TYPE_1_CODE_6(1, 6, "Reject route to destination"),

    TYPE_2(2, 0, ""),
    TYPE_3(3, 0, ""),

    // Type 4 codes
    TYPE_4_CODE_0(4, 0, "Erroneous header field"),
    TYPE_4_CODE_1(4, 1, "Unknown NextHdr type"),
    TYPE_4_CODE_2(4, 2, "(unassigned)"),
    TYPE_4_CODE_16(4, 16, "Invalid common header"),
    TYPE_4_CODE_17(4, 17, "Unknown SCION version"),
    TYPE_4_CODE_18(4, 18, "FlowID required"),
    TYPE_4_CODE_19(4, 19, "Invalid packet size"),
    TYPE_4_CODE_20(4, 20, "Unknown path type"),
    TYPE_4_CODE_21(4, 21, "Unknown address format"),
    TYPE_4_CODE_32(4, 32, "Invalid address header"),
    TYPE_4_CODE_33(4, 33, "Invalid source address"),
    TYPE_4_CODE_34(4, 34, "Invalid destination address"),
    TYPE_4_CODE_35(4, 35, "Non-local delivery"),
    TYPE_4_CODE_48(4, 48, "Invalid path"),
    TYPE_4_CODE_49(4, 49, "Unknown hop field cons ingress interface"),
    TYPE_4_CODE_50(4, 50, "Unknown hop field cons egress interface"),
    TYPE_4_CODE_51(4, 51, "Invalid hop field MAC"),
    TYPE_4_CODE_52(4, 52, "Path expired"),
    TYPE_4_CODE_53(4, 53, "Invalid segment change"),
    TYPE_4_CODE_64(4, 64, "Invalid extension header"),
    TYPE_4_CODE_65(4, 65, "Unknown hop-by-hop option"),
    TYPE_4_CODE_66(4, 66, "Unknown end-to-end option"),

    TYPE_5(5, 0, ""),
    TYPE_6(6, 0, ""),
    TYPE_128(128, 0, ""),
    TYPE_129(129, 0, ""),
    TYPE_130(130, 0, ""),
    TYPE_131(131, 0, "");

    final int type;
    final int code;
    final String text;

    ScmpCode(int type, int code, String text) {
      this.type = type;
      this.code = code;
      this.text = text;
    }

    public static ScmpCode parse(int type, int code) {
      ScmpCode[] values = ScmpCode.class.getEnumConstants();
      for (int i = 0; i < values.length; i++) {
        ScmpCode pe = values[i];
        if (pe.code() == code && pe.type == type) {
          return pe;
        }
      }
      throw new IllegalArgumentException("Unknown type/code: " + type + "/" + code);
    }

    @Override
    public int code() {
      return code;
    }

    public String getText() {
      return text;
    }

    @Override
    public String toString() {
      return type + ":" + code + ":'" + text + '\'';
    }
  }

  public static class ScmpMessage {
    final ScmpCode typeCode;
    final int identifier;
    final int sequenceNumber;
    final Path path;

    ScmpMessage(ScmpCode typeCode, int identifier, int sequenceNumber, Path path) {
      this.typeCode = typeCode;
      this.identifier = identifier;
      this.sequenceNumber = sequenceNumber;
      this.path = path;
    }

    public int getIdentifier() {
      return identifier;
    }

    public int getSequenceNumber() {
      return sequenceNumber;
    }

    public ScmpCode getType() {
      return typeCode;
    }

    public Path getPath() {
      return path;
    }
  }

  public static class ScmpEcho extends ScmpMessage {
    byte[] data;

    ScmpEcho(ScmpCode typeCode, int identifier, int sequenceNumber, Path path, byte[] data) {
      super(typeCode, identifier, sequenceNumber, path);
      this.data = data;
    }

    public byte[] getData() {
      return data;
    }
  }

  public static class ScmpTraceroute extends ScmpMessage {
    ScmpTraceroute(ScmpCode typeCode, int identifier, int sequenceNumber, Path path) {
      super(typeCode, identifier, sequenceNumber, path);
    }
  }
}
