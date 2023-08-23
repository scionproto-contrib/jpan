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
    // 32 bits : timestamp
    private int timestamp;

    private InfoField(byte[] data, int offset) {
        int i0 = ByteUtil.readInt(data, offset);
        int i1 = ByteUtil.readInt(data, offset + 4);
        p = ByteUtil.readBoolean(i0, 6);
        c = ByteUtil.readBoolean(i0, 7);
        reserved = ByteUtil.readInt(i0, 8, 8);
        segID = ByteUtil.readInt(i0, 16, 16);
        timestamp = i1;
    }

    public static InfoField read(byte[] data, int offset) {
        return new InfoField(data, offset);
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
                ", timestamp=" + Integer.toUnsignedString(timestamp) +
                '}';
    }
}