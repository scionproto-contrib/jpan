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

import java.net.InetSocketAddress;
import org.scion.PackageVisibilityHelper;

public class ExamplePacket {

  public static final String MSG = "Hello scion";
  public static final long SRC_IA = ScionUtil.parseIA("1-ff00:0:110");
  public static final long DST_IA = ScionUtil.parseIA("1-ff00:0:112");

  /** IPv6 localhost: "::1" */
  public static final byte[] DST_HOST = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

  /** IPv6 localhost: "::1" */
  public static final byte[] SRC_HOST = {127, 0, 0, 1};

  public static final InetSocketAddress FIRST_HOP = new InetSocketAddress("127.0.0.1", 23456);
  public static final byte[] PATH_RAW_TINY_110_112 = {
    0, 0, 32, 0, 1, 0, 11, 16,
    101, 83, 118, -81, 0, 63, 0, 0,
    0, 2, 118, -21, 86, -46, 89, 0,
    0, 63, 0, 1, 0, 0, -8, 2,
    -114, 25, 76, -122,
  };
  public static final RequestPath PATH =
      PackageVisibilityHelper.createDummyPath(
          DST_IA, DST_HOST, 8080, PATH_RAW_TINY_110_112, FIRST_HOP);

  /**
   * Packet bytes for a message sent in the "tiny"network config in scionproto.
   *
   * <p>Recording from end2end example (w/o tracing etc): Client request (ping).
   */
  public static final byte[] PACKET_BYTES_CLIENT_E2E_PING = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 1,
    0, 0, 32, 0, 1, 0, 11, 16, 101, 83, 118, -81, 0, 63, 0, 0,
    0, 2, 118, -21, 86, -46, 89, 0, 0, 63, 0, 1, 0, 0, -8, 2,
    -114, 25, 76, -122, -83, -100, 31, -112, 0, 19, 68, -82, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };

  /**
   * Packet bytes for a message sent in the "tiny"network config in scionproto.
   *
   * <p>Recording from end2end example (w/o tracing etc): Client: response received (oong).
   */
  public static final byte[] PACKET_BYTES_CLIENT_E2E_PONG = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 3, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 18, 127, 0, 0, 1,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
    1, 0, 32, 0, 0, 0, 11, 16, 101, 83, 118, -81, 0, 63, 0, 1,
    0, 0, -8, 2, -114, 25, 76, -122, 0, 63, 0, 0, 0, 2, 118, -21,
    86, -46, 89, 0, 31, -112, -83, -100, 0, 19, 68, -82, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };

  /**
   * Packet bytes for a message sent in the "tiny"network config in scionproto.
   *
   * <p>Recording from end2end example (w/o tracing etc): Server packet received (ping).
   */
  public static final byte[] PACKET_BYTES_SERVER_E2E_PING = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 48, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 18, 0, 1, -1, 0, 0, 0, 1, 16, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 127, 0, 0, 1,
    1, 0, 32, 0, 1, 0, 125, -5, 101, 83, 118, -81, 0, 63, 0, 0,
    0, 2, 118, -21, 86, -46, 89, 0, 0, 63, 0, 1, 0, 0, -8, 2,
    -114, 25, 76, -122, -83, -100, 31, -112, 0, 19, 68, -82, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };

  /**
   * Packet bytes for a message sent in the "tiny"network config in scionproto.
   *
   * <p>Recording from end2end example (w/o tracing etc): Server response (pong).
   */
  public static final byte[] PACKET_BYTES_SERVER_E2E_PONG = {
    0, 0, 0, 1, 17, 21, 0, 19, 1, 3, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 16, 0, 1, -1, 0, 0, 0, 1, 18, 127, 0, 0, 1,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
    0, 0, 32, 0, 0, 0, 125, -5, 101, 83, 118, -81, 0, 63, 0, 1,
    0, 0, -8, 2, -114, 25, 76, -122, 0, 63, 0, 0, 0, 2, 118, -21,
    86, -46, 89, 0, 31, -112, -83, -100, 0, 19, 68, -82, 72, 101, 108, 108,
    111, 32, 115, 99, 105, 111, 110,
  };
}
