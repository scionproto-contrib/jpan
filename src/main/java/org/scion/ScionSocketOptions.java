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

import java.net.SocketOption;

/** A home for SCION (SN) channel/socket options. */
public final class ScionSocketOptions {

  /**
   * If set to 'true', the Scion header parser will throw errors when encountering problems while
   * parsing a packet header. If set to 'false', problematic packets are silently dropped. Default
   * is 'false'.
   */
  public static final SocketOption<Boolean> SN_API_THROW_PARSER_FAILURE =
      new SciSocketOption<>("SN_API_THROW_PARSER_FAILURE", Boolean.class);

  /**
   * If set to 'true', the receive() and read() operations will read new packets directly into the
   * ByteBuffer provided by the user. The ByteBuffer will contain the header and the position of
   * will be set to the first byte of the payload. This has two advantages: the payload does not
   * need to be copied (saving one copy operation) and the Scion packet header is directly available
   * to the user. If set to 'false', the receive() and read() operations will copy the payload to
   * the ByteBuffer provided by the user. Default is 'false'.
   */
  @Deprecated // TODO implement
  public static final SocketOption<Boolean> SN_API_WRITE_TO_USER_BUFFER =
      new SciSocketOption<>("SN_API_WRITE_TO_USER_BUFFER", Boolean.class);

  /**
   * Before sending a packet, a new path will be requested if now() + pathExpirationMargin >
   * pathExpirationDate.
   */
  public static final SocketOption<Integer> SN_PATH_EXPIRY_MARGIN =
      new SciSocketOption<>("SN_PATH_EXPIRY_MARGIN", Integer.class);

  /**
   * Set the traffic class SCION header.
   *
   * @deprecated This feature may be removed in a future release
   */
  @Deprecated
  public static final SocketOption<Integer> SN_TRAFFIC_CLASS =
      new SciSocketOption<>("SN_TRAFFIC_CLASS", Integer.class);

  private ScionSocketOptions() {}

  static class SciSocketOption<T> implements SocketOption<T> {
    private final String name;
    private final Class<T> type;

    SciSocketOption(String name, Class<T> type) {
      this.name = name;
      this.type = type;
    }

    public String name() {
      return this.name;
    }

    public Class<T> type() {
      return this.type;
    }

    public String toString() {
      return this.name;
    }
  }
}
