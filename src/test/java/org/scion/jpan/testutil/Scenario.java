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
import com.google.gson.JsonParser;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.LocalTopology;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public class Scenario {

  private static final Map<Long, String> daemons = new HashMap<>();
  private static final Map<Long, LocalTopology> topologies = new HashMap<>();

  public static Scenario readFrom(String pathName) {
    return readFrom(Paths.get(pathName));
  }

  public static Scenario readFrom(Path pathName) {
    try {
      if (!Files.exists(pathName)) {
        // fallback, try resource folder
        ClassLoader classLoader = Scenario.class.getClassLoader();
        URL resource = classLoader.getResource(pathName.toString());
        if (resource != null) {
          pathName = Paths.get(resource.toURI());
        }
      }
      return new Scenario(pathName);
    } catch (IOException | URISyntaxException e) {
      throw new ScionRuntimeException(e);
    }
  }

  public InetSocketAddress getControlServer(long isdAs) {
    LocalTopology topo = topologies.get(isdAs);
    String addr = topo.getControlServerAddress();
    String ip = addr.substring(0, addr.indexOf(':'));
    String port = addr.substring(addr.indexOf(':') + 1);
    return new InetSocketAddress(ip, Integer.parseInt(port));
  }

  public String getDaemon(long isdAs) {
    return daemons.get(isdAs) + ":30255";
  }

  private Scenario(Path file) throws IOException {
    File parent = file.toFile();
    if (!parent.isDirectory()) {
      throw new IllegalStateException();
    }

    Path addresses = Paths.get(parent.getPath(), "sciond_addresses.json");
    parseSciondAddresses(readFile(addresses));

    Files.list(file)
        .filter(path -> path.getFileName().toString().startsWith("AS"))
        .map(path -> Paths.get(path.toString(), "topology.json"))
        .map(topoFile -> LocalTopology.create(readFile(topoFile)))
        .forEach(topo -> topologies.put(topo.getLocalIsdAs(), topo));
  }

  private static String readFile(Path file) {
    StringBuilder contentBuilder = new StringBuilder();
    try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
      stream.forEach(s -> contentBuilder.append(s).append("\n"));
    } catch (IOException e) {
      throw new ScionRuntimeException("Error reading file: " + file.toAbsolutePath(), e);
    }
    return contentBuilder.toString();
  }

  private void parseSciondAddresses(String content) {
    JsonElement jsonTree = JsonParser.parseString(content);
    JsonObject entry = jsonTree.getAsJsonObject();
    for (Map.Entry<String, JsonElement> e : entry.entrySet()) {
      daemons.put(ScionUtil.parseIA(e.getKey()), e.getValue().getAsString());
    }
  }
}
