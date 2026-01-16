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

package org.scion.jpan.demo.inspector;

import java.nio.ByteBuffer;
import org.scion.jpan.Scmp;

public class ScmpHeader {
  // 8 bit
  private int type;
  // 8 bit
  private int code;
  // 48 bit
  private int checksum;
  private int short0;
  private int short1;
  private long long0;
  private long long1;
  private long long2;
  private byte[] echoUserData = new byte[0];
  private byte[] errorPayload = new byte[0];
  private long traceIsdAs;
  private long traceIfID;

  public void read(ByteBuffer data) {
    int i0 = data.getInt();
    type = ByteUtil.readInt(i0, 0, 8);
    code = ByteUtil.readInt(i0, 8, 8);
    checksum = ByteUtil.readInt(i0, 16, 16);

    Scmp.Type st = Scmp.Type.parse(type);
    short0 = ByteUtil.toUnsigned(data.getShort());
    short1 = ByteUtil.toUnsigned(data.getShort());
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
  }

  public void write(ByteBuffer buffer) {
    Scmp.Type typeEnum = getType();
    switch (typeEnum) {
      case INFO_128:
      case INFO_129:
        writeEcho(buffer);
        break;
      case INFO_130:
      case INFO_131:
        writeTraceroute(buffer);
        break;
      case INFO_200:
      case INFO_201:
      case INFO_255:
        writeScmpHeader(buffer);
        break;
      default:
        if (getCode().isError()) {
          writeError(buffer);
        } else {
          throw new UnsupportedOperationException();
        }
    }
  }

  public void writeEcho(ByteBuffer buffer) {
    if (type != 128 && type != 129) {
      throw new IllegalStateException();
    }
    writeScmpHeader(buffer);
    buffer.putShort((short) short0); // unsigned identifier
    buffer.putShort((short) short1); // unsigned sequenceNumber
    buffer.put(echoUserData);
  }

  public void writeTraceroute(ByteBuffer buffer) {
    if (type != 130 && type != 131) {
      throw new IllegalStateException();
    }
    writeScmpHeader(buffer);
    buffer.putShort((short) short0); // unsigned identifier
    buffer.putShort((short) short1); // unsigned sequenceNumber

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
    writeScmpHeader(buffer);
    switch (type) {
      case 1:
        buffer.putInt(0); // unused
        break;
      case 2:
      case 4:
        buffer.putShort((short) 0); // reserved
        buffer.putShort((short) short1); // 2: MTU; 4: pointer
        break;
      case 5:
        buffer.putLong(long0); // ISD/AS
        buffer.putLong(long1); // Interface ID
        break;
      case 6:
        buffer.putLong(long0); // ISD/AS
        buffer.putLong(long1); // Ingress Interface ID
        buffer.putLong(long2); // Egress Interface ID
        break;
      case 100:
      case 101:
      case 127:
        break;
      default:
        throw new UnsupportedOperationException("Invalid: " + type + ":" + code);
    }
    // max: 1232
    int payloadLen = Math.min(buffer.position() + errorPayload.length, 1232) - buffer.position();
    buffer.put(errorPayload, 0, payloadLen);
  }

  private void writeScmpHeader(ByteBuffer buffer) {
    buffer.put(ByteUtil.toByte(type));
    buffer.put(ByteUtil.toByte(code));
    buffer.putShort((short) 0); // checksum
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
    this.type = code.type().id();
  }

  public byte[] getUserData() {
    return echoUserData;
  }

  public void setTraceData(long isdAs, int ifID) {
    this.traceIsdAs = isdAs;
    this.traceIfID = ifID;
  }

  public void setIdentifier(int identifier) {
    this.short0 = identifier;
  }

  public void setErrorPayload(byte[] payload) {
    this.errorPayload = payload;
  }

  public int getLength() {
    if (echoUserData.length != 0 && errorPayload.length != 0) {
      throw new IllegalStateException();
    }
    return Scmp.Type.parse(type).getHeaderLength() + echoUserData.length + errorPayload.length;
  }

  public void setDataLong(long l0, long l1, long l2) {
    this.long0 = l0;
    this.long1 = l1;
    this.long2 = l2;
  }

  public void setDataShort(int s0, int s1) {
    this.short0 = s0;
    this.short1 = s1;
  }
}
