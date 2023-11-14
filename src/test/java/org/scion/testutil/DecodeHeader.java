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

import org.scion.ScionPath;
import org.scion.ScionUtil;
import org.scion.demo.inspector.Constants;
import org.scion.demo.inspector.OverlayHeader;
import org.scion.demo.inspector.PathHeaderScion;
import org.scion.demo.inspector.ScionHeader;

/** This is a tool to decode and print out a ScionHeader. */
public class DecodeHeader {

  public static void main(String[] args) {
    printHeader(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING);
    printHeader(ExamplePacket.PACKET_BYTES_SERVER_E2E_PING);
    printHeader(ExamplePacket.PACKET_BYTES_SERVER_E2E_PONG);
    printHeader(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PONG);

    // client
    byte[] composed = compose("Hello SCION".getBytes());
    System.out.println("------- Composed header: -------");
    printHeader(composed);
    // server
    System.out.println("------- Reply header: -------");
    byte[] reply = composeReply(composed, "Re: Hello SCION".getBytes());
    printHeader(reply);
  }

  public static void printHeader(byte[] data) {
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();
    int offset = scionHeader.read(data, 0);
    offset = pathHeaderScion.read(data, offset);
    offset = overlayHeaderUdp.read(data, offset);
    byte[] payload = new byte[data.length - offset];
    System.arraycopy(data, offset, payload, 0, payload.length);

    // print
    System.out.println(scionHeader);
    System.out.println(pathHeaderScion);
    System.out.println(overlayHeaderUdp);
    System.out.println("Payload: " + new String(payload));
  }

  private static byte[] compose(byte[] payload) {
    // Send packet
    byte[] newData = new byte[10000];
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();

    DatagramPacket userInput = new DatagramPacket(payload, payload.length);
    scionHeader.setSrcHostAddress(new byte[] {127, 0, 0, 1});
    scionHeader.setDstHostAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
    scionHeader.setSrcIA(ScionUtil.parseIA("1-ff00:0:110"));
    scionHeader.setDstIA(ScionUtil.parseIA("1-ff00:0:112"));

    // There may not be a daemon running so we use a hard coded path in this example.
    // byte[] path =
    // ScionService.defaultService().getPath(ScionUtil.parseIA("1-ff00:0:112")).getRawPath();
    byte[] path = ExamplePacket.PATH_RAW_TINY_110_112;

    int writeOffset =
        scionHeader.write(
            newData, 0, userInput.getLength(), pathHeaderScion.length(), Constants.PathTypes.SCION);
    writeOffset = pathHeaderScion.writePath(newData, writeOffset, path);
    writeOffset = overlayHeaderUdp.write(newData, writeOffset, userInput.getLength(), 33333, 44444);

    // payload
    System.arraycopy(userInput.getData(), 0, newData, writeOffset, userInput.getLength());

    return newData;
  }

  public static byte[] composeReply(byte[] data, byte[] userData) {
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();
    int readOffset = scionHeader.read(data, 0);
    readOffset = pathHeaderScion.read(data, readOffset);
    readOffset = overlayHeaderUdp.read(data, readOffset);
    // byte[] payload = new byte[data.length - readOffset];
    // System.arraycopy(data, readOffset, payload, 0, payload.length);

    // reverse path etc
    scionHeader.reverse();
    pathHeaderScion.reverse();
    overlayHeaderUdp.reverse();

    byte[] newData = new byte[data.length];
    int writeOffset =
        scionHeader.write(
            newData, 0, userData.length, pathHeaderScion.length(), Constants.PathTypes.SCION);
    writeOffset = pathHeaderScion.write(newData, writeOffset);
    writeOffset = overlayHeaderUdp.write(newData, writeOffset, userData.length);
    System.arraycopy(userData, 0, newData, writeOffset, userData.length);

    return newData;
  }
}
