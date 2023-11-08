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
import org.scion.proto.daemon.Daemon;

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
    private long timestamp; // TODO timestamp raw?

    InfoField() {

    }

//    private InfoField(byte[] data, int offset) {
//        int i0 = ByteUtil.readInt(data, offset);
//        int i1 = ByteUtil.readInt(data, offset + 4);
//        p = ByteUtil.readBoolean(i0, 6);
//        c = ByteUtil.readBoolean(i0, 7);
//        reserved = ByteUtil.readInt(i0, 8, 8);
//        segID = ByteUtil.readInt(i0, 16, 16);
//        timestamp = i1;
//    }

    public void read(byte[] data, int offset) {
        int i0 = readInt(data, offset);
        int i1 = readInt(data, offset + 4);
        p = readBoolean(i0, 6);
        c = readBoolean(i0, 7);
        reserved = readInt(i0, 8, 8);
        segID = readInt(i0, 16, 16);
        timestamp = Integer.toUnsignedLong(i1);  // TODO test this, does it work correctly with signed/unsigned?
    }

    public void read(ByteBuffer data) {
        int i0 = data.getInt();
        int i1 = data.getInt();
        p = readBoolean(i0, 6);
        c = readBoolean(i0, 7);
        reserved = readInt(i0, 8, 8);
        segID = readInt(i0, 16, 16);
        timestamp = Integer.toUnsignedLong(i1);  // TODO test this, does it work correctly with signed/unsigned?
    }

    public int write(byte[] data, int offsetStart) {
        int offset = offsetStart;
        int i0 = 0;

        i0 = writeInt(i0, 0, 6, 0);
        i0 = writeBool(i0, 6, p);
        i0 = writeBool(i0, 7, c);
        i0 = writeInt(i0, 8, 8, 0); // RSV
        i0 = writeInt(i0, 16, 16, segID);
        offset = writeInt(data, offset, i0);
        offset = writeUnsignedInt(data, offset, timestamp);
        return offset;
    }

    public void write(ByteBuffer data) {
        int i0 = 0;
        i0 = writeInt(i0, 0, 6, 0);
        i0 = writeBool(i0, 6, p);
        i0 = writeBool(i0, 7, c);
        i0 = writeInt(i0, 8, 8, 0); // RSV
        i0 = writeInt(i0, 16, 16, segID);
        data.putInt(i0);
        data.putLong(timestamp);
    }

    public void reverse() {
        c = !c;
    }

    public int length() {
        return 8;
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
                ", timestamp=" + Long.toUnsignedString(timestamp) +
                '}';
    }

    public void reset() {
        r0 = false;
        r1 = false;
        r2 = false;
        r3 = false;
        r4 = false;
        r5 = false;
        r6 = false;
        p = false;
        c = false;
        reserved = 0;
        segID = 0;
        timestamp = 0;
    }

    public void set(Daemon.Path path) {
        p = false; // TODO
        c = true; // TODO
        this.timestamp = path.getExpiration().getSeconds();
        this.segID = 12345; // TODO !!!!!
    }

    boolean hasConstructionDirection() {
        return c;
    }
}
