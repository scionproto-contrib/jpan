// Copyright 2025 ETH Zurich, Anapaya Systems
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

package org.scion.jpan.internal.ppl;

import java.util.HashMap;
import java.util.Map;

/** Copied from private/xtest/graph/default_gen.go on 2025-01-15. */
class DefaultGen {

  private static final Map<String, Integer> STATIC_IFACE_ID_MAPPING = new HashMap<>();

  static {
    STATIC_IFACE_ID_MAPPING.put("110_X", 11);
    STATIC_IFACE_ID_MAPPING.put("111_A", 14);
    STATIC_IFACE_ID_MAPPING.put("111_B", 27);
    STATIC_IFACE_ID_MAPPING.put("111_C", 28);
    STATIC_IFACE_ID_MAPPING.put("112_X", 17);
    STATIC_IFACE_ID_MAPPING.put("120_X", 12);
    STATIC_IFACE_ID_MAPPING.put("120_A", 29);
    STATIC_IFACE_ID_MAPPING.put("120_B", 30);
    STATIC_IFACE_ID_MAPPING.put("120_B1", 31);
    STATIC_IFACE_ID_MAPPING.put("121_X", 15);
    STATIC_IFACE_ID_MAPPING.put("122_X", 18);
    STATIC_IFACE_ID_MAPPING.put("130_A", 13);
    STATIC_IFACE_ID_MAPPING.put("130_B", 32);
    STATIC_IFACE_ID_MAPPING.put("131_X", 16);
    STATIC_IFACE_ID_MAPPING.put("132_X", 19);
    STATIC_IFACE_ID_MAPPING.put("133_X", 10);
    STATIC_IFACE_ID_MAPPING.put("210_X", 21);
    STATIC_IFACE_ID_MAPPING.put("210_X1", 33);
    STATIC_IFACE_ID_MAPPING.put("211_A", 23);
    STATIC_IFACE_ID_MAPPING.put("211_A1", 34);
    STATIC_IFACE_ID_MAPPING.put("212_X", 25);
    STATIC_IFACE_ID_MAPPING.put("220_X", 22);
    STATIC_IFACE_ID_MAPPING.put("221_X", 24);
    STATIC_IFACE_ID_MAPPING.put("222_X", 26);
  }

  private static int uint16(int i) {
    return i;
  }

  private static final int IF_110_X_120_A = uint16(1129);
  private static final int IF_120_A_110_X = uint16(2911);
  private static final int IF_110_X_130_A = uint16(1113);
  private static final int IF_130_A_110_X = uint16(1311);
  private static final int IF_110_X_210_X = uint16(1121);
  private static final int IF_210_X_110_X = uint16(2111);
  private static final int IF_120_A_130_B = uint16(2932);
  private static final int IF_130_B_120_A = uint16(3229);
  private static final int IF_120_B_220_X = uint16(3022);
  private static final int IF_220_X_120_B = uint16(2230);
  private static final int IF_120_B1_220_X = uint16(3122);
  private static final int IF_220_X_120_B1 = uint16(2231);
  private static final int IF_120_B_121_X = uint16(3015);
  private static final int IF_121_X_120_B = uint16(1530);
  private static final int IF_120_X_111_B = uint16(1227);
  private static final int IF_111_B_120_X = uint16(2712);
  private static final int IF_130_A_131_X = uint16(1316);
  private static final int IF_131_X_130_A = uint16(1613);
  private static final int IF_130_B_111_A = uint16(3214);
  private static final int IF_111_A_130_B = uint16(1432);
  private static final int IF_130_A_112_X = uint16(1317);
  private static final int IF_112_X_130_A = uint16(1713);
  private static final int IF_111_C_121_X = uint16(2815);
  private static final int IF_121_X_111_C = uint16(1528);
  private static final int IF_111_B_211_A = uint16(2723);
  private static final int IF_211_A_111_B = uint16(2327);
  private static final int IF_111_C_211_A = uint16(2823);
  private static final int IF_211_A_111_C = uint16(2328);
  private static final int IF_111_A_112_X = uint16(1417);
  private static final int IF_112_X_111_A = uint16(1714);
  private static final int IF_121_X_131_X = uint16(1516);
  private static final int IF_131_X_121_X = uint16(1615);
  private static final int IF_121_X_122_X = uint16(1518);
  private static final int IF_122_X_121_X = uint16(1815);
  private static final int IF_122_X_133_X = uint16(1810);
  private static final int IF_133_X_122_X = uint16(1018);
  private static final int IF_131_X_132_X = uint16(1619);
  private static final int IF_132_X_131_X = uint16(1916);
  private static final int IF_132_X_133_X = uint16(1910);
  private static final int IF_133_X_132_X = uint16(1019);
  private static final int IF_210_X_220_X = uint16(2122);
  private static final int IF_220_X_210_X = uint16(2221);
  private static final int IF_210_X_211_A = uint16(2123);
  private static final int IF_211_A_210_X = uint16(2321);
  private static final int IF_210_X1_211_A = uint16(3323);
  private static final int IF_211_A_210_X1 = uint16(2333);
  private static final int IF_220_X_221_X = uint16(2224);
  private static final int IF_221_X_220_X = uint16(2422);
  private static final int IF_211_A_221_X = uint16(2324);
  private static final int IF_221_X_211_A = uint16(2423);
  private static final int IF_211_A_212_X = uint16(2325);
  private static final int IF_212_X_211_A = uint16(2523);
  private static final int IF_211_A1_212_X = uint16(3425);
  private static final int IF_212_X_211_A1 = uint16(2534);
  private static final int IF_211_A_222_X = uint16(2326);
  private static final int IF_222_X_211_A = uint16(2623);
  private static final int IF_221_X_222_X = uint16(2426);
  private static final int IF_222_X_221_X = uint16(2624);

  /**
   * Description contains the entire specification of a graph. It is useful for one shot
   * initialization.
   */
  static class Description {
    String[] nodes;
    EdgeDesc[] edges;

    Description(String[] nodes, EdgeDesc[] edges) {
      this.nodes = nodes;
      this.edges = edges;
    }
  }

  /** EdgeDesc is used in Descriptions to describe the links between ASes. */
  static class EdgeDesc {
    String xIA;
    int xIfID;
    String yIA;
    int yIfID;
    boolean peer;

    public EdgeDesc(String xIA, int xIfID, String yIA, int yIfID, boolean peer) {
      this.xIA = xIA;
      this.xIfID = xIfID;
      this.yIA = yIA;
      this.yIfID = yIfID;
      this.peer = peer;
    }
  }

  public static final Description DefaultGraphDescription =
      new Description(
          new String[] {
            "1-ff00:0:110", // X = 11
            "1-ff00:0:111", // A = 14, B = 27, C = 28
            "1-ff00:0:112", // X = 17
            "1-ff00:0:120", // X = 12, A = 29, B = 30, B1 = 31
            "1-ff00:0:121", // X = 15
            "1-ff00:0:122", // X = 18
            "1-ff00:0:130", // A = 13, B = 32
            "1-ff00:0:131", // X = 16
            "1-ff00:0:132", // X = 19
            "1-ff00:0:133", // X = 10
            "2-ff00:0:210", // X = 21, X1 = 33
            "2-ff00:0:211", // A = 23, A1 = 34
            "2-ff00:0:212", // X = 25
            "2-ff00:0:220", // X = 22
            "2-ff00:0:221", // X = 24
            "2-ff00:0:222", // X = 26
          },
          new EdgeDesc[] {
            new EdgeDesc("1-ff00:0:110", IF_110_X_120_A, "1-ff00:0:120", IF_120_A_110_X, false),
            new EdgeDesc("1-ff00:0:110", IF_110_X_130_A, "1-ff00:0:130", IF_130_A_110_X, false),
            new EdgeDesc("1-ff00:0:110", IF_110_X_210_X, "2-ff00:0:210", IF_210_X_110_X, false),
            new EdgeDesc("1-ff00:0:120", IF_120_A_130_B, "1-ff00:0:130", IF_130_B_120_A, false),
            new EdgeDesc("1-ff00:0:120", IF_120_B_220_X, "2-ff00:0:220", IF_220_X_120_B, false),
            new EdgeDesc("1-ff00:0:120", IF_120_B1_220_X, "2-ff00:0:220", IF_220_X_120_B1, false),
            new EdgeDesc("1-ff00:0:120", IF_120_B_121_X, "1-ff00:0:121", IF_121_X_120_B, false),
            new EdgeDesc("1-ff00:0:120", IF_120_X_111_B, "1-ff00:0:111", IF_111_B_120_X, false),
            new EdgeDesc("1-ff00:0:130", IF_130_A_131_X, "1-ff00:0:131", IF_131_X_130_A, false),
            new EdgeDesc("1-ff00:0:130", IF_130_B_111_A, "1-ff00:0:111", IF_111_A_130_B, false),
            new EdgeDesc("1-ff00:0:130", IF_130_A_112_X, "1-ff00:0:112", IF_112_X_130_A, false),
            new EdgeDesc("1-ff00:0:111", IF_111_C_121_X, "1-ff00:0:121", IF_121_X_111_C, true),
            new EdgeDesc("1-ff00:0:111", IF_111_B_211_A, "2-ff00:0:211", IF_211_A_111_B, true),
            new EdgeDesc("1-ff00:0:111", IF_111_C_211_A, "2-ff00:0:211", IF_211_A_111_C, true),
            new EdgeDesc("1-ff00:0:111", IF_111_A_112_X, "1-ff00:0:112", IF_112_X_111_A, false),
            new EdgeDesc("1-ff00:0:121", IF_121_X_131_X, "1-ff00:0:131", IF_131_X_121_X, true),
            new EdgeDesc("1-ff00:0:121", IF_121_X_122_X, "1-ff00:0:122", IF_122_X_121_X, false),
            new EdgeDesc("1-ff00:0:122", IF_122_X_133_X, "1-ff00:0:133", IF_133_X_122_X, true),
            new EdgeDesc("1-ff00:0:131", IF_131_X_132_X, "1-ff00:0:132", IF_132_X_131_X, false),
            new EdgeDesc("1-ff00:0:132", IF_132_X_133_X, "1-ff00:0:133", IF_133_X_132_X, false),
            new EdgeDesc("2-ff00:0:210", IF_210_X_220_X, "2-ff00:0:220", IF_220_X_210_X, false),
            new EdgeDesc("2-ff00:0:210", IF_210_X_211_A, "2-ff00:0:211", IF_211_A_210_X, false),
            new EdgeDesc("2-ff00:0:210", IF_210_X1_211_A, "2-ff00:0:211", IF_211_A_210_X1, false),
            new EdgeDesc("2-ff00:0:220", IF_220_X_221_X, "2-ff00:0:221", IF_221_X_220_X, false),
            new EdgeDesc("2-ff00:0:211", IF_211_A_221_X, "2-ff00:0:221", IF_221_X_211_A, true),
            new EdgeDesc("2-ff00:0:211", IF_211_A_212_X, "2-ff00:0:212", IF_212_X_211_A, false),
            new EdgeDesc("2-ff00:0:211", IF_211_A1_212_X, "2-ff00:0:212", IF_212_X_211_A1, false),
            new EdgeDesc("2-ff00:0:211", IF_211_A_222_X, "2-ff00:0:222", IF_222_X_211_A, false),
            new EdgeDesc("2-ff00:0:221", IF_221_X_222_X, "2-ff00:0:222", IF_222_X_221_X, false),
          });
}
