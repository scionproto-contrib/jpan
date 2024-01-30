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

import java.io.Closeable;
import java.io.IOException;

public final class Scion {

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * <p>If no default instance is available, it will try to create an instance as follows:<br>
   * - CHeck and try daemon port/host<br>
   * - Check and try properties/environment for topology file location<br>
   * - Check and try properties/environment for bootstrap server IP address<br>
   * - Check and try properties/environment for DNS NAPTR record entry name<br>
   *
   * @return default instance
   */
  public static ScionService defaultService() {
    return ScionService.defaultService();
  }

  public static void closeDefault() {
    ScionService.closeDefault();
  }

  /**
   * Create a new service instance. The new service will become the default service un;less there is
   * already a default service.
   *
   * @param hostAndPort of the local daemon in the form of IP:port
   * @return new ScionService instance
   */
  public static CloseableService newServiceWithDaemon(String hostAndPort) {
    return new CloseableService(hostAndPort, ScionService.Mode.DAEMON);
  }

  /**
   * Create a new service instance. The new service will become the default service un;less there is
   * already a default service.
   *
   * @param hostName of the host whose DNS entry contains hints for control service etc.
   * @return new ScionService instance
   */
  public static CloseableService newServiceWithDNS(String hostName) {
    return new CloseableService(hostName, ScionService.Mode.BOOTSTRAP_VIA_DNS);
  }

  /**
   * Create a new service instance. The new service will become the default service un;less there is
   * already a default service.
   *
   * @param hostAndPort of the bootstrap server.
   * @return new ScionService instance
   */
  public static CloseableService newServiceWithBootstrapServer(String hostAndPort) {
    return new CloseableService(hostAndPort, ScionService.Mode.BOOTSTRAP_SERVER_IP);
  }

  /**
   * Create a new service instance. The new service will become the default service un;less there is
   * already a default service.
   *
   * @param filePath name (and location) of the topology json file.
   * @return new ScionService instance
   */
  public static CloseableService newServiceWithTopologyFile(String filePath) {
    return new CloseableService(filePath, ScionService.Mode.BOOTSTRAP_TOPO_FILE);
  }

  public static class CloseableService extends ScionService implements Closeable {

    private CloseableService(String address, Mode mode) {
      super(address, mode);
    }

    public void close() throws IOException {
      super.close();
    }
  }
}
