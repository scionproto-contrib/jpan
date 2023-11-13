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

package org.scion.testutil;

import java.net.DatagramPacket;
import org.scion.demo.inspector.Constants;
import org.scion.demo.inspector.OverlayHeader;
import org.scion.demo.inspector.PathHeaderScion;
import org.scion.demo.inspector.ScionHeader;

/** This is a tool to decode and print out a ScionHeader. */
public class DecodeHeader {

  private static final byte[] example = ExamplePacket.PACKET_BYTES_SERVER_E2E;

  public static void main(String[] args) {
    testParse();
  }

  public static void testParse() {
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();
    byte[] data = example;

    int offset = scionHeader.read(data, 0);
    // System.out.println("Common header: " + commonHeader);

    // System.out.println("Address header: " + addressHeader);
    offset = pathHeaderScion.read(data, offset);

    // Pseudo header
    offset = overlayHeaderUdp.read(data, offset);
    // System.out.println(overlayHeaderUdp);

    byte[] payload = new byte[data.length - offset];
    System.arraycopy(data, offset, payload, 0, payload.length);

    // Send packet
    byte[] newData = new byte[data.length];

    DatagramPacket userInput = new DatagramPacket(payload, payload.length);
    scionHeader.setSrcHostAddress(new byte[] {127, 0, 0, 2});
    int writeOffset =
        scionHeader.write(
            newData, 0, userInput.getLength(), pathHeaderScion.length(), Constants.PathTypes.SCION);
    writeOffset = pathHeaderScion.write(newData, writeOffset);
    writeOffset = overlayHeaderUdp.write(newData, writeOffset, userInput.getLength());

    // payload
    System.arraycopy(userInput.getData(), 0, newData, writeOffset, userInput.getLength());

    // print
    System.out.println(scionHeader);
    System.out.println(pathHeaderScion);
    System.out.println(overlayHeaderUdp);

    System.out.println("Payload: " + new String(payload));
  }
}
