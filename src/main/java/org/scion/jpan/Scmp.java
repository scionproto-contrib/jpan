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

package org.scion.jpan;

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
    ERROR_1(1, "Destination Unreachable", 8),
    ERROR_2(2, "Packet Too Big", 8),
    ERROR_3(3, "(not assigned)", 0),
    ERROR_4(4, "Parameter Problem", 8),
    ERROR_5(5, "External Interface Down", 20),
    ERROR_6(6, "Internal Connectivity Down", 28),
    ERROR_100(100, "Private Experimentation", 0),
    ERROR_101(101, "Private Experimentation", 0),
    ERROR_127(127, "Reserved for expansion of SCMP error messages", 0),

    // SCMP informational messages:
    INFO_128(128, "Echo Request", 8),
    INFO_129(129, "Echo Reply", 8),
    INFO_130(130, "Traceroute Request", 24),
    INFO_131(131, "Traceroute Reply", 24),
    INFO_200(200, "Private Experimentation", 0),
    INFO_201(201, "Private Experimentation", 0),
    INFO_255(255, "Reserved for expansion of SCMP informational messages", 0);

    final int id;
    final String text;
    final int headerLength;

    Type(int id, String text, int headerLength) {
      this.id = id;
      this.text = text;
      this.headerLength = headerLength;
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

    public int getHeaderLength() {
      return headerLength;
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

    TYPE_2(2, 0, "Packet Too Big"),
    TYPE_3(3, 0, "(not assigned)"),

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

    TYPE_5(5, 0, "External Interface Down"),
    TYPE_6(6, 0, "Internal Connectivity Down"),
    TYPE_100(100, 0, "Private Experimentation"),
    TYPE_101(101, 0, "Private Experimentation"),
    TYPE_127(127, 0, "Reserved for expansion of SCMP error messages"),

    TYPE_128(128, 0, "Echo Request"),
    TYPE_129(129, 0, "Echo Reply"),
    TYPE_130(130, 0, "Traceroute Request"),
    TYPE_131(131, 0, "Traceroute Reply"),

    TYPE_200(200, 0, "Private Experimentation"),
    TYPE_201(201, 0, "Private Experimentation"),
    TYPE_255(255, 0, "Reserved for expansion of SCMP informational messages");

    final int type;
    final int id;
    final String text;

    TypeCode(int type, int code, String text) {
      this.type = type;
      this.id = code;
      this.text = text;
    }

    public static TypeCode parse(int type, int code) {
      TypeCode typeCode = parseOrNull(type, code);
      if (typeCode == null) {
        throw new IllegalArgumentException("Unknown type/code: " + type + "/" + code);
      }
      return typeCode;
    }

    public static TypeCode parseOrNull(int type, int code) {
      TypeCode[] values = TypeCode.class.getEnumConstants();
      for (int i = 0; i < values.length; i++) {
        TypeCode pe = values[i];
        if (pe.id() == code && pe.type == type) {
          return pe;
        }
      }
      return null;
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
    private final TypeCode typeCode;
    private final int identifier;
    private final int sequenceNumber;
    private final Path path;

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
  }

  public abstract static class TimedMessage extends Message {
    private long sendNanoSeconds;
    private long receiveNanoSeconds;
    private boolean timedOut = false;
    // If (this) is a response then "request" may contain the original request
    private TimedMessage request;

    private TimedMessage(TypeCode typeCode, int identifier, int sequenceNumber, Path path) {
      super(typeCode, identifier, sequenceNumber, path);
    }

    public long getSendNanoSeconds() {
      return this.sendNanoSeconds;
    }

    public void setSendNanoSeconds(long l) {
      this.sendNanoSeconds = l;
    }

    public long getReceiveNanoSeconds() {
      return this.receiveNanoSeconds;
    }

    public void setReceiveNanoSeconds(long l) {
      this.receiveNanoSeconds = l;
    }

    public long getNanoSeconds() {
      return receiveNanoSeconds - sendNanoSeconds;
    }

    public void setTimedOut(long timeOutNS) {
      this.timedOut = true;
      this.receiveNanoSeconds = this.sendNanoSeconds + timeOutNS;
    }

    public boolean isTimedOut() {
      return timedOut;
    }

    public void assignRequest(TimedMessage request, long receivedNanoSeconds) {
      this.request = request;
      this.sendNanoSeconds = request.sendNanoSeconds;
      this.receiveNanoSeconds = receivedNanoSeconds;
    }

    public TimedMessage getRequest() {
      return request;
    }
  }

  public static class ErrorMessage extends Message {
    private byte[] cause;

    private ErrorMessage(TypeCode typeCode, Path path) {
      super(typeCode, 0, 0, path);
    }

    public static ErrorMessage createEmpty(TypeCode typeCode, Path path) {
      return new ErrorMessage(typeCode, path);
    }

    public byte[] getCause() {
      return cause;
    }

    public void setCause(byte[] cause) {
      this.cause = cause;
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

    public static EchoMessage create(
        TypeCode typeCode, int identifier, int sequenceNumber, Path path) {
      if (typeCode != TypeCode.TYPE_128 && typeCode != TypeCode.TYPE_129) {
        throw new IllegalArgumentException("Illegal type for traceroute: " + typeCode);
      }
      return new EchoMessage(typeCode, identifier, sequenceNumber, path, null);
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

    public static TracerouteMessage create(
        TypeCode typeCode, int identifier, int sequenceNumber, Path path) {
      if (typeCode != TypeCode.TYPE_130 && typeCode != TypeCode.TYPE_131) {
        throw new IllegalArgumentException("Illegal type for traceroute: " + typeCode);
      }
      return new TracerouteMessage(typeCode, identifier, sequenceNumber, path);
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

    public void setTracerouteArgs(long isdAs, long ifID) {
      this.isdAs = isdAs;
      this.ifID = ifID;
    }
  }

  /**
   * Create a sender for SCMP requests.
   *
   * @return New SCMP sender builder
   */
  public static ScmpSender.Builder newSenderBuilder() {
    return ScmpSender.newBuilder();
  }

  /**
   * Create an asynchronous non-blocking sender for SCMP requests.
   *
   * @return New SCMP sender builder
   */
  public static ScmpSenderAsync.Builder newSenderAsyncBuilder(
      ScmpSenderAsync.ResponseHandler handler) {
    return ScmpSenderAsync.newBuilder(handler);
  }

  /**
   * Create a SCMP responder. It will listen on 30041 for SCMP echo requests and send a response
   * back.
   *
   * @return New SCMP responder builder
   */
  public static ScmpResponder.Builder newResponderBuilder() {
    return ScmpResponder.newBuilder();
  }
}
