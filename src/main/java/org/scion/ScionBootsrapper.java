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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
class ScionBootsrapper {

  private static final Logger LOG = LoggerFactory.getLogger(ScionBootsrapper.class.getName());
  private static final String STR_X_SCION = "x-sciondiscovery";
  private static final String STR_X_SCION_TCP = "x-sciondiscovery:tcp";
  private static ScionBootsrapper DEFAULT;
  private final InetSocketAddress topologyService;
  private String localIsdAs;
  private int localMtu;

  private final List<ServiceNode> controlServices = new ArrayList<>();
  private final List<ServiceNode> discoveryServices = new ArrayList<>();

  private static final String baseURL = "";
  private static final String topologyEndpoint = "topology";
  private static final String signedTopologyEndpoint = "topology.signed";
  private static final String trcsEndpoint = "trcs";
  private static final String trcBlobEndpoint = "trcs/isd%d-b%d-s%d/blob";
  private static final String topologyJSONFileName = "topology.json";
  private static final String signedTopologyFileName = "topology.signed";
  private static final Duration httpRequestTimeout = Duration.of(2, ChronoUnit.SECONDS);

  public long getLocalIsdAs() {
    return ScionUtil.parseIA(localIsdAs);
  }

  private static class ServiceNode {
    final String name;
    final String ipString;

    ServiceNode(String name, String ipString) {
      this.name = name;
      this.ipString = ipString;
    }
  }

  protected ScionBootsrapper(InetSocketAddress topologyService) {
    if (topologyService == null) {
      throw new IllegalArgumentException("Topology service address is null.");
    }
    this.topologyService = topologyService;
    LOG.info("Bootstrapper service started on " + topologyService);
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * @return default instance
   */
  static synchronized ScionBootsrapper createServiceViaDns(String host) {
    if (DEFAULT == null) {
      InetSocketAddress topoAddress = bootstrapViaDNS(host);
      DEFAULT = new ScionBootsrapper(topoAddress);
    }
    return DEFAULT;
  }

  //  static synchronized ScionBootsrapper createServiceViaBootstrapServerIP(String host) {
  //    if (DEFAULT == null) {
  //      InetSocketAddress topoAddress = new InetSocketAddress(host);
  //      DEFAULT = new ScionBootsrapper(topoAddress);
  //    }
  //    return DEFAULT;
  //  }

  private static InetSocketAddress bootstrapViaDNS(String hostName) {
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
            String flag = naptrFlag;
            InetAddress addr = queryTopoServerDNS(flag, host);
            return new InetSocketAddress(addr, port);
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
        return ar.getAddress();
      }
    } else if ("AAAA".equals(flag)) {
      Record[] recordsA = new Lookup(topoHost, Type.AAAA).run();
      if (recordsA == null) {
        throw new ScionRuntimeException("No DNS AAAA entry found for host: " + topoHost);
      }
      for (int i = 0; i < recordsA.length; i++) {
        ARecord ar = (ARecord) recordsA[i];
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

  public String getControlServerAddress() {
    try {
      getTopology(topologyService);
    } catch (IOException e) {
      throw new ScionRuntimeException("Error while getting topologyu file: " + e.getMessage(), e);
    }
    if (controlServices.isEmpty()) {
      throw new ScionRuntimeException(
          "No control servers found in topology provided by " + topologyService);
    }

    System.out.println("Topology: ISD/AS=" + localIsdAs + "  mtu=" + localMtu);
    for (ServiceNode cs : controlServices) {
      System.out.println("Control service: " + cs.name + "   " + cs.ipString);
    }
    for (ServiceNode cs : discoveryServices) {
      System.out.println("Discovery service: " + cs.name + "   " + cs.ipString);
    }
    return controlServices.get(0).ipString;
    // return discoveryServices.get(0).ipString;
  }

  public void refreshTopology() {
    // TODO check timeout from dig netsec-w37w3w.inf.ethz.ch?
    // TODO verify local DNS?? How?
    throw new UnsupportedOperationException();
  }

  private void getTopology(InetSocketAddress addr) throws IOException {
    controlServices.clear();
    discoveryServices.clear();
    URL url = buildTopologyURL(addr);
    String topologyFile = fetchTopologyFile(url);

    ObjectMapper om = new ObjectMapper();
    JsonParser p = om.reader().createParser(topologyFile);
    p.nextToken();
    int depth = 0;
    while (p.hasCurrentToken()) {
      JsonToken t = p.currentToken();
      if ("control_service".equals(p.currentName()) && t.isStructStart()) {
        p.nextToken();
        while (!"control_service".equals(p.currentName())) {
          controlServices.add(readControlServer(p));
        }
      } else if ("discovery_service".equals(p.currentName()) && t.isStructStart()) {
        p.nextToken();
        while (!"discovery_service".equals(p.currentName())) {
          discoveryServices.add(readControlServer(p));
        }
      } else if ("isd_as".equals(p.currentName()) && depth == 1) {
        p.nextToken();
        assertString("isd_as", p.currentName());
        localIsdAs = p.getValueAsString();
      } else if ("mtu".equals(p.currentName()) && depth == 1) {
        p.nextToken();
        assertString("mtu", p.currentName());
        localMtu = p.getIntValue();
      } else if (t.isStructStart()) {
        depth++;
      } else if (t.isStructEnd()) {
        depth--;
      }
      p.nextToken();
    }
  }

  private static ServiceNode readControlServer(JsonParser p) throws IOException {
    //  "control_service": {
    //    "cs64-2_0_9-1": {
    //      "addr": "192.168.53.20:30252"
    //    },
    //    "cs64-2_0_9-2": {
    //      "addr": "192.168.53.35:30252"
    //    }
    //  },

    // CS name
    String csName = p.getValueAsString();
    p.nextToken();
    assertString(csName, p.currentName());
    assertString("{", p.getText());
    p.nextToken();

    // CS address
    assertString("addr", p.currentName());
    assertString("addr", p.getValueAsString());
    p.nextToken();
    String csAddress = p.getValueAsString();
    assertString("addr", p.currentName());
    p.nextToken();

    // end
    assertString(csName, p.currentName());
    assertString("}", p.getText());
    p.nextToken();
    return new ServiceNode(csName, csAddress);
  }

  private static void assertString(String s1, String s2) {
    if (!s1.equals(s2)) {
      throw new IllegalStateException("Expected \"" + s1 + "\" but got \"" + s2 + "\"");
    }
  }

  private static URL buildTopologyURL(InetSocketAddress addr) throws MalformedURLException {
    String urlPath = baseURL + topologyEndpoint;
    String s =
        "http://" + addr.getAddress().getHostAddress() + ":" + addr.getPort() + "/" + urlPath;
    return new URL(s);
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
}
