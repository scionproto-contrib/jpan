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

package org.scion.jpan;

public final class Constants {
  public static final int SCMP_PORT = 30041;

  /**
   * @deprecated Dispatcher support will be removed soon.
   */
  @Deprecated public static final int DISPATCHER_PORT = 30041;

  public static final String PROPERTY_DAEMON = "org.scion.daemon";
  public static final String ENV_DAEMON = "SCION_DAEMON";
  public static final int DEFAULT_DAEMON_PORT = 30255;
  public static final String DEFAULT_DAEMON = "localhost:" + DEFAULT_DAEMON_PORT;

  /** Address of bootstrap server (http), e.g. 192.168.42.42 */
  public static final String PROPERTY_BOOTSTRAP_HOST = "org.scion.bootstrap.host";

  /** Address of bootstrap server (http), e.g. 192.168.42.42 */
  public static final String ENV_BOOTSTRAP_HOST = "SCION_BOOTSTRAP_HOST";

  /** Host name of DNS entry with NAPTR record for bootstrap service. */
  public static final String PROPERTY_BOOTSTRAP_NAPTR_NAME = "org.scion.bootstrap.naptr.name";

  /** Host name of DNS entry with NAPTR record for bootstrap service. */
  public static final String ENV_BOOTSTRAP_NAPTR_NAME = "SCION_BOOTSTRAP_NAPTR_NAME";

  /** path/file name for topology file. */
  public static final String PROPERTY_BOOTSTRAP_TOPO_FILE = "org.scion.bootstrap.topoFile";

  /** path/file name for topology file. */
  public static final String ENV_BOOTSTRAP_TOPO_FILE = "SCION_BOOTSTRAP_TOPO_FILE";

  /** Paths are refreshed when their expiry is less than X seconds away. */
  public static final String PROPERTY_PATH_EXPIRY_MARGIN = "org.scion.pathExpiryMargin";

  /** Paths are refreshed when their expiry is less than X seconds away. */
  public static final String ENV_PATH_EXPIRY_MARGIN = "SCION_PATH_EXPIRY_MARGIN";

  /**
   * Semicolon separated list of full paths of SCION hosts files. On Linux the default is
   * "/etc/scion/hosts".
   */
  public static final String PROPERTY_HOSTS_FILES = "org.scion.hostsFiles";

  /**
   * Semicolon separated list of full paths of SCION hosts files. On Linux the default is
   * "/etc/scion/hosts".
   */
  public static final String ENV_HOSTS_FILES = "SCION_HOSTS_FILES";

  /** Time (in seconds) before expiration at which a paths is automatically renewed. */
  public static final int DEFAULT_PATH_EXPIRY_MARGIN = 10;

  /** Enable minimization of segment requests during path construction. */
  public static final String PROPERTY_RESOLVER_MINIMIZE_REQUESTS =
      "EXPERIMENTAL_SCION_RESOLVER_MINIMIZE_REQUESTS";

  /** Enable minimization of segment requests during path construction. */
  public static final String ENV_RESOLVER_MINIMIZE_REQUESTS =
      "org.scion.resolver.experimentalMinimizeRequests";

  public static final boolean DEFAULT_RESOLVER_MINIMIZE_REQUESTS = false;

  /**
   * Disable usage of OS search domains for DNS lookup, e.g. from /etc/resolv.conf. This needs to be
   * disabled for JUnit testing.
   */
  public static final String PROPERTY_USE_OS_SEARCH_DOMAINS = "org.scion.test.useOsSearchDomains";

  public static final String ENV_USE_OS_SEARCH_DOMAINS = "SCION_USE_OS_SEARCH_DOMAINS";
  public static final boolean DEFAULT_USE_OS_SEARCH_DOMAINS = true;

  /** Provide list of DNS search domains. */
  public static final String PROPERTY_DNS_SEARCH_DOMAINS = "org.scion.dnsSearchDomains";

  public static final String ENV_DNS_SEARCH_DOMAINS = "SCION_DNS_SEARCH_DOMAINS";

  /** Run the SHIM (or not). */
  public static final String PROPERTY_SHIM = "org.scion.shim";

  public static final String ENV_SHIM = "SCION_SHIM";

  /**
   * Use STUN to detect external IP addresses.
   *
   * <p>Possible values:<br>
   * - "OFF": No STUN discovery <br>
   * - "BR": Discovery using STUN interface of border routers <br>
   * - "CUSTOM": Discovery using custom STUN server. This uses public known STUN servers unless
   * {@link #PROPERTY_NAT_STUN_SERVER} or {@link #ENV_NAT_STUN_SERVER} is set.<br>
   * - "AUTO": Use auto detection.<br>
   * // TODO is this still correct: ????
   *
   * <p>"AUTO" works as follows: <br>
   * 1) Check for custom STUN server and use if possible<br>
   * 2) Check border routers if they support STUN (timeout = 10ms)<br>
   * 3) If border router responds to traceroute/ping, do not use STUN at all<br>
   * 4) Try public stun server (optional: recheck with tr/ping, bail out if it fails)<br>
   */
  public static final String PROPERTY_NAT = "org.scion.nat";

  public static final String ENV_NAT = "SCION_NAT";
  public static final String DEFAULT_NAT = "OFF";

  /**
   * Timeout of the NAT before we expect it to forget a mapping, i.e. the time before JPAN initiates
   * a new STUN detection when reusing a stale connection.
   */
  public static final String PROPERTY_NAT_MAPPING_TIMEOUT = "org.scion.nat.mapping.timeout";

  public static final String ENV_NAT_MAPPING_TIMEOUT = "SCION_NAT_MAPPING_TIMEOUT";
  public static final int DEFAULT_NAT_MAPPING_TIMEOUT = 110;

  /**
   * Controls whether JPAN should send regular keep alive packets through the NAT to prevent losing
   * the mapping. Packets are only sent if no other activity is recorded. Default is "false".
   */
  public static final String PROPERTY_NAT_MAPPING_KEEPALIVE = "org.scion.nat.mapping.timeout";

  public static final String ENV_NAT_MAPPING_KEEPALIVE = "SCION_NAT_MAPPING_TIMEOUT";
  public static final boolean DEFAULT_NAT_MAPPING_KEEPALIVE = false;

  /** Define a custom SUN server, such as "192.168.0.42:3478" */
  public static final String PROPERTY_NAT_STUN_SERVER = "org.scion.nat.stun.server";

  public static final String ENV_NAT_STUN_SERVER = "SCION_NAT_STUN_SERVER";
  public static final String DEFAULT_NAT_STUN_SERVER =
      "stun.cloudflare.com:3478;stun.l.google.com:19302";

  /** Timeout for STUN requests to border routers or STUN servers. */
  public static final String PROPERTY_NAT_STUN_TIMEOUT_MS = "org.scion.nat.stun.timeout";

  public static final String ENV_NAT_STUN_TIMEOUT_MS = "SCION_NAT_STUN_TIMEOUT_MS";
  public static final int DEFAULT_NAT_STUN_TIMEOUT_MS = 10;

  /**
   * Non-public property that allows specifying DNS TXT entries for debugging. Example with two
   * entries: server1.com="scion=1-ff00:0:110,127.0.0.1";server2.ch="scion=1-ff00:0:112,::1"
   */
  static final String DEBUG_PROPERTY_MOCK_DNS_TXT = "DEBUG_SCION_MOCK_DNS_TXT";

  /**
   * Non-public property that allows ignoring all environment variables. This is useful for running
   * the tests on a host with a SCION installation.
   */
  static boolean debugIgnoreEnvironment = false;

  private Constants() {}
}
