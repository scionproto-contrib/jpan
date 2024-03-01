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

public final class Scion {

  private Scion() {}

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

}
