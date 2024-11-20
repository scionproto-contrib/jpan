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

import chat.dim.stun.Client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Random;

public class STUN {

  private static final Random rnd = new Random();

  static class TransactionID {
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

  public static TransactionID writeRequestLib(ByteBuffer buffer, InetSocketAddress sourceAddress) {
    TransactionID id = createTxID();
    Client client = new Client(sourceAddress) {
      @Override
      public byte[] receive() {
        return new byte[0];
      }

      @Override
      public int send(byte[] bytes, SocketAddress socketAddress, SocketAddress socketAddress1) {
        return 0;
      }
    };
    return id;
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
    int length = 20;
    buffer.putShort((short) length);

    // 0x2112A442
    buffer.putInt(0x42A41221);

    // Transaction ID
    TransactionID id = createTxID();
    for (int i = 0; i < id.id.length; i++) {
      buffer.putInt(id.id[i]);
    }

    return id;
  }
}
