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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scion.jpan.ScionException;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parse a topology file into a local topology. */
public class GlobalTopology {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalTopology.class.getName());
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
    //LOG.info("Getting trc folder from bootstrap server: {}", topologyResource);
    // TODO https????
    String TRC_ENDPOINT = "trcs";
    //URL url = new URL("http://" + topologyResource + "/" + BASE_URL + TRC_ENDPOINT);
    String filesString = server.fetchFile("trcs");
    Map<Integer, String> files = parseTrcFiles(filesString);

    for (String fileName : files.values()) {
      //LOG.info("Getting trc file from bootstrap server: {} {}", topologyResource, fileName);
      // TODO https????
      //String TRC_ENDPOINT = "trcs";
      //URL url2 = new URL("http://" + topologyResource + "/" + BASE_URL + TRC_ENDPOINT + "/" + fileName);
      String file = server.fetchFile("trcs/" + fileName);
      parseTrcFile(file);
    }
  }

  private static Map<Integer, String> parseTrcFiles(String trcFile) {
    Map<Integer, String> files = new HashMap<>();
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(trcFile);

    JsonArray entries = jsonTree.getAsJsonArray();
    for (int i = 0; i < entries.size(); i++) {
      JsonObject entry = entries.get(i).getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : entry.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        int base = cs.get("base_number").getAsInt();
        int isd = cs.get("isd").getAsInt();
        int serial = cs.get("serial_number").getAsInt();
        System.out.println("entry: " + e.getKey() + "  " + base + " " + isd + " " + serial);
        String file = "isd"+ isd + "-b" + base + "-s" + serial;
        files.put(isd, file);
      }
    }
    return files;
  }

  private static Map<Integer, String> parseTrcFile(String trcFile) {
    System.out.println("File: " + trcFile);
    Map<Integer, String> files = new HashMap<>();
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(trcFile);

    JsonArray entries = jsonTree.getAsJsonArray();
    for (int i = 0; i < entries.size(); i++) {
      JsonObject entry = entries.get(i).getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : entry.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        int base = cs.get("base_number").getAsInt();
        int isd = cs.get("isd").getAsInt();
        int serial = cs.get("serial_number").getAsInt();
        System.out.println("entry: " + e.getKey() + "  " + base + " " + isd + " " + serial);
        String file = "isd"+ isd + "-b" + base + "-s" + serial;
        files.put(isd, file);
      }
    }
    return files;
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
    LocalDateTime notAfter;
    LocalDateTime notBefore;
    final List<Long> authorativeASes = new ArrayList<>();
    final List<Long> coreASes = new ArrayList<>();

    Isd(int isd, int base, int serial) {
      this.isd = isd;
      this.baseNumber = base;
      this.serialNumber = serial;
    }

    @Override
    public String toString() {
      return "{" +
              "description='" + description + '\'' +
              ", baseNumber=" + baseNumber +
              ", isd=" + isd +
              ", serialNumber=" + serialNumber +
              ", notAfter=" + notAfter +
              ", notBefore=" + notBefore +
              ", authorativeASes=" + authorativeASes +
              ", coreASes=" + coreASes +
              '}';
    }
  }
}
