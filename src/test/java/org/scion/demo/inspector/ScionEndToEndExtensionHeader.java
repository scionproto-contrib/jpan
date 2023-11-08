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

import org.scion.internal.Constants;

import static org.scion.demo.util.ByteUtil.*;

public class ScionEndToEndExtensionHeader {

  // 8 bit
  private int nextHdr;
  // 8 bit
  private int extLen;
  private int extLenBytes;
  // 48 bit
  private long options;

  public int read(byte[] data, int offset) {
    long l0 = readLong(data, offset);
    nextHdr = (int) readLong(l0, 0, 8);
    extLen = (int) readLong(l0, 8, 8);
    extLenBytes = (extLen + 1) * 4;
    options = readLong(l0, 16, 48);
    // TODO validate checksum
    return offset + extLenBytes;
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
    return "ScionEndToEndExtensionHeader{" +
            "nextHdr=" + nextHdr +
            ", extLen=" + extLen +
            ", options=" + options +
            '}';
  }

  public Constants.HdrTypes nextHdr() {
    return Constants.HdrTypes.parse(nextHdr);
  }

  public int getExtLenBytes() {
    return extLenBytes;
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

}
