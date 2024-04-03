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

public final class Constants {
  public static final String PROPERTY_DAEMON = "org.scion.daemon";
  public static final String ENV_DAEMON = "SCION_DAEMON";
  public static final String DEFAULT_DAEMON = "localhost:30255";

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

  /**
   * Disable usage of OS search domains for DNS lookup, e.g from /etc/resolv.conf. This needs to be
   * disabled for JUnit testing.
   */
  public static final String PROPERTY_USE_OS_SEARCH_DOMAINS = "SCION_USE_OS_SEARCH_DOMAINS";

  public static final String ENV_USE_OS_SEARCH_DOMAINS = "org.scion.test.useOsSearchDomains";
  public static final boolean DEFAULT_USE_OS_SEARCH_DOMAINS = true;

  /**
   * Non-public property that allows specifying DNS TXT entries for debugging. Example with two
   * entries: server1.com="scion=1-ff00:0:110,127.0.0.1";server2.ch="scion=1-ff00:0:112,::1"
   */
  static final String DEBUG_PROPERTY_MOCK_DNS_TXT = "DEBUG_SCION_MOCK_DNS_TXT";

  private Constants() {}
}
