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

package org.scion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

/**
 * The ScionBootstrapper tries to find the address of the control server.
 *
 * <p>It currently supports: - DNS lookup NAPTR with A/AAAA and TXT for port information.
 *
 * @see Scion.CloseableService
 */
class ScionBootstrapper {

  private static final Logger LOG = LoggerFactory.getLogger(ScionBootstrapper.class.getName());
  private static final String STR_X_SCION = "x-sciondiscovery";
  private static final String STR_X_SCION_TCP = "x-sciondiscovery:tcp";
  private static final String baseURL = "";
  private static final String topologyEndpoint = "topology";
  private static final String signedTopologyEndpoint = "topology.signed";
  private static final String trcsEndpoint = "trcs";
  private static final String trcBlobEndpoint = "trcs/isd%d-b%d-s%d/blob";
  private static final String topologyJSONFileName = "topology.json";
  private static final String signedTopologyFileName = "topology.signed";
  private static final Duration httpRequestTimeout = Duration.of(2, ChronoUnit.SECONDS);

  private static class BorderRouter {
    private final String name;
    private final List<BorderRouterInterface> interfaces;

    public BorderRouter(String name, List<BorderRouterInterface> interfaces) {
      this.name = name;
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

  private final String topologyServiceAddress;
  private String localIsdAs;
  private int localMtu;
  private final List<ServiceNode> controlServices = new ArrayList<>();
  private final List<ServiceNode> discoveryServices = new ArrayList<>();
  private final List<BorderRouter> borderRouters = new ArrayList<>();

  public long getLocalIsdAs() {
    return ScionUtil.parseIA(localIsdAs);
  }

  protected ScionBootstrapper(String topologyServiceAddress) {
    this.topologyServiceAddress = topologyServiceAddress;
    init();
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * @return default instance
   */
  static synchronized ScionBootstrapper createViaDns(String host) {
    return new ScionBootstrapper(bootstrapViaDNS(host));
  }

  static synchronized ScionBootstrapper createViaBootstrapServerIP(String hostAndPort) {
    return new ScionBootstrapper(hostAndPort);
  }

  private static String bootstrapViaDNS(String hostName) {
    try {
      Record[] records = new Lookup(hostName, Type.NAPTR).run();
      if (records == null) {
        throw new ScionRuntimeException("No DNS NAPTR entry found for host: " + hostName);
      }

      for (int i = 0; i < records.length; i++) {
        NAPTRRecord nr = (NAPTRRecord) records[i];
        String naptrService = nr.getService();
        if (STR_X_SCION_TCP.equals(naptrService)) {
          String host = nr.getReplacement().toString();
          String naptrFlag = nr.getFlags();
          int port = queryTXT(hostName);
          if ("A".equals(naptrFlag) || "AAAA".equals(naptrFlag)) {
            InetAddress addr = queryTopoServerDNS(naptrFlag, host);
            return addr.getHostAddress() + ":" + port;
          }
          // keep going and collect more hints
        }
      }
    } catch (IOException e) {
      throw new ScionRuntimeException("Error while bootstrapping Scion via DNS: " + e.getMessage());
    }

    return null;
  }

  private static InetAddress queryTopoServerDNS(String flag, String topoHost) throws IOException {
    if ("A".equals(flag)) {
      Record[] recordsA = new Lookup(topoHost, Type.A).run();
      if (recordsA == null) {
        throw new ScionRuntimeException("No DNS A entry found for host: " + topoHost);
      }
      for (int i = 0; i < recordsA.length; i++) {
        ARecord ar = (ARecord) recordsA[i];
        // TODO just return the first one for now
        return ar.getAddress();
      }
    } else if ("AAAA".equals(flag)) {
      Record[] recordsA = new Lookup(topoHost, Type.AAAA).run();
      if (recordsA == null) {
        throw new ScionRuntimeException("No DNS AAAA entry found for host: " + topoHost);
      }
      for (int i = 0; i < recordsA.length; i++) {
        ARecord ar = (ARecord) recordsA[i];
        // TODO just return the first one for now
        return ar.getAddress();
      }
    } else {
      throw new ScionRuntimeException("Could not find bootstrap A/AAAA record");
    }
    return null;
  }

  private static int queryTXT(String hostName) throws IOException {
    Record[] records = new Lookup(hostName, Type.TXT).run();
    if (records == null) {
      throw new ScionRuntimeException("No DNS TXT entry found for host: " + hostName);
    }
    for (int i = 0; i < records.length; i++) {
      TXTRecord tr = (TXTRecord) records[i];
      String txtEntry = tr.rdataToString();
      if (txtEntry.startsWith("\"" + STR_X_SCION + "=")) {
        String portStr = txtEntry.substring(STR_X_SCION.length() + 2, txtEntry.length() - 1);
        int port = Integer.parseInt(portStr);
        if (port < 0 || port > 65536) {
          throw new ScionRuntimeException("Error parsing TXT entry: " + txtEntry);
        }
        return port;
      }
    }
    throw new ScionRuntimeException("Could not find bootstrap TXT port record");
  }

  private void init() {
    try {
      parseTopologyFile(getTopologyFile());
    } catch (IOException e) {
      throw new ScionRuntimeException("Error while getting topology file: " + e.getMessage(), e);
    }
    if (controlServices.isEmpty()) {
      throw new ScionRuntimeException(
          "No control servers found in topology provided by " + topologyServiceAddress);
    }
  }

  public String getControlServerAddress() {
    return controlServices.get(0).ipString;
  }

  public String getBorderRouterAddress(int interfaceId) {
    for (BorderRouter br : borderRouters) {
      // TODO
    }
    throw new ScionRuntimeException("No router found with ID " + interfaceId);
  }

  public void refreshTopology() {
    // TODO check timeout from dig netsec-w37w3w.inf.ethz.ch?
    // TODO verify local DNS?? How?
    // init();
    throw new UnsupportedOperationException();
  }

  private String getTopologyFile() throws IOException {
    LOG.info("Getting topology from bootstrap server: " + topologyServiceAddress);
    controlServices.clear();
    discoveryServices.clear();
    // TODO https????
    URL url = new URL("http://" + topologyServiceAddress + "/" + baseURL + topologyEndpoint);
    return fetchTopologyFile(url);
  }

  private void parseTopologyFile(String topologyFile) throws IOException {
    JsonElement jsonTree = com.google.gson.JsonParser.parseString(topologyFile);
    if (jsonTree.isJsonObject()) {
      JsonObject o = jsonTree.getAsJsonObject();
      localIsdAs = o.get("isd_as").getAsString();
      localMtu = o.get("mtu").getAsInt();
      JsonObject brs = o.get("border_routers").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : brs.entrySet()) {
        JsonObject br = e.getValue().getAsJsonObject();
        JsonObject ints = br.get("interfaces").getAsJsonObject();
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
        borderRouters.add(new BorderRouter(e.getKey(), interfaces));
      }
      JsonObject css = o.get("control_service").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : css.entrySet()) {
        JsonObject cs = e.getValue().getAsJsonObject();
        controlServices.add(new ServiceNode(e.getKey(), cs.get("addr").getAsString()));
      }
      JsonObject dss = o.get("discovery_service").getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : dss.entrySet()) {
        JsonObject ds = e.getValue().getAsJsonObject();
        discoveryServices.add(new ServiceNode(e.getKey(), ds.get("addr").getAsString()));
      }
    }
  }

  private static String fetchTopologyFile(URL url) throws IOException {
    HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
    httpURLConnection.setRequestMethod("GET");
    httpURLConnection.setConnectTimeout((int) httpRequestTimeout.toMillis());

    int responseCode = httpURLConnection.getResponseCode();
    if (responseCode != HttpURLConnection.HTTP_OK) { // success
      throw new ScionException(
          "GET request failed (" + responseCode + ") on topology server: " + url);
    }

    BufferedReader in =
        new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream()));
    StringBuilder response = new StringBuilder();
    String inputLine;
    while ((inputLine = in.readLine()) != null) {
      response.append(inputLine); // .append(System.lineSeparator());
    }
    in.close();

    return response.toString();
  }

  @Override
  public String toString() {
    //    StringBuilder sb = new StringBuilder();
    //    sb.append("ScionBootstrapper{");
    //    sb.append("topologyServiceAddress='").append(topologyServiceAddress).append('\'');
    //    sb.append(", localIsdAs='").append(localIsdAs).append('\'');
    //    sb.append(", localMtu=").append(localMtu);
    //    sb.append(", controlServices={");
    //    for (ServiceNode sn : controlServices) {
    //      sb.append(sn).append(",");
    //    }
    //    sb.append('}');
    //    sb.append(", discoveryServices={");
    //    for (ServiceNode sn : discoveryServices) {
    //      sb.append(sn).append(",");
    //    }
    //    sb.append('}');
    //    return sb.toString();
    StringBuilder sb = new StringBuilder();
    sb.append("Topo Server: ").append(topologyServiceAddress).append("\n");
    sb.append("ISD/AS: ").append(localIsdAs).append('\n');
    sb.append("MTU: ").append(localMtu).append('\n');
    for (ServiceNode sn : controlServices) {
      sb.append("Control server:   ").append(sn).append('\n');
    }
    for (ServiceNode sn : discoveryServices) {
      sb.append("Discovery server: ").append(sn).append('\n');
    }
    return sb.toString();
  }
}
