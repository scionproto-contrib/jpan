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

package org.scion.demo.inspector;

import static org.scion.demo.inspector.ByteUtil.*;

import java.nio.ByteBuffer;
import org.scion.Scmp;

public class ScmpHeader {
  // 8 bit
  private int type;
  // 8 bit
  private int code;
  // 48 bit
  private int checksum;
  private int short1;
  private int short2;
  private byte[] echoUserData;
  private long traceIsdAs;
  private long traceIfID;

  public void read(ByteBuffer data) {
    int i0 = data.getInt();
    type = readInt(i0, 0, 8);
    code = readInt(i0, 8, 8);
    checksum = readInt(i0, 16, 16);

    Scmp.Type st = Scmp.Type.parse(type);
    short1 = org.scion.internal.ByteUtil.toUnsigned(data.getShort());
    short2 = org.scion.internal.ByteUtil.toUnsigned(data.getShort());
    switch (st) {
      case INFO_128:
      case INFO_129:
        echoUserData = new byte[data.remaining()];
        data.get(echoUserData);
        break;
      case INFO_130:
      case INFO_131:
        traceIsdAs = data.getLong();
        traceIfID = data.getLong();
        break;
      default:
        // SCMP error
    }

    // System.out.println("Found SCMP: " + getType() + " -> " + getCode());
    // System.out.println("To read:" + data.remaining());
  }

  public void writeEcho(ByteBuffer buffer) {
    if (type != 128 && type != 129) {
      throw new IllegalStateException();
    }
    buffer.put(org.scion.internal.ByteUtil.toByte(type));
    buffer.put(org.scion.internal.ByteUtil.toByte(code));
    buffer.putShort((short) 0); // TODO checksum
    buffer.putShort((short) short1); // unsigned identifier
    buffer.putShort((short) short2); // unsigned sequenceNumber
    buffer.put(echoUserData);
  }

  public void writeTraceroute(ByteBuffer buffer) {
    if (type != 130 && type != 131) {
      throw new IllegalStateException();
    }
    buffer.put(org.scion.internal.ByteUtil.toByte(type));
    buffer.put(org.scion.internal.ByteUtil.toByte(code));
    buffer.putShort((short) 0); // TODO checksum
    buffer.putShort((short) short1); // unsigned identifier
    buffer.putShort((short) short2); // unsigned sequenceNumber

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
    buffer.putLong(traceIsdAs);
    buffer.putLong(traceIfID);
  }

  public void writeError(ByteBuffer buffer) {
    buffer.put(org.scion.internal.ByteUtil.toByte(type));
    buffer.put(org.scion.internal.ByteUtil.toByte(code));
    buffer.putShort((short) 0); // TODO checksum
    buffer.putShort((short) short1); // unsigned identifier
    buffer.putShort((short) short2); // unsigned sequenceNumber
  }

  @Override
  public String toString() {
    return "ScionSCMPHeader{" + "type=" + type + ", code=" + code + ", checksum=" + checksum + '}';
  }

  public Scmp.Type getType() {
    return Scmp.Type.parse(type);
  }

  public Scmp.TypeCode getCode() {
    return Scmp.TypeCode.parse(type, code);
  }

  public void setCode(Scmp.TypeCode code) {
    this.code = code.code();
    this.type = code.type();
  }

  public byte[] getUserData() {
    return echoUserData;
  }

  public void setTraceData(long isdAs, int ifID) {
    this.traceIsdAs = isdAs;
    this.traceIfID = ifID;
  }
}
