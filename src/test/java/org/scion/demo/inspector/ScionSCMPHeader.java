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
import org.scion.internal.Scmp;

public class ScionSCMPHeader {
  // 8 bit
  private int type;
  // 8 bit
  private int code;
  // 48 bit
  private int checksum;

  public void read(ByteBuffer data) {
    int i0 = data.getInt();
    type = readInt(i0, 0, 8);
    code = readInt(i0, 8, 8);
    checksum = readInt(i0, 16, 16);
    // TODO validate checksum
    // TODO read InfoBlock/DataBlock
    System.out.println("Found SCMP: " + getType() + " -> " + getCode());
    System.out.println("To read:" + data.remaining());
  }

  @Override
  public String toString() {
    return "ScionSCMPHeader{" + "type=" + type + ", code=" + code + ", checksum=" + checksum + '}';
  }

  public Scmp.ScmpType getType() {
    return Scmp.ScmpType.parse(type);
  }

  public Scmp.ScmpCode getCode() {
    return Scmp.ScmpCode.parse(type, code);
  }
}
