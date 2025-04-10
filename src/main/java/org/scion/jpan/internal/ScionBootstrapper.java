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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.scion.jpan.ScionException;
import org.scion.jpan.ScionRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ScionBootstrapper tries to find the address of the control server.
 *
 * <p>It currently supports: - DNS lookup NAPTR with A/AAAA and TXT for port information.
 */
public class ScionBootstrapper {

  private static final Logger LOG = LoggerFactory.getLogger(ScionBootstrapper.class.getName());
  private static final String TOPOLOGY_ENDPOINT = "topology";
  private static final Duration httpRequestTimeout = Duration.of(2, ChronoUnit.SECONDS);
  private final String topologyResource;
  private final LocalTopology localAS;
  private final GlobalTopology world;

  protected ScionBootstrapper(String topologyServiceAddress) {
    this.topologyResource = IPHelper.ensurePortOrDefault(topologyServiceAddress, 8041);
    this.localAS = initLocal();
    this.world = initGlobal();
  }

  protected ScionBootstrapper(java.nio.file.Path file) {
    this.topologyResource = file.toString();
    this.localAS = this.init(file);
    this.world = GlobalTopology.createEmpty();
  }

  protected ScionBootstrapper(DaemonServiceGrpc daemonService) {
    this.topologyResource = null;
    this.localAS = LocalTopology.create(daemonService);
    this.world = GlobalTopology.createEmpty();
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

  public static synchronized ScionBootstrapper createViaDaemon(DaemonServiceGrpc daemonService) {
    return new ScionBootstrapper(daemonService);
  }

  public LocalTopology getLocalTopology() {
    return localAS;
  }

  public GlobalTopology getGlobalTopology() {
    return world;
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

  private LocalTopology initLocal() {
    return LocalTopology.create(fetchFile(TOPOLOGY_ENDPOINT));
  }

  private GlobalTopology initGlobal() {
    return GlobalTopology.create(this);
  }

  private LocalTopology init(java.nio.file.Path file) {
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
      throw new ScionRuntimeException("Error reading topology file: " + file.toAbsolutePath(), e);
    }
    LocalTopology topo = LocalTopology.create(contentBuilder.toString());
    if (topo.getControlServices().isEmpty()) {
      throw new ScionRuntimeException("No control service found in topology file: " + file);
    }
    return topo;
  }

  public void refreshTopology() {
    // TODO check timeout from NAPTR record
    // TODO verify local DNS?? How?
    // init();
    throw new UnsupportedOperationException();
  }

  public String fetchFile(String resource) {
    try {
      LOG.info("Fetching resource from bootstrap server: {} {}", topologyResource, resource);
      URL url = new URL("http://" + topologyResource + "/" + resource);
      return fetchFile(url);
    } catch (IOException e) {
      throw new ScionRuntimeException(
          "While fetching resource '" + resource + "' from " + topologyResource, e);
    }
  }

  private static String fetchFile(URL url) throws IOException {
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
          response.append(inputLine).append(System.lineSeparator());
        }
        return response.toString();
      }
    } finally {
      if (httpURLConnection != null) {
        httpURLConnection.disconnect();
      }
    }
  }
}
