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

public class ScionPacketInspector {
  private final ScionHeader scionHeader = new ScionHeader();
  private final PathHeaderScion pathHeaderScion = new PathHeaderScion();
  private final PathHeaderOneHopPath pathHeaderOneHop = new PathHeaderOneHopPath();
  private final OverlayHeader overlayHeaderUdp = new OverlayHeader();

  public ScionPacketInspector() {}

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
    return sb.toString();
  }

  /**
   * Read a SCION header.
   * @param data A ButeBuffer containing the header data.
   * @return "true" iff the header could be read successfully.
   * @throws IOException if an IO error occurs.
   */
  public boolean readScionHeader(ByteBuffer data) throws IOException {
    int headerOffset = 0;
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
    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
      pathHeaderOneHop.read(data);
      return false;
    } else {
      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
    }

    // Pseudo header
    if (scionHeader.nextHeader() == Constants.HdrTypes.UDP) {
      overlayHeaderUdp.read(data);

      // Create a copy for returning data
      //byte[] copyHeader = new byte[offset];
      //System.arraycopy(data, headerOffset, copyHeader, 0, offset - headerOffset);
      // TODO use copied header

    } else if (scionHeader.nextHeader() == Constants.HdrTypes.SCMP) {
      System.out.println("Packet: DROPPED: SCMP");
      return false;
    } else if (scionHeader.nextHeader() == Constants.HdrTypes.END_TO_END) {
      System.out.println("Packet EndToEnd");
      ScionEndToEndExtensionHeader e2eHeader = new ScionEndToEndExtensionHeader();
      e2eHeader.read(data);
      if (e2eHeader.nextHdr() == Constants.HdrTypes.SCMP) {
        ScionSCMPHeader scmpHdr = new ScionSCMPHeader();
        scmpHdr.read(data);
        System.out.println("SCMP:");
        System.out.println("    type: " + scmpHdr.getType().getText());
        System.out.println("    code: " + scmpHdr.getCode());
      } else {
        System.out.println("Packet: DROPPED not implemented: " + scionHeader.nextHeader().name());
        return false;
      }
      return false;
    } else {
      System.out.println("Packet: DROPPED unknown: " + scionHeader.nextHeader().name());
      return false;
    }
    return true;
  }
}
