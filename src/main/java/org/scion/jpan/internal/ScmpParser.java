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

package org.scion.jpan.internal;

import java.nio.ByteBuffer;
import org.scion.jpan.ResponsePath;
import org.scion.jpan.Scmp;

public class ScmpParser {

  private ScmpParser() {}

  public static void buildExtensionHeader(ByteBuffer buffer, InternalConstants.HdrTypes nextHdr) {
    int len = 8;
    buffer.put(ByteUtil.toByte(nextHdr.code()));
    buffer.put(ByteUtil.toByte(((len + 3) / 4) - 1));
    buffer.putShort((short) 0); // TODO this should be variable length!
    buffer.putInt(0);
  }

  public static void buildScmpPing(
      ByteBuffer buffer, Scmp.Type type, int identifier, int sequenceNumber, byte[] data) {
    buffer.put(ByteUtil.toByte(type.id()));
    buffer.put(ByteUtil.toByte(0));
    buffer.putShort((short) 0); // TODO checksum
    buffer.putShort((short) identifier); // unsigned
    buffer.putShort((short) sequenceNumber); // unsigned
    buffer.put(data);
  }

  public static void buildScmpTraceroute(
      ByteBuffer buffer, Scmp.Type type, int identifier, int sequenceNumber) {
    buffer.put(ByteUtil.toByte(type.id()));
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

  public static Scmp.Type extractType(ByteBuffer data) {
    // Avoid changing the position!
    int headerLength = ScionHeaderParser.extractHeaderLength(data);
    return Scmp.Type.parse(ByteUtil.toUnsigned(data.get(headerLength)));
  }

  public static Scmp.TypeCode extractTypeCode(ByteBuffer data) {
    // Avoid changing the position!
    int headerLength = ScionHeaderParser.extractHeaderLength(data);
    int type = ByteUtil.toUnsigned(data.get(headerLength));
    int code = ByteUtil.toUnsigned(data.get(headerLength + 1));
    return Scmp.TypeCode.parse(type, code);
  }

  /**
   * Reads a SCMP message from the packet. Consumes the byte buffer.
   *
   * @param data packet data
   * @return SCMP message
   */
  public static Scmp.Message consume(ByteBuffer data, ResponsePath path) {
    int type = ByteUtil.toUnsigned(data.get());
    int code = ByteUtil.toUnsigned(data.get());
    data.getShort(); // checksum
    // TODO validate checksum

    Scmp.TypeCode typeCode = Scmp.TypeCode.parse(type, code);
    Scmp.Type typeEnum = Scmp.Type.parse(type);
    switch (typeEnum) {
      case INFO_128:
      case INFO_129:
        {
          int idNo = ByteUtil.toUnsigned(data.getShort());
          int seqNo = ByteUtil.toUnsigned(data.getShort());
          Scmp.EchoMessage echo = Scmp.EchoMessage.create(typeCode, idNo, seqNo, path);
          echo.setData(new byte[data.remaining()]);
          data.get(echo.getData());
          return echo;
        }
      case INFO_130:
      case INFO_131:
        {
          int idNo = ByteUtil.toUnsigned(data.getShort());
          int seqNo = ByteUtil.toUnsigned(data.getShort());
          long isdAs = data.getLong();
          long ifID = data.getLong();
          Scmp.TracerouteMessage trace = Scmp.TracerouteMessage.create(typeCode, idNo, seqNo, path);
          trace.setTracerouteArgs(isdAs, ifID);
          return trace;
        }
      case INFO_200:
      case INFO_201:
      case INFO_255:
        // INFO 200, 201, 255, ...
        return new Scmp.Message(typeCode, 0, 0, path);
      case ERROR_1:
        return readPayload(Scmp.Error1Message.create(typeCode, path), data);
      case ERROR_2:
        data.getShort(); // reserved
        int mtu = ByteUtil.toUnsigned(data.getShort());
        return readPayload(Scmp.Error2Message.create(typeCode, path, mtu), data);
      case ERROR_4:
        data.getShort(); // reserved
        int pointer = ByteUtil.toUnsigned(data.getShort());
        return readPayload(Scmp.Error4Message.create(typeCode, path, pointer), data);
      case ERROR_5:
        {
          long isdAs = data.getLong();
          long ifId = data.getLong();
          return readPayload(Scmp.Error5Message.create(typeCode, path, isdAs, ifId), data);
        }
      case ERROR_6:
        long isdAs = data.getLong();
        long ingress = data.getLong();
        long egress = data.getLong();
        return readPayload(Scmp.Error6Message.create(typeCode, path, isdAs, ingress, egress), data);
      case ERROR_100:
      case ERROR_101:
      case ERROR_127:
        return readPayload(Scmp.ErrorMessage.create(typeCode, path), data);
      default:
        throw new UnsupportedOperationException("TypeCode not supported: " + typeCode);
    }
  }

  private static Scmp.ErrorMessage readPayload(Scmp.ErrorMessage error, ByteBuffer data) {
    byte[] cause = new byte[data.remaining()];
    data.get(cause);
    error.setCause(cause);
    return error;
  }
}
