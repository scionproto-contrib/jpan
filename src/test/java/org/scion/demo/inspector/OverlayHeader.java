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

import static org.scion.demo.inspector.ByteUtil.readInt;
import static org.scion.demo.inspector.ByteUtil.write16;
import static org.scion.demo.inspector.ByteUtil.writeInt;

import java.nio.ByteBuffer;

public class OverlayHeader {

  // 16 bit
  private int srcPort;
  // 16 bit
  private int dstPort;
  // 16 bit
  private int packetLength;
  // 16 bit
  private int checkSum;

  public void read(ByteBuffer data) {
    int i0 = data.getInt();
    int i1 = data.getInt();
    srcPort = readInt(i0, 0, 16);
    dstPort = readInt(i0, 16, 16);
    packetLength = readInt(i1, 0, 16);
    checkSum = readInt(i1, 16, 16);
    // We do not validate the checksum
  }

  public void write(ByteBuffer data, int packetLength) {
    int i0 = 0;
    int i1 = 0;
    i0 = writeInt(i0, 0, 16, srcPort);
    i0 = writeInt(i0, 16, 16, dstPort);
    i1 = writeInt(i1, 0, 16, packetLength + 8);
    i1 = writeInt(i1, 16, 16, checkSum);
    data.putInt(i0);
    data.putInt(i1);
  }

  public void write(ByteBuffer data, int packetLength, int srcPort, int dstPort) {
    int i0 = 0;
    int i1 = 0;
    i0 = writeInt(i0, 0, 16, srcPort);
    i0 = writeInt(i0, 16, 16, dstPort);
    i1 = writeInt(i1, 0, 16, packetLength + 8);
    checkSum = 0; // We do not check it.
    i1 = write16(i1, 16, checkSum);
    data.putInt(i0);
    data.putInt(i1);
  }

  public void reverse() {
    int dummy = srcPort;
    srcPort = dstPort;
    dstPort = dummy;
  }

  @Override
  public String toString() {
    return "UdpOverlayHeader{"
        + "srcPort="
        + srcPort
        + ", dstPort="
        + dstPort
        + ", length="
        + packetLength
        + ", checkSum="
        + checkSum
        + '}';
  }

  public int length() {
    return 8;
  }

  public int getSrcPort() {
    return srcPort;
  }

  public int getDstPort() {
    return dstPort;
  }

  public void set(int packetLength, int srcPort, int dstPort) {
    this.packetLength = packetLength;
    this.srcPort = srcPort;
    this.dstPort = dstPort;
  }
}
