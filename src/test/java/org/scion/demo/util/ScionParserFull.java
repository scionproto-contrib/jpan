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

package org.scion.demo.util;

import java.io.IOException;
import java.net.*;
import org.scion.internal.Constants;
import org.scion.internal.OverlayHeader;
import org.scion.internal.PathHeaderScion;
import org.scion.internal.ScionHeader;
import org.scion.internal.ScionSCMPHeader;

public class ScionParserFull {
  private final ScionHeader scionHeader = new ScionHeader();
  private final PathHeaderScion pathHeaderScion = new PathHeaderScion();
  private final PathHeaderOneHopPath pathHeaderOneHop = new PathHeaderOneHopPath();
  private final OverlayHeader overlayHeaderUdp = new OverlayHeader();

  public ScionParserFull() {}

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

  public int readScionHeader(byte[] data) throws IOException {
    int headerOffset = 0;
    int offset = scionHeader.read(data, headerOffset);
    if (scionHeader.getDT() != 0) {
      System.out.println(
          "PACKET DROPPED: service address="
              + scionHeader.getDstHostAddress()
              + "  DT="
              + scionHeader.getDT());
      return -1;
    }

    if (scionHeader.pathType() == Constants.PathTypes.SCION) {
      offset = pathHeaderScion.read(data, offset);
    } else if (scionHeader.pathType() == Constants.PathTypes.OneHop) {
      offset = pathHeaderOneHop.read(data, offset);
      return -1;
    } else {
      throw new UnsupportedOperationException("Path type: " + scionHeader.pathType());
    }

    // Pseudo header
    if (scionHeader.nextHeader() == Constants.HdrTypes.UDP) {
      offset = overlayHeaderUdp.read(data, offset);

      // Create a copy for returning data
      byte[] copyHeader = new byte[offset];
      System.arraycopy(data, headerOffset, copyHeader, 0, offset - headerOffset);
      // TODO use copied header

    } else if (scionHeader.nextHeader() == Constants.HdrTypes.SCMP) {
      System.out.println("Packet: DROPPED: SCMP");
      return -1;
    } else if (scionHeader.nextHeader() == Constants.HdrTypes.END_TO_END) {
      System.out.println("Packet EndToEnd");
      ScionEndToEndExtensionHeader e2eHeader = new ScionEndToEndExtensionHeader();
      offset = e2eHeader.read(data, offset);
      if (e2eHeader.nextHdr() == Constants.HdrTypes.SCMP) {
        ScionSCMPHeader scmpHdr = new ScionSCMPHeader();
        offset = scmpHdr.read(data, offset);
        System.out.println("SCMP:");
        System.out.println("    type: " + scmpHdr.getType().getText());
        System.out.println("    code: " + scmpHdr.getCode());
      } else {
        System.out.println("Packet: DROPPED not implemented: " + scionHeader.nextHeader().name());
        return -1;
      }
      return -1;
    } else {
      System.out.println("Packet: DROPPED unknown: " + scionHeader.nextHeader().name());
      return -1;
    }
    return offset;
  }
}
