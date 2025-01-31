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

package org.scion.jpan.ppl;

import java.util.Arrays;
import java.util.Objects;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.ScionUtil;

// Copied from https://github.com/scionproto/scion/tree/master/private/path/pathpol
class HopPredicate {

  // A HopPredicate specifies a hop in the ACL or Sequence of the path policy,
  // see docs/PathPolicy.md.
  private int isd;
  private long as;
  private int[] ifIDs;

  static HopPredicate create(int isd, long as, int[] ifIDs) {
    return new HopPredicate(isd, as, ifIDs);
  }

  private HopPredicate(int isd, int[] ifIDs) {
    this.isd = isd;
    this.ifIDs = ifIDs;
  }

  private HopPredicate(int isd, long as, int[] ifIDs) {
    this.isd = isd;
    this.as = as;
    this.ifIDs = ifIDs;
  }

  static HopPredicate HopPredicateFromString(String str) {
    validateHopPredStr(str);

    int[] ifIDs = new int[1];
    // Parse ISD
    String[] dashParts = str.split("-");
    int isd;
    try {
      isd = ScionUtil.parseISD(dashParts[0]);
    } catch (Exception e) {
      throw new PplException("Failed to parse ISD: " + "value=" + str, e);
    }
    if (dashParts.length == 1) {
      return new HopPredicate(isd, ifIDs);
    }
    // Parse AS if present
    String[] hashParts = dashParts[1].split("#");
    long as;
    try {
      as = ScionUtil.parseAS(hashParts[0]);
    } catch (Exception e) {
      throw new PplException("Failed to parse AS: " + "value=" + str, e);
    }
    if (hashParts.length == 1) {
      return new HopPredicate(isd, as, ifIDs);
    }
    // Parse IfIDs if present
    String[] commaParts = hashParts[1].split(",");
    try {
      ifIDs[0] = parseIfID(commaParts[0]);
    } catch (Exception e) {
      throw new PplException("Failed to parse ifIDs: " + "value=" + str, e);
    }
    if (commaParts.length == 2) {
      try {
        int ifID = parseIfID(commaParts[1]);
        ifIDs = Arrays.copyOf(ifIDs, ifIDs.length + 1);
        ifIDs[ifIDs.length - 1] = ifID;
      } catch (Exception e) {
        throw new PplException("Failed to parse ifIDs: " + "value=" + str, e);
      }
    }
    // IfID cannot be set when the AS is a wildcard
    if (as == 0 && (ifIDs[0] != 0 || (ifIDs.length > 1 && ifIDs[1] != 0))) {
      throw new PplException("Failed to parse hop predicate, IfIDs must be 0: " + "value=" + str);
    }
    return new HopPredicate(isd, as, ifIDs);
  }

  // pathIFMatch takes a PathInterface and a bool indicating if the ingress
  // interface needs to be matching. It returns true if the HopPredicate matches the PathInterface
  boolean pathIFMatch(PathMetadata.PathInterface pi, boolean in) {
    if (isd != 0 && ScionUtil.extractIsd(pi.getIsdAs()) != isd) {
      return false;
    }
    if (as != 0 && ScionUtil.extractAs(pi.getIsdAs()) != as) {
      return false;
    }
    int ifInd = 0;
    // the IF index is set to 1 if
    // - there are two IFIDs and
    // - the ingress interface should not be matched
    if (ifIDs.length == 2 && !in) {
      ifInd = 1;
    }
    return ifIDs[ifInd] == 0 || ifIDs[ifInd] == pi.getId();
  }

  boolean matchesAll() {
    if (this == null) {
      return true; // TODO true !!!!???
    }
    // hp.AS == 0 implies that there is exactly one 0 interface.
    return isd == 0 && as == 0;
  }

  String string() {
    StringBuilder sb = new StringBuilder();
    for (int ifID : ifIDs) {
      if (sb.length() > 0) {
        sb.append(",");
      }
      sb.append(ifID);
    }
    return String.format("%d-%s#%s", isd, as, sb);
  }

  //    JsonWriter MarshalJSON(JsonWriter json) {
  //        return json.Marshal(string());
  //    }
  //
  //    JsonReader UnmarshalJSON(JsonReader json) {
  //        String str;
  //        json.Unmarshal(b, str);
  //        nhp = HopPredicateFromString(str);
  //                hp = nhp;
  //        return json;
  //    }

  private static int parseIfID(String str) {
    int ifID = Integer.parseUnsignedInt(str, 10);
    return ifID;
  }

  // validateHopPredStr checks if str has the correct amount of delimiters
  private static void validateHopPredStr(String str) {
    int dashes = count(str, '-');
    int hashes = count(str, '#');
    int commas = count(str, ',');
    if (dashes > 1 || hashes > 1 || commas > 1) {
      throw new PplException(
          "Failed to parse hop predicate, found delimiter too often: "
              + "dashes="
              + dashes
              + "; hashes="
              + hashes
              + "; commas="
              + commas);
    }
    if (dashes == 0 && (hashes > 0 || commas > 0)) {
      throw new PplException("Can't specify IFIDs without AS");
    }
  }

  private static int count(String str, char c) {
    int n = 0;
    for (int i = 0; i < str.length(); i++) {
      n += str.charAt(i) == c ? 1 : 0;
    }
    return n;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    HopPredicate that = (HopPredicate) o;
    return isd == that.isd && as == that.as && Objects.deepEquals(ifIDs, that.ifIDs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(isd, as, Arrays.hashCode(ifIDs));
  }

  @Override
  public String toString() {
    return "HopPredicate{"
        + "isd="
        + isd
        + ", as="
        + as
        + ", ifIDs="
        + Arrays.toString(ifIDs)
        + '}';
  }
}
