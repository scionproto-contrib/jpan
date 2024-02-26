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

package org.scion.demo;

import org.scion.ScionUtil;

public class DemoConstants {

  public enum Network {
    MOCK_TOPOLOGY, // SCION Java JUnit mock network
    MOCK_TOPOLOGY_IPV4, // SCION Java JUnit mock network, IPv4 only
    TINY_PROTO, // Try to connect to "tiny" scionproto network
    MINIMAL_PROTO, // Try to connect to "minimal" scionproto network
    PRODUCTION // production network
  }

  // ----------------  ISD/AS for local test networks ----------------
  public static final long ia110 = ScionUtil.parseIA("1-ff00:0:110");
  public static final long ia111 = ScionUtil.parseIA("1-ff00:0:111");
  public static final long ia1111 = ScionUtil.parseIA("1-ff00:0:1111");
  public static final long ia1112 = ScionUtil.parseIA("1-ff00:0:1112");
  public static final long ia112 = ScionUtil.parseIA("1-ff00:0:112");
  public static final long ia1121 = ScionUtil.parseIA("1-ff00:0:1121");
  public static final long ia120 = ScionUtil.parseIA("1-ff00:0:120");
  public static final long ia121 = ScionUtil.parseIA("1-ff00:0:121");
  public static final long ia210 = ScionUtil.parseIA("2-ff00:0:210");
  public static final long ia211 = ScionUtil.parseIA("2-ff00:0:211");

  // ----------------  "minimal" network ----------------
  public static final String daemon110_minimal = "127.0.0.29:30255";
  public static final String daemon111_minimal = "127.0.0.37:30255";
  public static final String daemon1111_minimal = "127.0.0.43:30255";
  public static final String daemon120_minimal = "127.0.0.76:30255";
  public static final String daemon121_minimal = "127.0.0.83:30255";
  public static final String daemon210_minimal = "127.0.0.92:30255";
  public static final String csAddr110_minimal = "127.0.0.28:31000";
  public static final String csAddr111_minimal = "127.0.0.36:31014";
  public static final String csAddr1111_minimal = "127.0.0.42:31022";
  public static final String csAddr120_minimal = "127.0.0.75:31008";
  public static final String csAddr210_minimal = "127.0.0.91:31038";

  // ----------------   "tiny" network  ------------------------
  public static final String daemon110_tiny = "127.0.0.12:30255";
  public static final String daemon111_tiny = "127.0.0.19:30255";
  public static final String csAddr110_tiny = "127.0.0.11:31000";
  public static final String csAddr111_tiny = "127.0.0.18:31006";
  public static final String csAddr112_tiny = "[fd00:f00d:cafe::7f00:a]:31010";

  // ----------------   "default" network  ------------------------
  public static final String csAddr110_default = "[fd00:f00d:cafe::7f00:14]:31000";
  public static final String csAddr111_default = "[fd00:f00d:cafe::7f00:1c]:31022";

  // ----------------  Production network  ----------------
  public static final long iaETH = ScionUtil.parseIA("64-2:0:9");
  public static final long iaETH_CORE = ScionUtil.parseIA("64-0:0:22f");
  public static final long iaGEANT = ScionUtil.parseIA(ScionUtil.toStringIA(71, 20965));
  public static final long iaOVGU = ScionUtil.parseIA("71-2:0:4a");
  public static final long iaAnapayaHK = ScionUtil.parseIA("66-2:0:11");
  public static final long iaEquinix = ScionUtil.parseIA("71-2:0:48");
  public static final long iaCyberex = ScionUtil.parseIA("71-2:0:49");
  public static final long iaPrinceton = ScionUtil.parseIA(ScionUtil.toStringIA(71, 88));
  public static final String csETH = "192.168.53.20:30252";
}
