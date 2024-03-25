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

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import org.scion.Scmp;
import org.scion.internal.InternalConstants;

public class ScionPacketInspector {
  private final ScionHeader scionHeader = new ScionHeader();
  private final PathHeaderScion pathHeaderScion = new PathHeaderScion();
  private final PathHeaderOneHopPath pathHeaderOneHop = new PathHeaderOneHopPath();
  private final OverlayHeader overlayHeaderUdp = new OverlayHeader();
  private final ScmpHeader scmpHeader = new ScmpHeader();
  private byte[] payload;

  public ScionPacketInspector() {}

  public static ScionPacketInspector createEmpty() {
    return new ScionPacketInspector();
  }

  public static ScionPacketInspector readPacket(ByteBuffer buffer) {
    ScionPacketInspector spi = new ScionPacketInspector();
    try {
      spi.read(buffer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return spi;
  }

  public InetAddress getSourceAddress() throws IOException {
    return scionHeader.getSrcHostAddress();
  }

  public InetSocketAddress getReceivedDstAddress() throws IOException {
    return new InetSocketAddress(scionHeader.getDstHostAddress(), overlayHeaderUdp.getDstPort());
  }

  public int getPayloadLength() {
    return scionHeader.getPayloadLength() - overlayHeaderUdp.length();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(scionHeader).append("\n");
    if (scionHeader.pathType() == Constants.PathTypes.SCION) {
      sb.append(pathHeaderScion).append("\n");
    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
      sb.append(pathHeaderOneHop).append("\n");
    } else {
      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
    }
    sb.append(overlayHeaderUdp).append("\n");
    sb.append("Payload: ").append(Arrays.toString(payload)).append("\n");
    return sb.toString();
  }

  /**
   * Read a SCION header.
   *
   * @param data A ByteBuffer containing the header data.
   * @return "true" iff the header could be read successfully.
   * @throws IOException if an IO error occurs.
   */
  public boolean read(ByteBuffer data) throws IOException {
    scionHeader.read(data);
    if (scionHeader.getDT() != 0) {
      System.out.println(
          "PACKET DROPPED: service address="
              + scionHeader.getDstHostAddress()
              + "  DT="
              + scionHeader.getDT());
      return false;
    }

    if (scionHeader.pathType() == Constants.PathTypes.SCION) {
      pathHeaderScion.read(data);
    } else if (scionHeader.pathType() == Constants.PathTypes.Empty) {
      // Empty path, nothing to do
    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
      pathHeaderOneHop.read(data);
      return false;
    } else {
      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
    }

    // Overlay header
    if (scionHeader.nextHeader() == Constants.HdrTypes.UDP) {
      overlayHeaderUdp.read(data);
    } else if (scionHeader.nextHeader() == Constants.HdrTypes.SCMP) {
      int offset = scionHeader.hdrLenBytes();
      data.position(offset);
      scmpHeader.read(data);
      return false;
    } else if (scionHeader.nextHeader() == Constants.HdrTypes.END_TO_END) {
      // System.out.println("Packet EndToEnd");
      ExtensionHeader e2eHeader = new ExtensionHeader();
      e2eHeader.read(data);
      // System.out.println(e2eHeader);
      if (e2eHeader.nextHdr() == Constants.HdrTypes.SCMP) {
        scmpHeader.read(data);
        //        System.out.println("SCMP:");
        //        System.out.println("    type: " + scmpHeader.getType().getText());
        //        System.out.println("    code: " + scmpHeader.getCode());
      } else {
        System.out.println("Packet: DROPPED not implemented: " + scionHeader.nextHeader().name());
        return false;
      }
      return false;
    } else {
      System.out.println("Packet: DROPPED unknown: " + scionHeader.nextHeader().name());
      return false;
    }

    payload = new byte[getPayloadLength()];
    data.get(payload);

    return true;
  }

  public ScionHeader getScionHeader() {
    return scionHeader;
  }

  public PathHeaderScion getPathHeaderScion() {
    return pathHeaderScion;
  }

  public OverlayHeader getOverlayHeaderUdp() {
    return overlayHeaderUdp;
  }

  public byte[] getPayLoad() {
    return payload;
  }

  public void reversePath() {
    scionHeader.reverse();
    pathHeaderScion.reverse();
    if (scionHeader.nextHeader() == Constants.HdrTypes.UDP) {
      overlayHeaderUdp.reverse();
    }
  }

  public void writePacket(ByteBuffer newData, byte[] userData) {
    scionHeader.write(
        newData, userData.length, pathHeaderScion.length(), Constants.PathTypes.SCION);
    pathHeaderScion.write(newData);
    overlayHeaderUdp.write(newData, userData.length);
    newData.put(userData);
  }

  public void writePacketSCMP(ByteBuffer newData) {
    Scmp.Type type = scmpHeader.getType();
    EnumSet<Scmp.Type> errors =
        EnumSet.of(
            Scmp.Type.ERROR_1,
            Scmp.Type.ERROR_2,
            Scmp.Type.ERROR_3,
            Scmp.Type.ERROR_4,
            Scmp.Type.ERROR_5,
            Scmp.Type.ERROR_6);
    if (type == Scmp.Type.INFO_128 || type == Scmp.Type.INFO_129) {
      scionHeader.write(
          newData,
          scmpHeader.getUserData().length + 8,
          pathHeaderScion.length(),
          Constants.PathTypes.SCION,
          InternalConstants.HdrTypes.SCMP);
      pathHeaderScion.write(newData);
      scmpHeader.writeEcho(newData);
    } else if (type == Scmp.Type.INFO_130 || type == Scmp.Type.INFO_131) {
      scionHeader.write(
          newData,
          24,
          pathHeaderScion.length(),
          Constants.PathTypes.SCION,
          InternalConstants.HdrTypes.SCMP);
      pathHeaderScion.write(newData);
      scmpHeader.writeTraceroute(newData);
    } else if (errors.contains(type)) {
      scionHeader.write(
          newData,
          8,
          pathHeaderScion.length(),
          Constants.PathTypes.SCION,
          InternalConstants.HdrTypes.SCMP);
      pathHeaderScion.write(newData);
      scmpHeader.writeError(newData);
    } else {
      throw new UnsupportedOperationException();
    }
  }

  public ScmpHeader getScmpHeader() {
    return scmpHeader;
  }
}
