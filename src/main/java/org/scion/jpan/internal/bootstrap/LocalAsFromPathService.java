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

package org.scion.jpan.internal.bootstrap;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parse a topology file into a local topology. */
public class LocalAsFromPathService {

  private static final Logger LOG = LoggerFactory.getLogger(LocalAsFromPathService.class.getName());

  public static LocalAS create(String pathService, TrcStore trcStore) {
    //    Daemon.ASResponse as = readASInfo(daemonService);
    //    this.localIsdAs = as.getIsdAs();
    //    this.localMtu = as.getMtu();
    //    this.isCoreAs = as.getCore();
    //    this.portRange = readLocalPortRange(daemonService);
    //    this.borderRouters.addAll(readBorderRouterAddresses(daemonService));
    return null;
  }
}
