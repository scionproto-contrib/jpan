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

package org.scion.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.scion.Scion;
import org.scion.ScionException;
import org.scion.ScionRuntimeException;
import org.scion.ScionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ScionBootstrapper tries to find the address of the control server.
 *
 * <p>It currently supports: - DNS lookup NAPTR with A/AAAA and TXT for port information.
 *
 * @see Scion.CloseableService
 */
public class ScionBootstrapper {

  private static final Logger LOG = LoggerFactory.getLogger(ScionBootstrapper.class.getName());
  private static final String BASE_URL = "";
  private static final String TOPOLOGY_ENDPOINT = "topology";
  private static final Duration httpRequestTimeout = Duration.of(2, ChronoUnit.SECONDS);
  private final String topologyResource;
  private final List<ServiceNode> controlServices = new ArrayList<>();
  private final List<ServiceNode> discoveryServices = new ArrayList<>();
  private final List<BorderRouter> borderRouters = new ArrayList<>();
  private String localIsdAs;
  private boolean isCoreAs;
  private int localMtu;

  protected ScionBootstrapper(String topologyServiceAddress) {
    this.topologyResource = topologyServiceAddress;
    init();
  }

  protected ScionBootstrapper(java.nio.file.Path file) {
    this.topologyResource = file.toString();
    init(file);
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * @return default instance
   */
  public static synchronized ScionBootstrapper createViaDns(String host) {
    return new ScionBootstrapper(bootstrapViaDNS(host));
  }

  public static synchronized ScionBootstrapper createViaBootstrapServerIP(String hostAndPort) {
    return new ScionBootstrapper(hostAndPort);
  }

  public static synchronized ScionBootstrapper createViaTopoFile(java.nio.file.Path file) {
    return new ScionBootstrapper(file);
  }

  private static String bootstrapViaDNS(String hostName) {
    try {
      String addr = DNSHelper.getScionDiscoveryAddress(hostName);
      if (addr == null) {
        throw new ScionRuntimeException("No valid DNS NAPTR entry found for host: " + hostName);
      }
      return addr;
    } catch (IOException e) {
      throw new ScionRuntimeException("Error while bootstrapping Scion via DNS: " + e.getMessage());
    }
  }

  private static JsonElement safeGet(JsonObject o, String name) {
    JsonElement e = o.get(name);
    if (e == null) {
      throw new ScionRuntimeException("Entry not found in topology file: " + name);
    }
    return e;
  }

  private static String fetchTopologyFile(URL url) throws IOException {
    HttpURLConnection httpURLConnection = null;
    try {
      httpURLConnection = (HttpURLConnection) url.openConnection();
      httpURLConnection.setRequestMethod("GET");
      httpURLConnection.setConnectTimeout((int) httpRequestTimeout.toMillis());

      int responseCode = httpURLConnection.getResponseCode();
      if (responseCode != HttpURLConnection.HTTP_OK) { // success
        throw new ScionException(
            "GET request failed (" + responseCode + ") on topology server: " + url);
      }

      try (BufferedReader in =
          new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream()))) {
        StringBuilder response = new StringBuilder();
        String inputLine;
        while ((inputLine = in.readLine()) != null) {
          response.append(inputLine); // .append(System.lineSeparator());
        }
        return response.toString();
      }
    } finally {
      if (httpURLConnection != null) {
        httpURLConnection.disconnect();
      }
    }
  }

  private void init() {
    try {
      parseTopologyFile(getTopologyFile());
    } catch (IOException e) {
      throw new ScionRuntimeException(
          "Error while getting topology file from " + topologyResource + ": " + e.getMessage(), e);
    }
    if (controlServices.isEmpty()) {
      throw new ScionRuntimeException(
          "No control servers found in topology provided by " + topologyResource);
    }
  }

  private void init(java.nio.file.Path file) {
    try {
      if (!Files.exists(file)) {
        // fallback, try resource folder
        ClassLoader classLoader = getClass().getClassLoader();
        URL resource = classLoader.getResource(file.toString());
        if (resource != null) {
          file = Paths.get(resource.toURI());
        }
      }
    } catch (URISyntaxException e) {
      throw new ScionRuntimeException(e);
    }

    StringBuilder contentBuilder = new StringBuilder();
    try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
      stream.forEach(s -> contentBuilder.append(s).append("\n"));
    } catch (IOException e) {
      throw new ScionRuntimeException(
          "Error reading topology file found at: " + file.toAbsolutePath());
    }
    parseTopologyFile(contentBuilder.toString());
    if (controlServices.isEmpty()) {
      throw new ScionRuntimeException("No control service found in topology filet: " + file);
    }
  }

  public String getControlServerAddress() {
    return controlServices.get(0).ipString;
  }

  public boolean isLocalAsCore() {
    return isCoreAs;
  }

  public long getLocalIsdAs() {
    return ScionUtil.parseIA(localIsdAs);
  }

  public String getBorderRouterAddress(int interfaceId) {
    for (BorderRouter br : borderRouters) {
      for (BorderRouterInterface brif : br.interfaces) {
        if (brif.id == interfaceId) {
          return br.internalAddress;
        }
      }
    }
    throw new ScionRuntimeException("No router found with interface ID " + interfaceId);
  }

  public int getLocalMtu() {
    return this.localMtu;
  }

  public void refreshTopology() {
    // TODO check timeout from NAPTR record
    // TODO verify local DNS?? How?
    // init();
    throw new UnsupportedOperationException();
  }

  private String getTopologyFile() throws IOException {
    LOG.info("Getting topology from bootstrap server: {}", topologyResource);
    controlServices.clear();
    discoveryServices.clear();
    // TODO https????
    URL url = new URL("http://" + topologyResource + "/" + BASE_URL + TOPOLOGY_ENDPOINT);
    return fetchTopologyFile(url);
  }

  private void parseTopologyFile(String topologyFile) {
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(topologyFile);
    if (jsonTree.isJsonObject()) {
      JsonObject o = jsonTree.getAsJsonObject();
      localIsdAs = safeGet(o, "isd_as").getAsString();
      localMtu = safeGet(o, "mtu").getAsInt();
      JsonObject brs = safeGet(o, "border_routers").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : brs.entrySet()) {
        JsonObject br = e.getValue().getAsJsonObject();
        String addr = safeGet(br, "internal_addr").getAsString();
        JsonObject ints = safeGet(br, "interfaces").getAsJsonObject();
        List<BorderRouterInterface> interfaces = new ArrayList<>();
        for (Map.Entry<String, JsonElement> ifEntry : ints.entrySet()) {
          JsonObject ife = ifEntry.getValue().getAsJsonObject();
          // TODO bandwidth, mtu, ... etc
          JsonObject underlay = ife.getAsJsonObject("underlay");
          interfaces.add(
              new BorderRouterInterface(
                  ifEntry.getKey(),
                  underlay.get("public").getAsString(),
                  underlay.get("remote").getAsString()));
        }
        borderRouters.add(new BorderRouter(e.getKey(), addr, interfaces));
      }
      JsonObject css = safeGet(o, "control_service").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : css.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        controlServices.add(new ServiceNode(e.getKey(), cs.get("addr").getAsString()));
      }
      JsonObject dss = safeGet(o, "discovery_service").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : dss.entrySet()) {
        JsonObject ds = e.getValue().getAsJsonObject();
        discoveryServices.add(new ServiceNode(e.getKey(), ds.get("addr").getAsString()));
      }
      JsonArray attr = safeGet(o, "attributes").getAsJsonArray();
      for (int i = 0; i < attr.size(); i++) {
        if ("core".equals(attr.get(i).getAsString())) {
          isCoreAs = true;
        }
      }
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Topo Server: ").append(topologyResource).append("\n");
    sb.append("ISD/AS: ").append(localIsdAs).append('\n');
    sb.append("Core: ").append(isCoreAs).append('\n');
    sb.append("MTU: ").append(localMtu).append('\n');
    for (ServiceNode sn : controlServices) {
      sb.append("Control server:   ").append(sn).append('\n');
    }
    for (ServiceNode sn : discoveryServices) {
      sb.append("Discovery server: ").append(sn).append('\n');
    }
    return sb.toString();
  }

  private static class BorderRouter {
    private final String name;
    private final String internalAddress;
    private final List<BorderRouterInterface> interfaces;

    public BorderRouter(String name, String addr, List<BorderRouterInterface> interfaces) {
      this.name = name;
      this.internalAddress = addr;
      this.interfaces = interfaces;
    }
  }

  private static class BorderRouterInterface {
    final int id;
    final String publicUnderlay;
    final String remoteUnderlay;

    public BorderRouterInterface(String id, String publicU, String remoteU) {
      this.id = Integer.parseInt(id);
      this.publicUnderlay = publicU;
      this.remoteUnderlay = remoteU;
    }
  }

  private static class ServiceNode {
    final String name;
    final String ipString;

    ServiceNode(String name, String ipString) {
      this.name = name;
      this.ipString = ipString;
    }

    @Override
    public String toString() {
      return "{" + "name='" + name + '\'' + ", ipString='" + ipString + '\'' + '}';
    }
  }
}
