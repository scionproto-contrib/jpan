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
import java.nio.ByteBuffer;

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
    ByteBuffer composed = compose("Hello SCION".getBytes());
    System.out.println("------- Composed header: -------");
    printHeader(composed);
    // server
    System.out.println("------- Reply header: -------");
    composed.flip();
    ByteBuffer reply = composeReply(composed, "Re: Hello SCION".getBytes());
    printHeader(reply);
  }

  public static void printHeader(byte[] data) {
    printHeader(ByteBuffer.wrap(data));
  }

  public static void printHeader(ByteBuffer data) {
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();
    scionHeader.read(data);
    pathHeaderScion.read(data);
    overlayHeaderUdp.read(data);
    byte[] payload = new byte[data.remaining()];
    data.get(payload);

    // print
    System.out.println(scionHeader);
    System.out.println(pathHeaderScion);
    System.out.println(overlayHeaderUdp);
    System.out.println("Payload: " + new String(payload));
  }

  private static ByteBuffer compose(byte[] payload) {
    // Send packet
    ByteBuffer newData = ByteBuffer.allocate(10000);
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

    scionHeader.write(
            newData, userInput.getLength(), pathHeaderScion.length(), Constants.PathTypes.SCION);
    pathHeaderScion.writePath(newData, path);
    overlayHeaderUdp.write(newData, userInput.getLength(), 33333, 44444);

    // payload
    newData.put(userInput.getData(), userInput.getOffset(), userInput.getLength());

    return newData;
  }

  public static ByteBuffer composeReply(ByteBuffer data, byte[] userData) {
    ScionHeader scionHeader = new ScionHeader();
    PathHeaderScion pathHeaderScion = new PathHeaderScion();
    OverlayHeader overlayHeaderUdp = new OverlayHeader();
    scionHeader.read(data);
    pathHeaderScion.read(data);
    overlayHeaderUdp.read(data);

    // reverse path etc
    scionHeader.reverse();
    pathHeaderScion.reverse();
    overlayHeaderUdp.reverse();

    ByteBuffer newData = ByteBuffer.allocate(data.limit());
    scionHeader.write(
            newData, userData.length, pathHeaderScion.length(), Constants.PathTypes.SCION);
    pathHeaderScion.write(newData);
    overlayHeaderUdp.write(newData, userData.length);
    newData.put(userData);

    return newData;
  }
}
