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

import static org.scion.internal.ByteUtil.*;

public class ScionSCMPHeader {

  // 8 bit
  private int type;
  // 8 bit
  private int code;
  // 48 bit
  private int checksum;

  public int read(byte[] data, int offset) {
    int i0 = readInt(data, offset);
    type = readInt(i0, 0, 8);
    code = readInt(i0, 8, 8);
    checksum = readInt(i0, 16, 16);
    // TODO validate checksum
    // TODO read InfoBlock/DataBlock
    return offset + 4;
  }

  //    public void reverse() {
  //        int dummy = srcPort;
  //        srcPort = dstPort;
  //        dstPort = dummy;
  //    }
  //
  //    public int write(byte[] data, int offset, int packetLength) {
  //        int i0 = 0;
  //        int i1 = 0;
  //        i0 = writeInt(i0, 0, 16, srcPort);
  //        i0 = writeInt(i0, 16, 16, dstPort);
  //        i1 = writeInt(i1, 0, 16, packetLength + 8);
  //        i1 = writeInt(i1, 16, 16, checkSum);
  //        offset = writeInt(data, offset, i0);
  //        offset = writeInt(data, offset, i1);
  //        return offset;
  //    }

  @Override
  public String toString() {
    return "ScionEndToEndExtensionHeader{"
        + "type="
        + type
        + ", code="
        + code
        + ", checksum="
        + checksum
        + '}';
  }

  public ScmpType getType() {
    return ScmpType.parse(type);
  }

  public String getCode() {
    return "" + code;
  }

  //    @Override
  //    public String toString() {
  //        return "UdpPseudoHeader{" +
  //                "srcPort=" + srcPort +
  //                ", dstPort=" + dstPort +
  //                ", length=" + packetLength +
  //                ", checkSum=" + checkSum +
  //                '}';
  //    }

  public enum ScmpType implements Constants.ParseEnum {
    // SCMP error messages:

    E1(1,"Destination Unreachable"),
    E2(2, "Packet Too Big"),
    E3(3, "(not assigned)"),
    E4(4,"Parameter Problem"),
    E5(5,"External Interface Down"),
    E6(6,"Internal Connectivity Down"),
    E100(100,"Private Experimentation"),
    E101(101,"Private Experimentation"),
    E127(127, "Reserved for expansion of SCMP error messages"),

    // SCMP informational messages:
    I128(128, "Echo Request"),
    I129(129,"Echo Reply"),
    I130(130,"Traceroute Request"),
    I131(131,"Traceroute Reply"),
    I200(200,"Private Experimentation"),
    I201(201,"Private Experimentation"),
    I255(255,"Reserved for expansion of SCMP informational messages");

    final int code;
    final String text;

    ScmpType(int code, String text) {
      this.code = code;
      this.text = text;
    }

    public static ScmpType parse(int code) {
      return Constants.ParseEnum.parse(ScmpType.class, code);
    }

    @Override
    public int code() {
      return code;
    }

    public String getText() {
      return text;
    }
  }


  public enum ScmpType1Code implements Constants.ParseEnum {
    // SCMP type 1 messages:

    E1(1,"Destination Unreachable"),
    E2(2, "Packet Too Big"),
    E3(3, "(not assigned)"),
    E4(4,"Parameter Problem"),
    E5(5,"External Interface Down"),
    E6(6,"Internal Connectivity Down"),
    E100(100,"Private Experimentation"),
    E101(101,"Private Experimentation"),
    E127(127, "Reserved for expansion of SCMP error messages"),

    // SCMP informational messages:
    I128(128, "Echo Request"),
    I129(129,"Echo Reply"),
    I130(130,"Traceroute Request"),
    I131(131,"Traceroute Reply"),
    I200(200,"Private Experimentation"),
    I201(201,"Private Experimentation"),
    I255(255,"Reserved for expansion of SCMP informational messages");

    final int code;
    final String text;

    ScmpType1Code(int code, String text) {
      this.code = code;
      this.text = text;
    }

    public static ScmpType1Code parse(int code) {
      return Constants.ParseEnum.parse(ScmpType1Code.class, code);
    }

    @Override
    public int code() {
      return code;
    }

    public String getText() {
      return text;
    }
  }

}
