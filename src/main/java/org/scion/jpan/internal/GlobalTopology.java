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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;

/** Parse a topology file into a local topology. */
public class GlobalTopology {

  private final Map<Integer, Isd> world = new HashMap<>();

  public static synchronized GlobalTopology create(ScionBootstrapper server) throws IOException {
    GlobalTopology topo = new GlobalTopology();
    topo.getTrcFiles(server);
    return topo;
  }

  private static JsonElement safeGet(JsonObject o, String name) {
    JsonElement e = o.get(name);
    if (e == null) {
      throw new ScionRuntimeException("Entry not found in topology file: " + name);
    }
    return e;
  }

  @Deprecated // TODO do we need this? remove it?
  public static GlobalTopology createEmpty() {
    return new GlobalTopology();
  }

  public boolean isCoreAs(long isdAs) {
    int isdCode = ScionUtil.extractIsd(isdAs);
    Isd isd = world.get(isdCode);
    if (isd == null) {
      throw new ScionRuntimeException("Unknown ISD: " + isdCode);
    }

    for (Long core : isd.coreASes) {
      if (core == isdAs) {
        return true;
      }
    }
    return false;
  }

  private void getTrcFiles(ScionBootstrapper server) throws IOException {
    String filesString = server.fetchFile("trcs");
    parseTrcFiles(filesString);

    for (Isd isd : world.values()) {
      String fileName = "isd" + isd.isd + "-b" + isd.baseNumber + "-s" + isd.serialNumber;
      String file = server.fetchFile("trcs/" + fileName);
      parseTrcFile(file, isd);
    }
  }

  private void parseTrcFiles(String trcFile) {
    JsonElement jsonTree = JsonParser.parseString(trcFile);
    JsonArray entries = jsonTree.getAsJsonArray();
    for (int i = 0; i < entries.size(); i++) {
      JsonObject entry = entries.get(i).getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : entry.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        int base = cs.get("base_number").getAsInt();
        int isd = cs.get("isd").getAsInt();
        int serial = cs.get("serial_number").getAsInt();
        world.put(isd, new Isd(isd, base, serial));
      }
    }
  }

  private static void parseTrcFile(String trcFile, Isd isd) {
    JsonElement jsonTree = JsonParser.parseString(trcFile);
    if (jsonTree.isJsonObject()) {
      JsonObject o = jsonTree.getAsJsonObject();

      JsonArray authoritativeAses = safeGet(o, "authoritative_ases").getAsJsonArray();
      for (int i = 0; i < authoritativeAses.size(); i++) {
        String isdCode = authoritativeAses.get(i).getAsString();
        isd.authorativeASes.add(ScionUtil.parseIA(isdCode));
      }
      JsonArray coreAses = safeGet(o, "core_ases").getAsJsonArray();
      for (int i = 0; i < coreAses.size(); i++) {
        String isdCode = coreAses.get(i).getAsString();
        isd.coreASes.add(ScionUtil.parseIA(isdCode));
      }

      isd.description = safeGet(o, "description").getAsString();

      JsonObject id = safeGet(o, "id").getAsJsonObject();
      int base = id.get("base_number").getAsInt();
      int isdCode = id.get("isd").getAsInt();
      int serial = id.get("serial_number").getAsInt();
      if (isd.isd != isdCode || isd.baseNumber != base || isd.serialNumber != serial) {
        throw new IllegalStateException("ISD/Base/Serial mismatch in TRC file.");
      }

      JsonObject validity = safeGet(o, "validity").getAsJsonObject();
      String afterStr = validity.get("not_after").getAsString();
      String beforeStr = validity.get("not_before").getAsString();
      isd.notAfter = Instant.parse(afterStr);
      isd.notBefore = Instant.parse(beforeStr);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (Isd isd : world.values()) {
      sb.append("ISD:   ").append(isd).append('\n');
    }
    return sb.toString();
  }

  private static class Isd {
    String description;
    int baseNumber;
    int isd;
    int serialNumber;
    Instant notAfter;
    Instant notBefore;
    final List<Long> authorativeASes = new ArrayList<>();
    final List<Long> coreASes = new ArrayList<>();

    Isd(int isd, int base, int serial) {
      this.isd = isd;
      this.baseNumber = base;
      this.serialNumber = serial;
    }

    @Override
    public String toString() {
      return "{"
          + "description='"
          + description
          + '\''
          + ", baseNumber="
          + baseNumber
          + ", isd="
          + isd
          + ", serialNumber="
          + serialNumber
          + ", notAfter="
          + notAfter
          + ", notBefore="
          + notBefore
          + ", authorativeASes="
          + authorativeASes
          + ", coreASes="
          + coreASes
          + '}';
    }
  }
}
