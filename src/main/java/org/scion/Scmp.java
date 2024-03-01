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

package org.scion;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Scmp {

  interface ParseEnum {

    int id();

    static <E extends ParseEnum> E parse(Class<E> e, int code) {
      E[] values = e.getEnumConstants();
      for (int i = 0; i < values.length; i++) {
        E pe = values[i];
        if (pe.id() == code) {
          return pe;
        }
      }
      throw new IllegalArgumentException("Unknown code: " + code);
    }
  }

  public enum Type implements ParseEnum {
    // SCMP error messages:
    ERROR_1(1, "Destination Unreachable"),
    ERROR_2(2, "Packet Too Big"),
    ERROR_3(3, "(not assigned)"),
    ERROR_4(4, "Parameter Problem"),
    ERROR_5(5, "External Interface Down"),
    ERROR_6(6, "Internal Connectivity Down"),
    ERROR_100(100, "Private Experimentation"),
    ERROR_101(101, "Private Experimentation"),
    ERROR_127(127, "Reserved for expansion of SCMP error messages"),

    // SCMP informational messages:
    INFO_128(128, "Echo Request"),
    INFO_129(129, "Echo Reply"),
    INFO_130(130, "Traceroute Request"),
    INFO_131(131, "Traceroute Reply"),
    INFO_200(200, "Private Experimentation"),
    INFO_201(201, "Private Experimentation"),
    INFO_255(255, "Reserved for expansion of SCMP informational messages");

    final int id;
    final String text;

    Type(int id, String text) {
      this.id = id;
      this.text = text;
    }

    public static Type parse(int id) {
      return ParseEnum.parse(Type.class, id);
    }

    @Override
    public int id() {
      return id;
    }

    public String getText() {
      return text;
    }

    @Override
    public String toString() {
      return id + ":'" + text + '\'';
    }
  }

  public enum TypeCode implements ParseEnum {
    // SCMP type 1 messages:
    TYPE_1_CODE_0(1, 0, "No route to destination"),
    TYPE_1_CODE_1(1, 1, "Communication administratively denied"),
    TYPE_1_CODE_2(1, 2, "Beyond scope of source address"),
    TYPE_1_CODE_3(1, 3, "Address unreachable"),
    TYPE_1_CODE_4(1, 4, "Port unreachable"),
    TYPE_1_CODE_5(1, 5, "Source address failed ingress/egress policy"),
    TYPE_1_CODE_6(1, 6, "Reject route to destination"),

    TYPE_2(2, 0, ""),
    TYPE_3(3, 0, ""),

    // Type 4 codes
    TYPE_4_CODE_0(4, 0, "Erroneous header field"),
    TYPE_4_CODE_1(4, 1, "Unknown NextHdr type"),
    TYPE_4_CODE_2(4, 2, "(unassigned)"),
    TYPE_4_CODE_16(4, 16, "Invalid common header"),
    TYPE_4_CODE_17(4, 17, "Unknown SCION version"),
    TYPE_4_CODE_18(4, 18, "FlowID required"),
    TYPE_4_CODE_19(4, 19, "Invalid packet size"),
    TYPE_4_CODE_20(4, 20, "Unknown path type"),
    TYPE_4_CODE_21(4, 21, "Unknown address format"),
    TYPE_4_CODE_32(4, 32, "Invalid address header"),
    TYPE_4_CODE_33(4, 33, "Invalid source address"),
    TYPE_4_CODE_34(4, 34, "Invalid destination address"),
    TYPE_4_CODE_35(4, 35, "Non-local delivery"),
    TYPE_4_CODE_48(4, 48, "Invalid path"),
    TYPE_4_CODE_49(4, 49, "Unknown hop field cons ingress interface"),
    TYPE_4_CODE_50(4, 50, "Unknown hop field cons egress interface"),
    TYPE_4_CODE_51(4, 51, "Invalid hop field MAC"),
    TYPE_4_CODE_52(4, 52, "Path expired"),
    TYPE_4_CODE_53(4, 53, "Invalid segment change"),
    TYPE_4_CODE_64(4, 64, "Invalid extension header"),
    TYPE_4_CODE_65(4, 65, "Unknown hop-by-hop option"),
    TYPE_4_CODE_66(4, 66, "Unknown end-to-end option"),

    TYPE_5(5, 0, ""),
    TYPE_6(6, 0, ""),

    TYPE_128(128, 0, "Echo Request"),
    TYPE_129(129, 0, "Echo Reply"),
    TYPE_130(130, 0, "Traceroute Request"),
    TYPE_131(131, 0, "Traceroute Reply");

    final int type;
    final int id;
    final String text;

    TypeCode(int type, int code, String text) {
      this.type = type;
      this.id = code;
      this.text = text;
    }

    public static TypeCode parse(int type, int code) {
      TypeCode[] values = TypeCode.class.getEnumConstants();
      for (int i = 0; i < values.length; i++) {
        TypeCode pe = values[i];
        if (pe.id() == code && pe.type == type) {
          return pe;
        }
      }
      throw new IllegalArgumentException("Unknown type/code: " + type + "/" + code);
    }

    @Override
    public int id() {
      return id;
    }

    public int code() {
      return id;
    }

    public int type() {
      return type;
    }

    public String getText() {
      return text;
    }

    public boolean isError() {
      return type >= Type.ERROR_1.id && type <= Type.ERROR_127.id;
    }

    @Override
    public String toString() {
      return type + ":" + id + ":'" + text + '\'';
    }
  }

  public static class Message {
    private TypeCode typeCode;
    private int identifier;
    private int sequenceNumber;
    private Path path;

    /** DO NOT USE! */
    public Message(TypeCode typeCode, int identifier, int sequenceNumber, Path path) {
      this.typeCode = typeCode;
      this.identifier = identifier;
      this.sequenceNumber = sequenceNumber;
      this.path = path;
    }

    public int getIdentifier() {
      return identifier;
    }

    public int getSequenceNumber() {
      return sequenceNumber;
    }

    public TypeCode getTypeCode() {
      return typeCode;
    }

    public Path getPath() {
      return path;
    }

    public void setPath(Path path) {
      this.path = path;
    }

    public void setMessageArgs(TypeCode sc, int identifier, int sequenceNumber) {
      this.typeCode = sc;
      this.identifier = identifier;
      this.sequenceNumber = sequenceNumber;
    }
  }

  public abstract static class TimedMessage extends Message {
    private long nanoSeconds;
    private boolean timedOut = false;

    private TimedMessage(TypeCode typeCode, int identifier, int sequenceNumber, Path path) {
      super(typeCode, identifier, sequenceNumber, path);
    }

    public void setNanoSeconds(long nanoSeconds) {
      this.nanoSeconds = nanoSeconds;
    }

    public long getNanoSeconds() {
      return nanoSeconds;
    }

    public void setTimedOut() {
      this.timedOut = true;
    }

    public boolean isTimedOut() {
      return timedOut;
    }
  }

  public static class EchoMessage extends TimedMessage {
    private byte[] data;
    private int sizeSent;
    private int sizeReceived;

    private EchoMessage(
        TypeCode typeCode, int identifier, int sequenceNumber, Path path, byte[] data) {
      super(typeCode, identifier, sequenceNumber, path);
      this.data = data;
    }

    public static EchoMessage createEmpty(Path path) {
      return new EchoMessage(TypeCode.TYPE_128, -1, -1, path, null);
    }

    public static EchoMessage createRequest(int sequenceNumber, Path path, ByteBuffer payload) {
      byte[] data = new byte[payload.remaining()];
      payload.get(data);
      return new EchoMessage(TypeCode.TYPE_128, -1, sequenceNumber, path, data);
    }

    public byte[] getData() {
      return data;
    }

    public void setData(byte[] data) {
      this.data = data;
    }

    public void setSizeSent(int sizeSent) {
      this.sizeSent = sizeSent;
    }

    public int getSizeSent() {
      return sizeSent;
    }

    public void setSizeReceived(int sizeReceived) {
      this.sizeReceived = sizeReceived;
    }

    public int getSizeReceived() {
      return sizeReceived;
    }
  }

  public static class TracerouteMessage extends TimedMessage {

    private long isdAs;
    private long ifID;

    private TracerouteMessage(TypeCode typeCode, int identifier, int sequenceNumber, Path path) {
      this(typeCode, identifier, sequenceNumber, 0, 0, path);
    }

    public static TracerouteMessage createEmpty(Path path) {
      return new TracerouteMessage(TypeCode.TYPE_130, -1, -1, path);
    }

    public static TracerouteMessage createRequest(int sequenceNumber, Path path) {
      return new TracerouteMessage(TypeCode.TYPE_130, -1, sequenceNumber, path);
    }

    public TracerouteMessage(
        TypeCode typeCode, int identifier, int sequenceNumber, long isdAs, long ifID, Path path) {
      super(typeCode, identifier, sequenceNumber, path);
      this.isdAs = isdAs;
      this.ifID = ifID;
    }

    public long getIsdAs() {
      return isdAs;
    }

    public long getIfID() {
      return ifID;
    }

    @Override
    public String toString() {
      String echoMsgStr = getTypeCode().getText();
      echoMsgStr += " scmp_seq=" + getSequenceNumber();
      echoMsgStr += " " + ScionUtil.toStringIA(getIsdAs()) + " IfID=" + getIfID();
      return echoMsgStr;
    }

    public void setTracerouteArgs(long isdAs, long ifID) {
      this.isdAs = isdAs;
      this.ifID = ifID;
    }
  }

  static Scmp.Message createMessage(Type type, Path path) {
    switch (type) {
      case INFO_128:
      case INFO_129:
        return Scmp.EchoMessage.createEmpty(path);
      case INFO_130:
      case INFO_131:
        return Scmp.TracerouteMessage.createEmpty(path);
      default:
        return new Scmp.Message(null, -1, -1, path);
    }
  }

  /**
   * Create a channel for sending SCMP requests.
   *
   * @param path Path to destination
   * @return New SCMP channel
   */
  public static ScmpChannel createChannel(RequestPath path) throws IOException {
    return new ScmpChannel(path);
  }

  /**
   * Create a channel for sending SCMP requests.
   *
   * @param path Path to destination
   * @param listeningPort Local port to listen for SCMP requests.
   * @return New SCMP channel
   */
  public static ScmpChannel createChannel(RequestPath path, int listeningPort) throws IOException {
    return new ScmpChannel(Scion.defaultService(), path, listeningPort);
  }

  /**
   * Create a channel for sending SCMP requests.
   *
   * @param service the ScionService instance
   * @param path Path to destination
   * @param listeningPort Local port to listen for SCMP requests.
   * @return New SCMP channel
   */
  public static ScmpChannel createChannel(ScionService service, RequestPath path, int listeningPort)
      throws IOException {
    return new ScmpChannel(service, path, listeningPort);
  }
}
