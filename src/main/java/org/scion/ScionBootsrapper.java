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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.net.*;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
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

  private static final String STR_X_SCION = "x-sciondiscovery";
  private static final String STR_X_SCION_TCP = "x-sciondiscovery:tcp";
  private static final Logger LOG = LoggerFactory.getLogger(ScionBootsrapper.class.getName());
  private static ScionBootsrapper DEFAULT;
  private final InetSocketAddress topologyService;

  private static final String baseURL = "";
  private static final String topologyEndpoint = "topology";
  private static final String signedTopologyEndpoint = "topology.signed";
  private static final String trcsEndpoint = "trcs";
  private static final String trcBlobEndpoint = "trcs/isd%d-b%d-s%d/blob";
  private static final String topologyJSONFileName = "topology.json";
  private static final String signedTopologyFileName = "topology.signed";
  private static final Duration httpRequestTimeout = Duration.of(2, ChronoUnit.SECONDS);

  protected ScionBootsrapper(InetSocketAddress topologyService) {
    if (topologyService == null) {
      throw new IllegalArgumentException("Topology service address is null.");
    }
    this.topologyService = topologyService;
    LOG.info("Path service started on " + topologyService);
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * @return default instance
   */
  public static synchronized ScionBootsrapper defaultService(String host) throws ScionException {
    if (DEFAULT == null) {
      InetSocketAddress csAddress = bootstrapViaDNS(host);
      DEFAULT = new ScionBootsrapper(csAddress);
    }
    return DEFAULT;
  }

  @Deprecated // TODO experimental, do not use
  private static InetSocketAddress bootstrapViaDNS(String hostName) throws ScionException {
    try {
      Record[] records = new Lookup(hostName, Type.NAPTR).run();
      if (records == null) {
        throw new ScionException("No DNS NAPTR entry found for host: " + hostName);
      }
      String host = null;
      String flag = null;
      for (int i = 0; i < records.length; i++) {
        NAPTRRecord nr = (NAPTRRecord) records[i];
        String naptrService = nr.getService();
        if (STR_X_SCION_TCP.equals(naptrService)) {
          host = nr.getReplacement().toString();
          String naptrFlag = nr.getFlags();
          int port = queryTXT(hostName);
          if ("A".equals(naptrFlag) || "AAAA".equals(naptrFlag)) {
            flag = naptrFlag;
            InetAddress addr = queryTopoServerDNS(flag, host);
            return new InetSocketAddress(addr, port);
          }
          // keep going and collect more hints
        }
      }

      if (host == null) {
        return null;
      }
    } catch (TextParseException e) {
      throw new ScionException("Error parsing TXT entry: " + e.getMessage());
    }

    return null;
  }

  private static InetAddress queryTopoServerDNS(String flag, String topoHost)
      throws ScionException, TextParseException {
    if ("A".equals(flag)) {
      Record[] recordsA = new Lookup(topoHost, Type.A).run();
      if (recordsA == null) {
        throw new ScionException("No DNS A entry found for host: " + topoHost);
      }
      for (int i = 0; i < recordsA.length; i++) {
        ARecord ar = (ARecord) recordsA[i];
        return ar.getAddress();
      }
    } else if ("AAAA".equals(flag)) {
      Record[] recordsA = new Lookup(topoHost, Type.AAAA).run();
      if (recordsA == null) {
        throw new ScionException("No DNS AAAA entry found for host: " + topoHost);
      }
      for (int i = 0; i < recordsA.length; i++) {
        ARecord ar = (ARecord) recordsA[i];
        return ar.getAddress();
      }
    } else {
      throw new ScionException("Could not find bootstrap A/AAAA record");
    }
    return null;
  }

  private static int queryTXT(String hostName) throws ScionException, TextParseException {
    Record[] records = new Lookup(hostName, Type.TXT).run();
    if (records == null) {
      throw new ScionException("No DNS TXT entry found for host: " + hostName);
    }
    for (int i = 0; i < records.length; i++) {
      TXTRecord tr = (TXTRecord) records[i];
      String txtEntry = tr.rdataToString();
      if (txtEntry.startsWith("\"" + STR_X_SCION + "=")) {
        String portStr = txtEntry.substring(STR_X_SCION.length() + 2, txtEntry.length() - 1);
        int port = Integer.parseInt(portStr);
        if (port < 0 || port > 65536) {
          throw new ScionException("Error parsing TXT entry: " + txtEntry);
        }
        return port;
      }
    }
    throw new ScionException("Could not find bootstrap TXT port record");
  }

  public InetSocketAddress getControlServerAddress() throws IOException {

    getTopology(topologyService);

    return topologyService;
  }

  @Deprecated // make non-public
  public static String getTopology(InetSocketAddress addr) throws IOException {
    URL url = buildTopologyURL(addr);
    String raw = fetchRawBytes("topology", url);
    //    if err != nil {
    //      return err
    //    }

    // TODO
    // Check that the topology is valid json
    //    if (!json.Valid(raw)) {
    //      throw new ScionException("unable to parse raw bytes to JSON");
    //    }

    ObjectMapper om = new ObjectMapper();
    JsonParser p = om.reader().createParser(raw);
    p.nextToken();
    while (p.hasCurrentToken()) {
      JsonToken t = p.currentToken();
      if ("control_service".equals(p.currentName()) && t.isStructStart()) {
        t = p.nextToken();
        //t = p.nextToken();
        while (!"control_service".equals(p.currentName())) {
          ControlServer cs = readControlServer(p);
       //   System.out.println("TOKEN: " + p.currentName() + " " + t.name() + " " + t.id() + " " + t.isStructStart() + "/" + t.isStructEnd() + " " + p.getValueAsString());
          //p.nextToken();
        }
      }
//      System.out.println("TOKEN: " + p.currentName() + " " + t.name() + " " + t.id() + " " + t.isStructStart() + "/" + t.isStructEnd() + " " + t.asString() + Arrays.toString(t.asCharArray()) + p.getValueAsString());
      p.nextToken();
    }

    Gson gson = new Gson();
    //gson.fromJson(raw)

    System.out.println("JSON: " + raw);

    // TODO
    // Check that the topology is a valid SCION topology, this check is done by the topology
    // consumer
    /*_, err = topology.RWTopologyFromJSONBytes(raw)
    if err != nil {
    	return fmt.Errorf("unable to parse RWTopology from JSON bytes: %w", err)
    }*/
    //    topologyPath := filepath.Join(outputPath, topologyJSONFileName)
    //    err = os.WriteFile(topologyPath, raw, 0644)
    //    if err != nil {
    //      return fmt.Errorf("bootstrapper could not store topology: %w", err);
    //    }
    //    return nil;
    return raw;
  }

  private static class ControlServer {
    final String name;
    final String ipString;

    ControlServer(String name, String ipString) {
      this.name = name;
      this.ipString = ipString;
    }

  }

  private static ControlServer readControlServer(JsonParser p) throws IOException {
    String s1 = p.getValueAsString();
   // System.out.println("TOKEN: " + p.currentName() + " " + p.getValueAsString());
    p.nextToken();
    String s2 = p.getValueAsString();
    assertString(s1, p.currentName());
//    System.out.println("TOKEN: " + p.currentName() + " " + p.getValueAsString());
    p.nextToken();
    String s3 = p.getValueAsString();
    assertString("addr", p.currentName());
    assertString("addr", p.getValueAsString());
//    System.out.println("TOKEN: " + p.currentName() + " " + p.getValueAsString());
    p.nextToken();
    String s4 = p.getValueAsString();
    assertString("addr", p.currentName());
  //  System.out.println("TOKEN: " + p.currentName() + " " + p.getValueAsString());
    p.nextToken();
    String s5 = p.getValueAsString();
    // System.out.println("TOKEN: " + p.currentName() + " " + p.getValueAsString());
    p.nextToken();
    System.out.println(s1 + "  " + s4);
    return new ControlServer(s1, s4);
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
    System.out.println("URL= " + s);
    URL url = new URL(s);
    System.out.println("URL= " + url);
    return url;
  }

  private static String fetchRawBytes(String fileName, URL url) throws IOException {
    LOG.info("Fetching " + fileName + " url" + url);
    //    ctx, cancelF := context.WithTimeout(context.Background(), httpRequestTimeout)
    //    defer cancelF()
    String r = fetchHTTP(url);
    //    if err != nil {
    //      log.Error(fmt.Sprintf("Failed to fetch %s from %s", fileName, url), "err", err)
    //      return nil, err
    //    }
    // Close response reader and handle errors
    //    defer func() {
    //      if err := r.Close(); err != nil {
    //        log.Error(fmt.Sprintf("Error closing the body of the %s response", fileName), "err",
    // err)
    //      }
    //    }()

    return r;
    //    byte[] raw = r.getBytes();
    ////    if err != nil {
    ////      return nil, fmt.Errorf("unable to read from response body: %w", err)
    ////    }
    //    return raw;
  }

  private static String fetchHTTP2(URL url) throws IOException {
    // URL url = new URL("http://example.com");
    HttpURLConnection con = (HttpURLConnection) url.openConnection();
    con.setRequestMethod("GET");
    // con.
    return con.getResponseMessage();
  }

  private static String fetchHTTP(URL url) throws IOException {
    HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
    httpURLConnection.setRequestMethod("GET");
    // httpURLConnection.setRequestProperty("User-Agent", USER_AGENT);
    int responseCode = httpURLConnection.getResponseCode();
    System.out.println("GET Response Code :: " + responseCode);
    if (responseCode == HttpURLConnection.HTTP_OK) { // success
      BufferedReader in =
          new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream()));
      String inputLine;
      StringBuffer response = new StringBuffer();

      while ((inputLine = in.readLine()) != null) {
        response.append(inputLine);
      }
      in.close();

      // print result
      System.out.println(response);
      return response.toString();
    } else {
      System.out.println("GET request not worked");
    }

    for (int i = 1; i <= 8; i++) {
      System.out.println(
          httpURLConnection.getHeaderFieldKey(i) + " = " + httpURLConnection.getHeaderField(i));
    }
    return null;
  }
}
