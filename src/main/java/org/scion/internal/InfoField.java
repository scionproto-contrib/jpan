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

import java.nio.ByteBuffer;

import static org.scion.internal.ByteUtil.*;

public class InfoField {

    private boolean r0;
    private boolean r1;
    private boolean r2;
    private boolean r3;
    private boolean r4;
    private boolean r5;
    private boolean r6;
    private boolean p;
    private boolean c;
    // 8 bits
    private int reserved;
    // 16 bits : segID
    private int segID;
    // 32 bits : timestamp (unsigned int)
    // This is an unsigned 'int' stored in a signed integer.
    private int timestampRaw;

    InfoField() {

    }

    public void read(ByteBuffer data) {
        int i0 = data.getInt();
        int i1 = data.getInt();
        p = readBoolean(i0, 6);
        c = readBoolean(i0, 7);
        reserved = readInt(i0, 8, 8);
        segID = readInt(i0, 16, 16);
        timestampRaw = i1;
    }

    public void write(ByteBuffer data) {
        int i0 = 0;
        i0 = writeInt(i0, 0, 6, 0);
        i0 = writeBool(i0, 6, p);
        i0 = writeBool(i0, 7, c);
        i0 = writeInt(i0, 8, 8, 0); // RSV
        i0 = writeInt(i0, 16, 16, segID);
        data.putInt(i0);
        data.putInt(timestampRaw);
    }

    @Override
    public String toString() {
        return "InfoField{" +
                "r0=" + r0 +
                ", r1=" + r1 +
                ", r2=" + r2 +
                ", r3=" + r3 +
                ", r4=" + r4 +
                ", r5=" + r5 +
                ", r6=" + r6 +
                ", P=" + p +
                ", C=" + c +
                ", reserved=" + reserved +
                ", segID=" + segID +
                ", timestamp=" + Integer.toUnsignedString(timestampRaw) +
                '}';
    }
}
