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

import static org.scion.Scmp.EchoMessage;
import static org.scion.Scmp.Message;
import static org.scion.Scmp.TracerouteMessage;
import static org.scion.Scmp.Type;
import static org.scion.Scmp.TypeCode;

import java.nio.ByteBuffer;

public class ScmpParser {

  private ScmpParser() {}

  public static void buildExtensionHeader(ByteBuffer buffer, InternalConstants.HdrTypes nextHdr) {
    int len = 8;
    buffer.put(ByteUtil.toByte(nextHdr.code));
    buffer.put(ByteUtil.toByte(((len + 3) / 4) - 1));
    buffer.putShort((short) 0); // TODO this should be variable length!
    buffer.putInt(0);
  }

  public static void buildScmpPing(
      ByteBuffer buffer, int identifier, int sequenceNumber, byte[] data) {
    buffer.put(ByteUtil.toByte(Type.INFO_128.id()));
    buffer.put(ByteUtil.toByte(0));
    buffer.putShort((short) 0); // TODO checksum
    buffer.putShort((short) identifier); // unsigned
    buffer.putShort((short) sequenceNumber); // unsigned
    buffer.put(data);
  }

  public static void buildScmpPing(
      ByteBuffer buffer, int identifier, int sequenceNumber, ByteBuffer data) {
    buffer.put(ByteUtil.toByte(Type.INFO_128.id()));
    buffer.put(ByteUtil.toByte(0));
    buffer.putShort((short) 0); // TODO checksum
    buffer.putShort((short) identifier); // unsigned
    buffer.putShort((short) sequenceNumber); // unsigned
    buffer.put(data);
  }

  public static void buildScmpTraceroute(ByteBuffer buffer, int identifier, int sequenceNumber) {
    buffer.put(ByteUtil.toByte(Type.INFO_130.id()));
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

  public static Type extractType(ByteBuffer data) {
    // Avoid changing the position!
    return Type.parse(ByteUtil.toUnsigned(data.get(data.position())));
  }

  /**
   * Reads a SCMP message from the packet. Consumes the byte buffer.
   *
   * @param data packet data
   * @param holder SCMP message holder
   * @return ScmpMessage object
   */
  public static void consume(ByteBuffer data, Message holder) {
    int type = ByteUtil.toUnsigned(data.get());
    int code = ByteUtil.toUnsigned(data.get());
    data.getShort(); // checksum
    // TODO validate checksum

    Type st = Type.parse(type);
    TypeCode sc = TypeCode.parse(type, code);
    int short1 = ByteUtil.toUnsigned(data.getShort());
    int short2 = ByteUtil.toUnsigned(data.getShort());
    holder.setMessageArgs(sc, short1, short2);
    switch (st) {
      case INFO_128:
      case INFO_129:
        EchoMessage echo = (EchoMessage) holder;
        if (echo.getData() == null) {
          echo.setData(new byte[data.remaining()]);
        }
        // If there is an array we can simply reuse it. The length of the
        // package has already been validated.
        data.get(echo.getData());
        break;
      case INFO_130:
      case INFO_131:
        long isdAs = data.getLong();
        long ifID = data.getLong();
        TracerouteMessage trace = (TracerouteMessage) holder;
        trace.setTracerouteArgs(isdAs, ifID);
        break;
      default:
        break;
    }
  }
}
