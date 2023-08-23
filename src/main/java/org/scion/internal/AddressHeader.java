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

import static org.scion.internal.ByteUtil.*;

import org.scion.Util;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class AddressHeader {ScionCommonHeader common;
    //  8 bit: DstISD
    int dstISD;
    // 48 bit: DstAS
    long dstAS;
    //  8 bit: SrcISD
    int srcISD;
    // 48 bit: SrcAS
    long srcAS;
    //  ? bit: DstHostAddr
    int dstHost0;
    int dstHost1;
    int dstHost2;
    int dstHost3;
    //  ? bit: SrcHostAddr
    int srcHost0;
    int srcHost1;
    int srcHost2;
    int srcHost3;

    int len;

    AddressHeader(ScionCommonHeader common) {
        this.common = common;
    }

    public static AddressHeader read(byte[] data, int headerOffset, ScionCommonHeader commonHeader) {
        //  8 bit: DstISD
        // 48 bit: DstAS
        //  8 bit: SrcISD
        // 48 bit: SrcAS
        //  ? bit: DstHostAddr
        //  ? bit: SrcHostAddr

        int offset = headerOffset;
        AddressHeader header = new AddressHeader(commonHeader);

        long l0 = readLong(data, offset);
        offset += 8;
        long l1 = readLong(data, offset);
        offset += 8;
        header.dstISD = (int) readLong(l0, 0, 16);
        header.dstAS = readLong(l0, 16, 48);
        header.srcISD = (int) readLong(l1, 0, 16);
        header.srcAS = readLong(l1, 16, 48);

        header.dstHost0 = readInt(data, offset);
        offset += 4;
        if (header.common.dl >= 1) {
            header.dstHost1 = readInt(data, offset);
            offset += 4;
        }
        if (header.common.dl >= 2) {
            header.dstHost2 = readInt(data, offset);
            offset += 4;
        }
        if (header.common.dl >= 3) {
            header.dstHost3 = readInt(data, offset);
            offset += 4;
        }

        header.srcHost0 = readInt(data, offset);
        offset += 4;
        if (header.common.sl >= 1) {
            header.srcHost1 = readInt(data, offset);
            offset += 4;
        }
        if (header.common.sl >= 2) {
            header.srcHost2 = readInt(data, offset);
            offset += 4;
        }
        if (header.common.sl >= 3) {
            header.srcHost3 = readInt(data, offset);
            offset += 4;
        }

        header.len = offset - headerOffset;
        return header;
    }

    public InetAddress getSrcHostAddress(byte[] data) {
        // TODO this is awkward, we should not pass in data[] here. (Or we should do it everywhere)
        byte[] bytes = new byte[(common.sl + 1) * 4];
        int offset = 16 + (common.dl + 1) * 4;
        System.arraycopy(data, common.length() + offset, bytes, 0, bytes.length);
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            // This really should not happen
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Address Header: ");
        //            sb.append("  DstISD=" + dstISD);
        //            sb.append("  DstAS =" + dstAS);
        //            //sb.append("\n");
        //            sb.append("  SrcISD=" + srcISD);
        //            sb.append("  SrcAS =" + srcAS);
        //            System.out.println(sb);
        sb.append("  dstIsdAs=").append(Util.toStringIA(dstISD, dstAS));
        sb.append("  srcIsdAs=").append(Util.toStringIA(srcISD, srcAS));
        sb.append("  dstHost=" + common.dt + "/");
        if (common.dl == 0) {
            sb.append(Util.toStringIPv4(dstHost0)); // TODO dt 0=IPv$ or 1=Service
        } else if (common.dl == 3) {
            sb.append(Util.toStringIPv6(common.dl + 1, dstHost0, dstHost1, dstHost2, dstHost3));
        } else {
            sb.append("Format not recognized: " + Util.toStringIPv6(common.dl + 1, dstHost0, dstHost1, dstHost2, dstHost3));
        }
//            switch (common.dl) {
//                case 0: sb.append(Util.toStringIPv4(dstHost0)); break;
//                case 1: sb.append(Util.toStringIPv4(dstHost0)).append(Util.toStringIPv4(dstHost1)); break;
//                case 2: sb.append(Util.toStringIPv4(dstHost0)).append(Util.toStringIPv4(dstHost1)).append(Util.toStringIPv4(dstHost2)); break;
//                case 3: sb.append(Util.toStringIPv4(dstHost0)).append(Util.toStringIPv4(dstHost1)).append(Util.toStringIPv4(dstHost2)).append(Util.toStringIPv4(dstHost3)); break;
//                default:
//                    throw new IllegalArgumentException("DL=" + common.dl);
//            }
        sb.append("  srcHost=" + common.st + "/");
        if (common.sl == 0) {
            sb.append(Util.toStringIPv4(srcHost0)); // TODO dt 0=IPv$ or 1=Service
        } else if (common.sl == 3) {
            sb.append(Util.toStringIPv6(common.sl + 1, srcHost0, srcHost1, srcHost2, srcHost3));
        } else {
            sb.append("Format not recognized: " + Util.toStringIPv6(common.sl + 1, srcHost0, srcHost1, srcHost2, srcHost3));
        }
//            switch (common.sl) {
//                case 0: sb.append(Util.toStringIPv4(srcHost0)); break;
//                case 1: sb.append(Util.toStringIPv4(srcHost0)).append(Util.toStringIPv4(srcHost1)); break;
//                case 2: sb.append(Util.toStringIPv4(srcHost0)).append(Util.toStringIPv4(srcHost1)).append(Util.toStringIPv4(srcHost2)); break;
//                case 3: sb.append(Util.toStringIPv4(srcHost0)).append(Util.toStringIPv4(srcHost1)).append(Util.toStringIPv4(srcHost2)).append(Util.toStringIPv4(srcHost3)); break;
//                default:
//                    throw new IllegalArgumentException("SL=" + common.sl);
//            }
        return sb.toString();
    }

    public int length() {
        return len;
    }
}
