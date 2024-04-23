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

package org.scion.jpan.internal;

import static org.scion.jpan.internal.ByteUtil.readBoolean;
import static org.scion.jpan.internal.ByteUtil.readInt;
import static org.scion.jpan.internal.ByteUtil.readLong;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class PathRawParser {
  private static final String NL = System.lineSeparator();

  // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset
  // calculations below).
  private int currINF;
  // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset
  // calculations below).
  private int currHF;
  // 6 bit : RSV
  private int reserved;
  // Up to 3 Info fields and up to 64 Hop fields
  // The number of hop fields in a given segment. Seg,Len > 0 implies the existence of info field i.
  // 6 bit : Seg0Len
  // 6 bit : Seg1Len
  // 6 bit : Seg2Len
  private final int[] segLen = new int[3];
  private final InfoField[] info = new InfoField[3];

  private final HopField[] hops = new HopField[64];
  private int nHops;

  private int len;

  public PathRawParser() {
    this.info[0] = new InfoField();
    this.info[1] = new InfoField();
    this.info[2] = new InfoField();
    Arrays.setAll(hops, value -> new HopField());
  }

  public void read(ByteBuffer data) {
    int start = data.position();
    // 2 bit : (C)urrINF : 2-bits index (0-based) pointing to the current info field (see offset
    // calculations below).
    // 6 bit : CurrHF :    6-bits index (0-based) pointing to the current hop field (see offset
    // calculations below).
    // 6 bit : RSV
    // Up to 3 Info fields and up to 64 Hop fields
    // The number of hop fields in a segment. SegLen > 0 implies the existence of info field i.
    // 6 bit : Seg0Len
    // 6 bit : Seg1Len
    // 6 bit : Seg2Len

    int i0 = data.getInt();
    currINF = readInt(i0, 0, 2);
    currHF = readInt(i0, 2, 6);
    reserved = readInt(i0, 8, 6);
    segLen[0] = readInt(i0, 14, 6);
    segLen[1] = readInt(i0, 20, 6);
    segLen[2] = readInt(i0, 26, 6);

    for (int i = 0; i < segLen.length && segLen[i] > 0; i++) {
      info[i].read(data);
    }

    nHops = segLen[0] + segLen[1] + segLen[2];
    for (int i = 0; i < nHops; i++) {
      hops[i].read(data);
    }

    len = data.position() - start;
  }

  @Override
  public String toString() {
    StringBuilder s = new StringBuilder();
    s.append("currINF=").append(currINF).append("  currHP=").append(currHF);
    s.append("  reserved=").append(reserved);
    for (int i = 0; i < segLen.length; i++) {
      s.append("  seg").append(i).append("Len=").append(segLen[i]);
    }
    for (int i = 0; i < segLen.length; i++) {
      if (segLen[i] > 0) {
        s.append(NL).append("  info").append(i).append("=").append(info[i]);
      }
    }
    for (int i = 0; i < nHops; i++) {
      s.append(NL).append("    hop=").append(hops[i]);
    }
    return s.toString();
  }

  public int length() {
    return len;
  }

  public InfoField getInfoField(int i) {
    return info[i];
  }

  public int getHopCount() {
    return nHops;
  }

  public HopField getHopField(int i) {
    return hops[i];
  }

  public int getSegLen(int i) {
    return segLen[i];
  }

  public static class HopField {

    /**
     * 1 bit : ConsIngress Router Alert. If the ConsIngress Router Alert is set, the ingress router
     * (in construction direction) will process the L4 payload in the packet.
     */
    private boolean flagI;

    /**
     * 1 bit : ConsEgress Router Alert. If the ConsEgress Router Alert is set, the egress router (in
     * construction direction) will process the L4 payload in the packet.
     */
    private boolean flagE;

    /**
     * 8 bits : Expiry time of a hop field. The expiration time expressed is relative. An absolute
     * expiration time in seconds is computed in combination with the timestamp field (from the
     * corresponding info field) as follows: abs_time = timestamp + (1+expiryTime)*24*60*60/256
     */
    private int expiryTime;

    // 16 bits : consIngress : The 16-bits ingress interface IDs in construction direction.
    private int consIngress;
    // 16 bits : consEgress : The 16-bits egress interface IDs in construction direction.
    private int consEgress;
    // 48 bits : MAC : 6-byte Message Authentication Code to authenticate the hop field.
    // For details on how this MAC is calculated refer to Hop Field MAC Computation:
    // https://scion.docs.anapaya.net/en/latest/protocols/scion-header.html#hop-field-mac-computation
    private long mac;

    HopField() {}

    public void read(ByteBuffer data) {
      int i0 = data.getInt();
      long l1 = data.getLong();
      flagI = readBoolean(i0, 6);
      flagE = readBoolean(i0, 7);
      expiryTime = readInt(i0, 8, 8);
      consIngress = readInt(i0, 16, 16);
      consEgress = (int) readLong(l1, 0, 16);
      mac = readLong(l1, 16, 48);
    }

    @Override
    public String toString() {
      String s = "I=" + flagI + ", E=" + flagE + ", expiryTime=" + expiryTime;
      s += ", consIngress=" + consIngress + ", consEgress=" + consEgress + ", mac=" + mac;
      return s;
    }

    public int length() {
      return 12;
    }

    public int getIngress() {
      return consIngress;
    }

    public int getEgress() {
      return consEgress;
    }

    public boolean hasIngressAlert() {
      return flagI;
    }

    public boolean hasEgressAlert() {
      return flagE;
    }
  }

  public static class InfoField {

    private boolean p;
    private boolean c;
    // 16 bits : segID
    private int segID;
    // 32 bits : timestamp (unsigned int)
    private int timestampRaw; // "raw" because field type is "signed int"

    InfoField() {}

    public void read(ByteBuffer data) {
      int i0 = data.getInt();
      int i1 = data.getInt();
      p = readBoolean(i0, 6);
      c = readBoolean(i0, 7);
      segID = readInt(i0, 16, 16);
      timestampRaw = i1;
    }

    public int length() {
      return 8;
    }

    @Override
    public String toString() {
      long ts = Integer.toUnsignedLong(timestampRaw);
      return "P=" + p + ", C=" + c + ", segID=" + segID + ", timestamp=" + ts;
    }

    public long getTimestamp() {
      return Integer.toUnsignedLong(timestampRaw);
    }

    public boolean hasConstructionDirection() {
      return c;
    }

    public boolean getFlagC() {
      return c;
    }
  }
}
