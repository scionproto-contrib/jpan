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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class WireGuardPacket {
  static final int TYPE_HANDSHAKE_INIT = 1;
  static final int TYPE_HANDSHAKE_RESPONSE = 2;
  static final int TYPE_COOKIE_REPLY = 3;
  static final int TYPE_DATA = 4;

  private WireGuardPacket() {}

  static int packetType(byte[] packet) {
    if (packet.length < 4) {
      return -1;
    }
    return ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  static byte[] buildHandshakeInit(int senderIndex, byte[] noisePayload108, byte[] mac1, byte[] mac2) {
    ByteBuffer out = ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(TYPE_HANDSHAKE_INIT);
    out.putInt(senderIndex);
    out.put(noisePayload108);
    out.put(mac1);
    out.put(mac2);
    return out.array();
  }

  static HandshakeResponse parseHandshakeResponse(byte[] packet) {
    if (packet.length < 112) {
      return null;
    }
    ByteBuffer in = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    int type = in.getInt();
    if (type != TYPE_HANDSHAKE_RESPONSE) {
      return null;
    }
    int senderIndex = in.getInt();
    int receiverIndex = in.getInt();
    byte[] noisePayload68 = new byte[68];
    in.get(noisePayload68);
    return new HandshakeResponse(senderIndex, receiverIndex, noisePayload68);
  }

  static byte[] buildDataPacket(int receiverIndex, long counter, byte[] ciphertext) {
    ByteBuffer out = ByteBuffer.allocate(16 + ciphertext.length).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(TYPE_DATA);
    out.putInt(receiverIndex);
    out.putLong(counter);
    out.put(ciphertext);
    return out.array();
  }

  static DataPacket parseDataPacket(byte[] packet) {
    if (packet.length < 16) {
      return null;
    }
    ByteBuffer in = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    int type = in.getInt();
    if (type != TYPE_DATA) {
      return null;
    }
    int receiverIndex = in.getInt();
    long counter = in.getLong();
    byte[] ciphertext = new byte[packet.length - 16];
    in.get(ciphertext);
    return new DataPacket(receiverIndex, counter, ciphertext);
  }

  static final class HandshakeResponse {
    final int senderIndex;
    final int receiverIndex;
    final byte[] noisePayload68;

    HandshakeResponse(int senderIndex, int receiverIndex, byte[] noisePayload68) {
      this.senderIndex = senderIndex;
      this.receiverIndex = receiverIndex;
      this.noisePayload68 = noisePayload68;
    }
  }

  static final class DataPacket {
    final int receiverIndex;
    final long counter;
    final byte[] ciphertext;

    DataPacket(int receiverIndex, long counter, byte[] ciphertext) {
      this.receiverIndex = receiverIndex;
      this.counter = counter;
      this.ciphertext = ciphertext;
    }
  }
}
