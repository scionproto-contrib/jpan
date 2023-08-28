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

import org.junit.jupiter.api.Test;
import org.scion.ScionDatagramSocket;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class HeaderParserTest {

    private static final byte[] packetBytes = {
            0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
            0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 2,
            1, 0, 32, 0, 1, 0, -103, -90, 100, -20, 100, -13, 0, 63, 0, 0,
            0, 2, 62, 57, -82, 1, -16, 51, 0, 63, 0, 1, 0, 0, -104, 77,
            -24, 2, -64, -11, 0, 100, 31, -112, 0, 19, -15, -27, 72, 101, 108, 108,
            111, 32, 115, 99, 105, 111, 110,
    };


    @Test
    public void testParse() throws IOException {
        CommonHeader commonHeader = new CommonHeader();
        AddressHeader addressHeader = new AddressHeader(commonHeader);
        PathHeaderScion pathHeaderScion = new PathHeaderScion();
        byte[] data = packetBytes;

        int offset = commonHeader.read(data, 0);
        //System.out.println("Common header: " + commonHeader);
        offset = addressHeader.read(data, offset);

        //System.out.println("Address header: " + addressHeader);
        assertEquals(1, commonHeader.pathType());
        offset = pathHeaderScion.read(data, offset);

        // Pseudo header
        PseudoHeader udpHeader = PseudoHeader.read(data, offset);
        System.out.println(udpHeader);
        offset += udpHeader.length();


//        byte[] newData = new byte[data.length];
//        int writeOffset = CommonHeader.write(data, input, localAddress);
//        writeOffset = AddressHeader.write(data, writeOffset, commonHeader, addressHeader);
//        writeOffset = pathHeaderScion.write(data, writeOffset);
//
//
//        assertArrayEquals(data, newData);
//
//
//        // TODO
//        pathHeaderScion.reverse();
//        pathHeaderScion.reverse();



    }

}
