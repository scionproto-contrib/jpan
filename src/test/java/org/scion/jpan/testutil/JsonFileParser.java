// Copyright 2024 ETH Zurich
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

package org.scion.jpan.testutil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;

public class JsonFileParser {

  public static String readFile(java.nio.file.Path file) {
    file = toResourcePath(file);
    StringBuilder contentBuilder = new StringBuilder();
    try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
      stream.forEach(s -> contentBuilder.append(s).append("\n"));
    } catch (IOException e) {
      throw new ScionRuntimeException(
          "Error reading topology file found at: " + file.toAbsolutePath());
    }
    return contentBuilder.toString();
  }

  public static Path toResourcePath(Path file) {
    if (file == null) {
      return null;
    }
    try {
      ClassLoader classLoader = JsonFileParser.class.getClassLoader();
      URL resource = classLoader.getResource(file.toString());
      if (resource != null) {
        return Paths.get(resource.toURI());
      }
      throw new IllegalArgumentException("Resource not found: " + file);
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public static Path toResourcePath(String fileName) {
    if (fileName == null) {
      return null;
    }
    return toResourcePath(Paths.get(fileName));
  }

  public static AsInfo parseTopologyFile(Path path) {
    String fileStr = readFile(path);
    AsInfo as = new AsInfo();
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(fileStr);
    if (jsonTree.isJsonObject()) {
      JsonObject o = jsonTree.getAsJsonObject();
      as.setIsdAs(ScionUtil.parseIA(safeGet(o, "isd_as").getAsString()));
      // localMtu = safeGet(o, "mtu").getAsInt();
      JsonObject brs = safeGet(o, "border_routers").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : brs.entrySet()) {
        JsonObject br = e.getValue().getAsJsonObject();
        String addr = safeGet(br, "internal_addr").getAsString();
        JsonObject ints = safeGet(br, "interfaces").getAsJsonObject();
        List<AsInfo.BorderRouterInterface> interfaces = new ArrayList<>();
        for (Map.Entry<String, JsonElement> ifEntry : ints.entrySet()) {
          JsonObject ife = ifEntry.getValue().getAsJsonObject();
          // TODO bandwidth, mtu, ... etc
          JsonObject underlay = ife.getAsJsonObject("underlay");
          interfaces.add(
              new AsInfo.BorderRouterInterface(
                  ifEntry.getKey(),
                  ife.get("isd_as").getAsString(),
                  underlay.get("public").getAsString(),
                  underlay.get("remote").getAsString()));
        }
        as.add(new AsInfo.BorderRouter(e.getKey(), addr, interfaces));
      }
      JsonObject css = safeGet(o, "control_service").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : css.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        as.setControlServer(cs.get("addr").getAsString());
        // controlServices.add(
        //     new ScionBootstrapper.ServiceNode(e.getKey(), cs.get("addr").getAsString()));
      }
    }
    return as;
  }

  private static JsonElement safeGet(JsonObject o, String name) {
    JsonElement e = o.get(name);
    if (e == null) {
      throw new ScionRuntimeException("Entry not found in topology file: " + name);
    }
    return e;
  }
}
