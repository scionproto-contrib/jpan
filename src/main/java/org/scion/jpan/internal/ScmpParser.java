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
import org.scion.jpan.Path;
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
   */
  public static Scmp.Message consume(ByteBuffer data, Path path) {
    int type = ByteUtil.toUnsigned(data.get());
    int code = ByteUtil.toUnsigned(data.get());
    data.getShort(); // checksum
    // TODO validate checksum

    Scmp.TypeCode typeCode = Scmp.TypeCode.parse(type, code);
    int short1 = ByteUtil.toUnsigned(data.getShort());
    int short2 = ByteUtil.toUnsigned(data.getShort());
    switch (typeCode) {
      case TYPE_128:
      case TYPE_129:
        Scmp.EchoMessage echo = Scmp.EchoMessage.create(typeCode, short1, short2, path);
        if (echo.getData() == null) {
          echo.setData(new byte[data.remaining()]);
        }
        // If there is an array we can simply reuse it. The length of the
        // package has already been validated.
        data.get(echo.getData());
        return echo;
      case TYPE_130:
      case TYPE_131:
        long isdAs = data.getLong();
        long ifID = data.getLong();
        Scmp.TracerouteMessage trace =
            Scmp.TracerouteMessage.create(typeCode, short1, short2, path);
        trace.setTracerouteArgs(isdAs, ifID);
        return trace;
      default:
        // TODO add payload
        return Scmp.ErrorMessage.createEmpty(typeCode, path);
    }
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
    int idNo = ByteUtil.toUnsigned(data.getShort());
    int seqNo = ByteUtil.toUnsigned(data.getShort());
    switch (typeCode) {
      case TYPE_128:
      case TYPE_129:
        Scmp.EchoMessage echo = Scmp.EchoMessage.create(typeCode, idNo, seqNo, path);
        echo.setData(new byte[data.remaining()]);
        data.get(echo.getData());
        return echo;
      case TYPE_130:
      case TYPE_131:
        long isdAs = data.getLong();
        long ifID = data.getLong();
        Scmp.TracerouteMessage trace = Scmp.TracerouteMessage.create(typeCode, idNo, seqNo, path);
        trace.setTracerouteArgs(isdAs, ifID);
        return trace;
      default:
        if (!typeCode.isError()) {
          // INFO 200, 201, 255, ...
          // TODO more data / payload?
          return new Scmp.Message(typeCode, idNo, seqNo, path);
        }
        Scmp.ErrorMessage error = Scmp.ErrorMessage.createEmpty(typeCode, path);
        byte[] cause = new byte[data.remaining()];
        data.get(cause);
        error.setCause(cause);
        return error;
    }
  }
}
